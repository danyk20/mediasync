/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import { DurableObject } from "cloudflare:workers";
import { fail, ok, type Result } from "./http.ts";
import { NonceStore } from "./nonces.ts";

/**
 * One Durable Object per consumer–producer pair (PLAN §8.4), named by the pair ID (ADR 0009).
 *
 * It holds:
 * - the pair record: which two devices may use it, written when the consumer registers the pair;
 * - the consumer's FCM token, for wake-ups (PLAN §10.4);
 * - the signaling mailbox: a WebSocket per device (hibernation API, so an idle connection costs nothing)
 *   and a short queue for messages sent while the other device is offline;
 * - the nonces of recent requests, for the replay check.
 *
 * Signaling messages are WebRTC SDP and ICE candidates that the phones encrypted with their pair key
 * (Tink AEAD). The mailbox relays them as opaque binary frames and never parses them.
 */

export type Role = "consumer" | "producer";
export type Requirement = Role | "member";

export interface PairRecord {
    pairId: string;
    consumer: string;
    producer: string;
    createdAt: number;
    fcmToken?: string;
    lastWakeAt?: number;
}

export interface Membership {
    pairId: string;
    role: Role;
    consumer: string;
    producer: string;
    pushTokenRegistered: boolean;
    peerOnline: boolean;
}

/** An authenticated request, as the Worker hands it over: who signed it and with which nonce. */
export interface Caller {
    device: string;
    nonce: string;
}

/** Internal headers the Worker sets on WebSocket upgrades it forwards; clients can't reach the object directly. */
export const INTERNAL_DEVICE_HEADER = "x-mediasync-internal-device";
export const INTERNAL_NONCE_HEADER = "x-mediasync-internal-nonce";

export const MAX_MESSAGE_BYTES = 64 * 1024;
export const MESSAGE_TTL_MILLIS = 10 * 60 * 1000;
export const MAX_QUEUED_MESSAGES = 64;
export const MAX_QUEUED_BYTES = 256 * 1024;
/** Per connection; more than this in a minute is a misbehaving client, and the socket is closed. */
export const MAX_MESSAGES_PER_MINUTE = 120;
/** At most one wake-up per pair in this interval: one is enough to open the consumer's receive window. */
export const WAKE_MIN_INTERVAL_MILLIS = 20 * 1000;

/** WebSocket close codes (4000–4999 are for applications). */
export const CLOSE_REPLACED = 4001;
export const CLOSE_UNPAIRED = 4003;
export const CLOSE_POLICY = 1008;

const PAIR_KEY = "pair";

interface SocketState {
    device: string;
    windowStart: number;
    count: number;
}

/** Text frames the mailbox sends; everything binary is a relayed message. */
export type ControlFrame =
    | { type: "ready"; peerOnline: boolean; queued: number }
    | { type: "peer"; online: boolean }
    | { type: "error"; code: "binary_only" | "message_too_large" | "mailbox_full" | "unpaired"; message: string };

export class PairMailbox extends DurableObject<Env> {
    private readonly sql: SqlStorage;
    private readonly nonces: NonceStore;
    private schemaReady = false;

    constructor(ctx: DurableObjectState, env: Env) {
        super(ctx, env);
        this.sql = ctx.storage.sql;
        this.nonces = new NonceStore(this.sql);
        // Keep-alive pings are answered by the runtime without waking a hibernating object.
        ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
    }

    /** Tables are created on first use, and again after an unpair wiped the storage. */
    private ensureSchema(): void {
        if (this.schemaReady) return;
        this.nonces.ensureSchema();
        this.sql.exec(`CREATE TABLE IF NOT EXISTS messages (
            seq INTEGER PRIMARY KEY AUTOINCREMENT,
            recipient TEXT NOT NULL,
            body BLOB NOT NULL,
            expires_at INTEGER NOT NULL
        )`);
        this.schemaReady = true;
    }

    private record(): Promise<PairRecord | undefined> {
        return this.ctx.storage.get<PairRecord>(PAIR_KEY);
    }

    /**
     * Registers the pair, or updates the consumer's FCM token (ADR 0009). The Worker has already checked
     * that [pairId] is derived from the caller (the consumer) and [producer].
     *
     * [fcmToken]: a string sets it, `null` removes it, `undefined` keeps the current one.
     */
    async register(
        caller: Caller,
        pairId: string,
        producer: string,
        fcmToken: string | null | undefined,
        now: number = Date.now(),
    ): Promise<Result<{ created: boolean }>> {
        this.ensureSchema();
        const existing = await this.record();
        if (existing && (existing.consumer !== caller.device || existing.producer !== producer)) {
            // Can't happen with derived IDs; refuse rather than let anyone rewrite a pair.
            return fail(409, "pair_conflict", "This pair ID belongs to other devices");
        }
        if (!this.nonces.claim(caller.device, caller.nonce, now)) return this.replayed();
        const record: PairRecord = existing ?? { pairId, consumer: caller.device, producer, createdAt: now };
        if (fcmToken === null) delete record.fcmToken;
        else if (fcmToken !== undefined) record.fcmToken = fcmToken;
        await this.ctx.storage.put(PAIR_KEY, record);
        await this.scheduleCleanup();
        return ok({ created: existing === undefined });
    }

    /** Checks that the caller belongs to the pair (in the required role) and that the request is fresh. */
    async authorize(caller: Caller, need: Requirement, now: number = Date.now()): Promise<Result<Membership>> {
        this.ensureSchema();
        const record = await this.record();
        const checked = this.check(record, caller, need, now);
        if (!checked.ok) return checked;
        await this.scheduleCleanup();
        return checked;
    }

    /**
     * Claims a wake-up for the producer: returns the consumer's FCM token, or why there's none to send.
     * The Worker sends the push itself, so the object isn't kept busy while Google answers.
     */
    async claimWake(caller: Caller, now: number = Date.now()): Promise<Result<{ token: string }>> {
        this.ensureSchema();
        const record = await this.record();
        const checked = this.check(record, caller, "producer", now);
        if (!checked.ok) return checked;
        await this.scheduleCleanup();
        if (!record?.fcmToken) {
            return fail(409, "no_push_token", "The consumer hasn't registered for wake-ups");
        }
        const sinceLast = now - (record.lastWakeAt ?? 0);
        if (sinceLast < WAKE_MIN_INTERVAL_MILLIS) {
            const retryAfter = Math.ceil((WAKE_MIN_INTERVAL_MILLIS - sinceLast) / 1000);
            return fail(429, "wake_throttled", "A wake-up was sent moments ago", { "retry-after": `${retryAfter}` });
        }
        record.lastWakeAt = now;
        await this.ctx.storage.put(PAIR_KEY, record);
        return ok({ token: record.fcmToken });
    }

    /** Forgets [token] after FCM reported it unregistered, unless the consumer already replaced it. */
    async forgetPushToken(token: string): Promise<void> {
        const record = await this.record();
        if (record?.fcmToken !== token) return;
        delete record.fcmToken;
        await this.ctx.storage.put(PAIR_KEY, record);
    }

    /** Unpairing (PLAN §11, revocation): either device deletes everything the Worker holds for the pair. */
    async unpair(caller: Caller, now: number = Date.now()): Promise<Result<{ deleted: true }>> {
        this.ensureSchema();
        const checked = this.check(await this.record(), caller, "member", now);
        if (!checked.ok) return checked;
        const notice: ControlFrame = { type: "error", code: "unpaired", message: "The pair was removed" };
        for (const socket of this.ctx.getWebSockets()) {
            this.trySend(socket, JSON.stringify(notice));
            this.tryClose(socket, CLOSE_UNPAIRED, "unpaired");
        }
        await this.ctx.storage.deleteAlarm();
        await this.ctx.storage.deleteAll();
        this.schemaReady = false;
        return ok({ deleted: true });
    }

    /** WebSocket upgrade for the signaling mailbox; the Worker authenticated the caller already. */
    override async fetch(request: Request): Promise<Response> {
        if (request.headers.get("upgrade")?.toLowerCase() !== "websocket") {
            return new Response("Expected a WebSocket upgrade", { status: 426 });
        }
        const device = request.headers.get(INTERNAL_DEVICE_HEADER);
        const nonce = request.headers.get(INTERNAL_NONCE_HEADER);
        if (device === null || nonce === null) return new Response("Not authenticated", { status: 401 });

        this.ensureSchema();
        const record = await this.record();
        const checked = this.check(record, { device, nonce }, "member", Date.now());
        if (!checked.ok) {
            return Response.json({ error: { code: checked.code, message: checked.message } }, { status: checked.status });
        }

        // One connection per device: a reconnect replaces the old socket (e.g. after a network change).
        for (const old of this.ctx.getWebSockets(device)) this.tryClose(old, CLOSE_REPLACED, "replaced");

        const pair = new WebSocketPair();
        const client = pair[0];
        const server = pair[1];
        this.ctx.acceptWebSocket(server, [device]);
        const state: SocketState = { device, windowStart: Date.now(), count: 0 };
        server.serializeAttachment(state);

        const peer = this.peerOf(checked.value.role, checked.value);
        const queued = this.takeQueued(device, Date.now());
        const ready: ControlFrame = { type: "ready", peerOnline: this.isOnline(peer, server), queued: queued.length };
        server.send(JSON.stringify(ready));
        for (const message of queued) server.send(message);
        this.broadcastPresence(peer, true, server);
        await this.scheduleCleanup();
        return new Response(null, { status: 101, webSocket: client });
    }

    override async webSocketMessage(socket: WebSocket, message: string | ArrayBuffer): Promise<void> {
        const state = socket.deserializeAttachment() as SocketState;
        const now = Date.now();
        if (now - state.windowStart >= 60_000) {
            state.windowStart = now;
            state.count = 0;
        }
        state.count += 1;
        socket.serializeAttachment(state);
        if (state.count > MAX_MESSAGES_PER_MINUTE) {
            this.tryClose(socket, CLOSE_POLICY, "too many messages");
            return;
        }
        if (typeof message === "string") {
            this.sendError(socket, "binary_only", "Signaling messages are binary frames; text is for \"ping\" only");
            return;
        }
        if (message.byteLength > MAX_MESSAGE_BYTES) {
            this.sendError(socket, "message_too_large", `Messages are limited to ${MAX_MESSAGE_BYTES} bytes`);
            return;
        }
        this.ensureSchema();
        const record = await this.record();
        if (!record) {
            this.sendError(socket, "unpaired", "The pair was removed");
            this.tryClose(socket, CLOSE_UNPAIRED, "unpaired");
            return;
        }
        const peer = state.device === record.consumer ? record.producer : record.consumer;
        const online = this.ctx.getWebSockets(peer);
        if (online.length > 0) {
            for (const target of online) this.trySend(target, message);
            return;
        }
        if (!this.enqueue(peer, message, now)) {
            this.sendError(socket, "mailbox_full", "The other device is offline and its mailbox is full");
            return;
        }
        await this.scheduleCleanup();
    }

    override async webSocketClose(socket: WebSocket, code: number, reason: string): Promise<void> {
        // Complete the closing handshake; the runtime may already have done so.
        this.tryClose(socket, code === 1005 || code === 1006 ? 1000 : code, reason);
        const state = socket.deserializeAttachment() as SocketState | null;
        if (!state) return;
        const record = await this.record();
        if (!record) return;
        const stillOnline = this.ctx.getWebSockets(state.device).some((other) => other !== socket);
        if (stillOnline) return;
        const peer = state.device === record.consumer ? record.producer : record.consumer;
        this.broadcastPresence(peer, false, socket);
    }

    override async webSocketError(socket: WebSocket): Promise<void> {
        this.tryClose(socket, 1011, "error");
    }

    /** Deletes expired messages and nonces, so ciphertext doesn't outlive its use. */
    override async alarm(): Promise<void> {
        this.ensureSchema();
        const now = Date.now();
        this.sql.exec("DELETE FROM messages WHERE expires_at <= ?", now);
        this.nonces.purge(now);
        await this.scheduleCleanup();
    }

    private check(record: PairRecord | undefined, caller: Caller, need: Requirement, now: number): Result<Membership> {
        if (!record) return fail(404, "pair_not_found", "No such pair; the consumer registers it after pairing");
        let role: Role;
        if (caller.device === record.consumer) role = "consumer";
        else if (caller.device === record.producer) role = "producer";
        else return fail(403, "not_a_member", "This device isn't part of the pair");
        if (need !== "member" && need !== role) return fail(403, `not_${need}`, `Only the ${need} can do this`);
        if (!this.nonces.claim(caller.device, caller.nonce, now)) return this.replayed();
        const peer = role === "consumer" ? record.producer : record.consumer;
        return ok({
            pairId: record.pairId,
            role,
            consumer: record.consumer,
            producer: record.producer,
            pushTokenRegistered: record.fcmToken !== undefined,
            peerOnline: this.isOnline(peer),
        });
    }

    private replayed<T>(): Result<T> {
        return fail(401, "auth_replayed", "This request was already used");
    }

    private peerOf(role: Role, membership: Membership): string {
        return role === "consumer" ? membership.producer : membership.consumer;
    }

    private isOnline(device: string, except?: WebSocket): boolean {
        return this.ctx.getWebSockets(device).some((socket) => socket !== except);
    }

    private broadcastPresence(device: string, online: boolean, except: WebSocket): void {
        const frame: ControlFrame = { type: "peer", online };
        for (const socket of this.ctx.getWebSockets(device)) {
            if (socket !== except) this.trySend(socket, JSON.stringify(frame));
        }
    }

    private enqueue(recipient: string, message: ArrayBuffer, now: number): boolean {
        this.sql.exec("DELETE FROM messages WHERE expires_at <= ?", now);
        const usage = this.sql
            .exec<{ count: number; bytes: number | null }>(
                "SELECT COUNT(*) AS count, SUM(LENGTH(body)) AS bytes FROM messages WHERE recipient = ?",
                recipient,
            )
            .one();
        if (usage.count >= MAX_QUEUED_MESSAGES || (usage.bytes ?? 0) + message.byteLength > MAX_QUEUED_BYTES) {
            return false;
        }
        this.sql.exec(
            "INSERT INTO messages (recipient, body, expires_at) VALUES (?, ?, ?)",
            recipient,
            message,
            now + MESSAGE_TTL_MILLIS,
        );
        return true;
    }

    /** Removes and returns the unexpired messages waiting for [recipient], oldest first. */
    private takeQueued(recipient: string, now: number): ArrayBuffer[] {
        const rows = this.sql
            .exec<{ body: ArrayBuffer }>(
                "SELECT body FROM messages WHERE recipient = ? AND expires_at > ? ORDER BY seq",
                recipient,
                now,
            )
            .toArray();
        this.sql.exec("DELETE FROM messages WHERE recipient = ?", recipient);
        return rows.map((row) => row.body);
    }

    /** Arms the alarm for the earliest expiry of a queued message or nonce. */
    private async scheduleCleanup(): Promise<void> {
        const nextMessage = this.sql
            .exec<{ next: number | null }>("SELECT MIN(expires_at) AS next FROM messages")
            .one().next;
        const candidates = [nextMessage, this.nonces.nextExpiry()].filter((t): t is number => t !== null);
        if (candidates.length === 0) return;
        const next = Math.min(...candidates);
        const current = await this.ctx.storage.getAlarm();
        if (current === null || current > next) await this.ctx.storage.setAlarm(next);
    }

    private sendError(socket: WebSocket, code: Extract<ControlFrame, { type: "error" }>["code"], message: string): void {
        const frame: ControlFrame = { type: "error", code, message };
        this.trySend(socket, JSON.stringify(frame));
    }

    private trySend(socket: WebSocket, data: string | ArrayBuffer): void {
        try {
            socket.send(data);
        } catch {
            // The socket is closing; the message is lost, as signaling tolerates (the phones re-offer).
        }
    }

    private tryClose(socket: WebSocket, code: number, reason: string): void {
        try {
            socket.close(code, reason);
        } catch {
            // Already closed.
        }
    }
}

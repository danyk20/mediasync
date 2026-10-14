/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import { caller, meterStub, pairStub, type RequestContext } from "../context.ts";
import { ApiError, badRequest, json, parseJsonObject, requiredCount, requiredString, unwrap } from "../http.ts";
import { pairTag } from "../identity.ts";
import { enforceLimit } from "../rate-limit.ts";
import { type IceServer, mintTurnCredentials, STUN_SERVERS, TurnError } from "../turn.ts";

/** Why the relay isn't offered; the app words its message from this (PLAN §8.5). */
export type RelayUnavailableReason = "budget_exhausted" | "not_configured" | "unavailable";

export interface IceServersResponse {
    iceServers: IceServer[];
    /** How long the TURN credentials stay valid; the app asks again before they expire. */
    ttlSeconds: number;
    relay: { available: true } | { available: false; reason: RelayUnavailableReason; resumesAt?: string };
}

const DEFAULT_TTL_SECONDS = 3600;

function credentialTtl(env: Env): number {
    const ttl = Number(env.TURN_CREDENTIAL_TTL_SECONDS);
    // PLAN §8.4: credentials live 1 h, which bounds any overshoot once the budget is reached.
    return Number.isInteger(ttl) && ttl >= 60 && ttl <= DEFAULT_TTL_SECONDS ? ttl : DEFAULT_TTL_SECONDS;
}

/**
 * `POST /v1/pairs/{pairId}/ice-servers`: STUN servers, plus TURN credentials while the budget allows.
 *
 * Always answers 200 with at least STUN: the relay being paused, unconfigured or down must not stop a
 * direct connection (PLAN §8.5, "Wi-Fi, Turbo and direct internet connections keep working").
 */
export async function iceServers(context: RequestContext): Promise<Response> {
    const { env } = context;
    await enforceLimit(env.TURN_LIMITER, `turn:${context.device.id}`);
    unwrap(await pairStub(env, context.pairId).authorize(caller(context), "member", context.now));

    const ttlSeconds = credentialTtl(env);
    const stunOnly = (relay: IceServersResponse["relay"]): Response =>
        json({ iceServers: STUN_SERVERS, ttlSeconds, relay } satisfies IceServersResponse);

    const meter = await meterStub(env).view(undefined, context.now);
    if (!meter.relayAvailable) {
        return stunOnly({ available: false, reason: "budget_exhausted", resumesAt: meter.period.end });
    }
    if (!env.TURN_KEY_ID || !env.TURN_KEY_API_TOKEN) return stunOnly({ available: false, reason: "not_configured" });
    try {
        const servers = await mintTurnCredentials({
            keyId: env.TURN_KEY_ID,
            apiToken: env.TURN_KEY_API_TOKEN,
            ttlSeconds,
            customIdentifier: pairTag(context.pairId),
        });
        return json({ iceServers: servers, ttlSeconds, relay: { available: true } } satisfies IceServersResponse);
    } catch (error) {
        console.warn(`TURN credentials unavailable: ${error instanceof TurnError ? error.message : "request failed"}`);
        return stunOnly({ available: false, reason: "unavailable" });
    }
}

/** `GET /v1/pairs/{pairId}/relay-usage`: the meter, with this pair's approximate share (PLAN §8.5). */
export async function getRelayUsage(context: RequestContext): Promise<Response> {
    unwrap(await pairStub(context.env, context.pairId).authorize(caller(context), "member", context.now));
    return json(await meterStub(context.env).view(pairTag(context.pairId), context.now));
}

const SESSION_PATTERN = /^[A-Za-z0-9_-]{22}$/;

/**
 * `POST /v1/pairs/{pairId}/relay-usage`: a live report during a relayed session (PLAN §8.5).
 * Body: `{"session": "<base64url of 16 random bytes, one per WebRTC connection>", "relayedBytes": <n>}`,
 * where `relayedBytes` is the cumulative bytes sent plus received on the selected candidate pair since the
 * connection started, reported only while the local candidate is of type `relay`.
 */
export async function reportRelayUsage(context: RequestContext): Promise<Response> {
    const body = parseJsonObject(context.body);
    const session = requiredString(body, "session", 22);
    if (!SESSION_PATTERN.test(session)) throw badRequest('"session" must be 16 bytes in base64url (22 characters)');
    const relayedBytes = requiredCount(body, "relayedBytes");
    unwrap(await pairStub(context.env, context.pairId).authorize(caller(context), "member", context.now));
    return json(await meterStub(context.env).report(pairTag(context.pairId), session, relayedBytes, context.now));
}

/** Admin devices, from the optional `RELAY_ADMIN_DEVICE_IDS` secret: comma-separated device IDs. */
function admins(env: Env): Set<string> {
    return new Set(
        (env.RELAY_ADMIN_DEVICE_IDS ?? "")
            .split(",")
            .map((id) => id.trim())
            .filter((id) => id !== ""),
    );
}

/**
 * `PUT /v1/relay/settings`: changes the budget, billing period start and paid usage (PLAN §8.5,
 * "Adjustable in Settings"). Only admin devices may; without any configured, settings come from the vars.
 * Body: `{"budgetGb": 950, "periodStartDay": 1, "allowPaid": false, "paidCapUsd": 0}` (all fields).
 */
export async function updateRelaySettings(context: RequestContext): Promise<Response> {
    if (!admins(context.env).has(context.device.id)) {
        throw new ApiError(403, "not_admin", "This device may not change relay settings");
    }
    const body = parseJsonObject(context.body);
    const meter = meterStub(context.env);
    const settings = unwrap(await meter.updateSettings(context.device.id, context.device.nonce, body, context.now));
    // A new period start day changes which analytics count; refresh rather than wait for the cron.
    context.ctx.waitUntil(meter.refresh());
    return json(settings);
}

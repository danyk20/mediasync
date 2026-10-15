/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import { runDurableObjectAlarm, runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import {
    CLOSE_POLICY,
    CLOSE_REPLACED,
    CLOSE_UNPAIRED,
    INTERNAL_DEVICE_HEADER,
    MAX_MESSAGE_BYTES,
    MAX_MESSAGES_PER_MINUTE,
    MAX_QUEUED_MESSAGES,
} from "../src/pair-mailbox.ts";
import { TestDevice } from "./support/device.ts";
import { TestSocket } from "./support/socket.ts";
import { errorCode, pairStub, registeredPair, send } from "./support/worker.ts";

const bytes = (...values: number[]) => new Uint8Array(values);

async function queuedCount(pairId: string): Promise<number> {
    return runInDurableObject(pairStub(pairId), (_, state) =>
        state.storage.sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM messages").one().n,
    );
}

describe("GET /v1/pairs/{pairId}/signal", () => {
    it("relays opaque binary messages between the two devices and reports presence", async () => {
        const { consumer, producer, pairId } = await registeredPair();
        const consumerSocket = await TestSocket.open(consumer, pairId);
        expect(await consumerSocket.nextControl()).toEqual({ type: "ready", peerOnline: false, queued: 0 });

        const producerSocket = await TestSocket.open(producer, pairId);
        expect(await producerSocket.nextControl()).toEqual({ type: "ready", peerOnline: true, queued: 0 });
        expect(await consumerSocket.nextControl()).toEqual({ type: "peer", online: true });

        producerSocket.send(bytes(1, 2, 3));
        expect(await consumerSocket.nextBinary()).toEqual(bytes(1, 2, 3));
        consumerSocket.send(bytes(4, 5));
        expect(await producerSocket.nextBinary()).toEqual(bytes(4, 5));

        producerSocket.close();
        expect(await consumerSocket.nextControl()).toEqual({ type: "peer", online: false });
        consumerSocket.close();
    });

    it("keeps messages for an offline device and delivers them, in order, when it connects", async () => {
        const { consumer, producer, pairId } = await registeredPair();
        const producerSocket = await TestSocket.open(producer, pairId);
        await producerSocket.nextControl();
        producerSocket.send(bytes(1));
        producerSocket.send(bytes(2));
        await producerSocket.drain();
        expect(await queuedCount(pairId)).toBe(2);

        const consumerSocket = await TestSocket.open(consumer, pairId);
        expect(await consumerSocket.nextControl()).toEqual({ type: "ready", peerOnline: true, queued: 2 });
        expect(await consumerSocket.nextBinary()).toEqual(bytes(1));
        expect(await consumerSocket.nextBinary()).toEqual(bytes(2));
        expect(await queuedCount(pairId)).toBe(0);
        producerSocket.close();
        consumerSocket.close();
    });

    it("drops queued messages once they expire", async () => {
        const { producer, pairId } = await registeredPair();
        const socket = await TestSocket.open(producer, pairId);
        await socket.nextControl();
        socket.send(bytes(9));
        await socket.drain();
        expect(await queuedCount(pairId)).toBe(1);

        // Age the message, then let the cleanup alarm run.
        await runInDurableObject(pairStub(pairId), (_, state) => {
            state.storage.sql.exec("UPDATE messages SET expires_at = 1");
        });
        expect(await runDurableObjectAlarm(pairStub(pairId))).toBe(true);
        expect(await queuedCount(pairId)).toBe(0);
        socket.close();
    });

    it("answers keep-alive pings without involving the mailbox", async () => {
        const { producer, pairId } = await registeredPair();
        const socket = await TestSocket.open(producer, pairId);
        await socket.nextControl();
        socket.send("ping");
        expect(await socket.next()).toBe("pong");
        socket.close();
    });

    it("rejects text frames, oversized messages and a full mailbox without closing", async () => {
        const { producer, pairId } = await registeredPair();
        const socket = await TestSocket.open(producer, pairId);
        await socket.nextControl();

        socket.send("hello");
        expect(await socket.nextControl()).toMatchObject({ type: "error", code: "binary_only" });

        socket.send(new Uint8Array(MAX_MESSAGE_BYTES + 1));
        expect(await socket.nextControl()).toMatchObject({ type: "error", code: "message_too_large" });

        for (let i = 0; i < MAX_QUEUED_MESSAGES; i++) socket.send(bytes(i));
        socket.send(bytes(255));
        expect(await socket.nextControl()).toMatchObject({ type: "error", code: "mailbox_full" });
        expect(await queuedCount(pairId)).toBe(MAX_QUEUED_MESSAGES);
        expect(socket.closed).toBeNull();
        socket.close();
    });

    it("closes a connection that floods the mailbox", async () => {
        const { consumer, producer, pairId } = await registeredPair();
        const consumerSocket = await TestSocket.open(consumer, pairId);
        await consumerSocket.nextControl();
        const socket = await TestSocket.open(producer, pairId);
        await socket.nextControl();
        for (let i = 0; i <= MAX_MESSAGES_PER_MINUTE; i++) socket.send(bytes(1));
        expect((await socket.waitForClose()).code).toBe(CLOSE_POLICY);
        consumerSocket.close();
    });

    it("replaces an older connection of the same device", async () => {
        const { producer, pairId } = await registeredPair();
        const first = await TestSocket.open(producer, pairId);
        await first.nextControl();
        const second = await TestSocket.open(producer, pairId);
        await second.nextControl();
        expect((await first.waitForClose()).code).toBe(CLOSE_REPLACED);
        expect(second.closed).toBeNull();
        second.close();
    });

    it("closes both connections when the pair is removed", async () => {
        const { consumer, producer, pairId } = await registeredPair();
        const consumerSocket = await TestSocket.open(consumer, pairId);
        await consumerSocket.nextControl();
        const producerSocket = await TestSocket.open(producer, pairId);
        await producerSocket.nextControl();
        await consumerSocket.nextControl(); // peer online

        expect((await send(await consumer.request("DELETE", `/v1/pairs/${pairId}`))).status).toBe(200);
        expect(await producerSocket.nextControl()).toMatchObject({ type: "error", code: "unpaired" });
        expect((await producerSocket.waitForClose()).code).toBe(CLOSE_UNPAIRED);
        expect((await consumerSocket.waitForClose()).code).toBe(CLOSE_UNPAIRED);
    });

    it("refuses devices outside the pair, unknown pairs and plain HTTP", async () => {
        const { consumer, pairId } = await registeredPair();
        const stranger = await TestDevice.create();
        const upgrade = { headers: { upgrade: "websocket" } };

        const forbidden = await send(await stranger.request("GET", `/v1/pairs/${pairId}/signal`, undefined, upgrade));
        expect(forbidden.status).toBe(403);
        expect(await errorCode(forbidden)).toBe("not_a_member");

        const unknown = await send(await stranger.request("GET", `/v1/pairs/${"1".repeat(64)}/signal`, undefined, upgrade));
        expect(unknown.status).toBe(404);

        const plain = await send(await consumer.request("GET", `/v1/pairs/${pairId}/signal`));
        expect(plain.status).toBe(426);
        expect(await errorCode(plain)).toBe("websocket_required");
    });

    it("ignores identity headers smuggled in by the client", async () => {
        const { consumer, pairId } = await registeredPair();
        const stranger = await TestDevice.create();
        const response = await send(
            await stranger.request("GET", `/v1/pairs/${pairId}/signal`, undefined, {
                headers: { upgrade: "websocket", [INTERNAL_DEVICE_HEADER]: consumer.id },
            }),
        );
        expect(response.status).toBe(403);
    });

    it("refuses a replayed upgrade request", async () => {
        const { producer, pairId } = await registeredPair();
        const request = await producer.request("GET", `/v1/pairs/${pairId}/signal`, undefined, {
            headers: { upgrade: "websocket" },
        });
        const first = await send(request.clone());
        expect(first.status).toBe(101);
        first.webSocket?.accept();
        first.webSocket?.close(1000, "done");
        const replay = await send(request);
        expect(replay.status).toBe(401);
        expect(await errorCode(replay)).toBe("auth_replayed");
    });
});

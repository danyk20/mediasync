/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import { runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { derivePairId } from "../src/identity.ts";
import type { PairRecord } from "../src/pair-mailbox.ts";
import { TestDevice } from "./support/device.ts";
import { errorCode, pairStub, registeredPair, send } from "./support/worker.ts";

async function record(pairId: string): Promise<PairRecord | undefined> {
    return runInDurableObject(pairStub(pairId), (_, state) => state.storage.get<PairRecord>("pair"));
}

describe("PUT /v1/pairs/{pairId}", () => {
    it("registers the pair for the consumer that signed, then answers 200 when repeated", async () => {
        const consumer = await TestDevice.create();
        const producer = await TestDevice.create();
        const pairId = await derivePairId(consumer.id, producer.id);

        const first = await send(await consumer.request("PUT", `/v1/pairs/${pairId}`, { producer: producer.id }));
        expect(first.status).toBe(201);
        expect(await first.json()).toEqual({ pairId, created: true });

        const again = await send(await consumer.request("PUT", `/v1/pairs/${pairId}`, { producer: producer.id }));
        expect(again.status).toBe(200);
        expect(await again.json()).toEqual({ pairId, created: false });

        expect(await record(pairId)).toMatchObject({ pairId, consumer: consumer.id, producer: producer.id });
    });

    it("sets, keeps and removes the consumer's FCM token", async () => {
        const { consumer, producer, pairId } = await registeredPair("token-1");
        expect((await record(pairId))?.fcmToken).toBe("token-1");

        await send(await consumer.request("PUT", `/v1/pairs/${pairId}`, { producer: producer.id }));
        expect((await record(pairId))?.fcmToken).toBe("token-1");

        await send(await consumer.request("PUT", `/v1/pairs/${pairId}`, { producer: producer.id, fcmToken: "token-2" }));
        expect((await record(pairId))?.fcmToken).toBe("token-2");

        await send(await consumer.request("PUT", `/v1/pairs/${pairId}`, { producer: producer.id, fcmToken: null }));
        expect((await record(pairId))?.fcmToken).toBeUndefined();
    });

    it("refuses a pair ID that isn't derived from the signer as consumer", async () => {
        const consumer = await TestDevice.create();
        const producer = await TestDevice.create();
        const pairId = await derivePairId(consumer.id, producer.id);

        // The producer can't register the pair: with it in the consumer slot the ID doesn't match.
        const response = await send(await producer.request("PUT", `/v1/pairs/${pairId}`, { producer: producer.id }));
        expect(response.status).toBe(400);
        expect(await errorCode(response)).toBe("bad_request");

        const swapped = await send(await producer.request("PUT", `/v1/pairs/${pairId}`, { producer: consumer.id }));
        expect(swapped.status).toBe(400);
        expect(await errorCode(swapped)).toBe("pair_id_mismatch");
        expect(await record(pairId)).toBeUndefined();
    });

    it.each([
        [{}, "bad_request"],
        [{ producer: "ABC" }, "bad_request"],
        [{ producer: "a".repeat(64), fcmToken: 7 }, "bad_request"],
        [{ producer: "a".repeat(64), fcmToken: "" }, "bad_request"],
    ])("validates the body %j", async (body, code) => {
        const consumer = await TestDevice.create();
        const pairId = await derivePairId(consumer.id, "a".repeat(64));
        const response = await send(await consumer.request("PUT", `/v1/pairs/${pairId}`, body));
        expect(response.status).toBe(400);
        expect(await errorCode(response)).toBe(code);
    });

    it("refuses invalid JSON", async () => {
        const consumer = await TestDevice.create();
        const pairId = await derivePairId(consumer.id, "a".repeat(64));
        const response = await send(await consumer.request("PUT", `/v1/pairs/${pairId}`, new TextEncoder().encode("{")));
        expect(response.status).toBe(400);
    });
});

describe("GET /v1/pairs/{pairId}", () => {
    it("tells each member its role and whether the other device is online", async () => {
        const { consumer, producer, pairId } = await registeredPair("token");
        const asConsumer = await send(await consumer.request("GET", `/v1/pairs/${pairId}`));
        expect(asConsumer.status).toBe(200);
        expect(await asConsumer.json()).toEqual({ pairId, role: "consumer", pushTokenRegistered: true, peerOnline: false });

        const asProducer = await send(await producer.request("GET", `/v1/pairs/${pairId}`));
        expect(await asProducer.json()).toMatchObject({ role: "producer" });
    });

    it("refuses other devices and unknown pairs", async () => {
        const { pairId } = await registeredPair();
        const stranger = await TestDevice.create();
        const forbidden = await send(await stranger.request("GET", `/v1/pairs/${pairId}`));
        expect(forbidden.status).toBe(403);
        expect(await errorCode(forbidden)).toBe("not_a_member");

        const unknown = await send(await stranger.request("GET", `/v1/pairs/${"0".repeat(64)}`));
        expect(unknown.status).toBe(404);
        expect(await errorCode(unknown)).toBe("pair_not_found");
    });

    it("refuses a replayed request", async () => {
        const { consumer, pairId } = await registeredPair();
        const request = await consumer.request("GET", `/v1/pairs/${pairId}`);
        expect((await send(request.clone())).status).toBe(200);
        const replay = await send(request);
        expect(replay.status).toBe(401);
        expect(await errorCode(replay)).toBe("auth_replayed");
    });
});

describe("DELETE /v1/pairs/{pairId}", () => {
    it.each(["consumer", "producer"] as const)("lets the %s remove everything stored for the pair", async (who) => {
        const pair = await registeredPair("token");
        const response = await send(await pair[who].request("DELETE", `/v1/pairs/${pair.pairId}`));
        expect(response.status).toBe(200);
        expect(await response.json()).toEqual({ deleted: true });
        expect(await record(pair.pairId)).toBeUndefined();

        const after = await send(await pair.consumer.request("GET", `/v1/pairs/${pair.pairId}`));
        expect(after.status).toBe(404);
    });

    it("can be registered again after unpairing", async () => {
        const { consumer, producer, pairId } = await registeredPair();
        await send(await consumer.request("DELETE", `/v1/pairs/${pairId}`));
        const again = await send(await consumer.request("PUT", `/v1/pairs/${pairId}`, { producer: producer.id }));
        expect(again.status).toBe(201);
        expect((await send(await producer.request("GET", `/v1/pairs/${pairId}`))).status).toBe(200);
    });

    it("refuses other devices", async () => {
        const { pairId } = await registeredPair();
        const stranger = await TestDevice.create();
        expect((await send(await stranger.request("DELETE", `/v1/pairs/${pairId}`))).status).toBe(403);
        expect(await record(pairId)).toBeDefined();
    });
});

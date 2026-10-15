/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import { env, runInDurableObject } from "cloudflare:test";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { RequestContext } from "../src/context.ts";
import { iceServers, type IceServersResponse } from "../src/handlers/relay.ts";
import { pairTag } from "../src/identity.ts";
import { BYTES_PER_GB } from "../src/meter/settings.ts";
import type { Snapshot } from "../src/meter/usage-meter.ts";
import { billingPeriod } from "../src/meter/period.ts";
import { STUN_SERVERS } from "../src/turn.ts";
import { TestDevice } from "./support/device.ts";
import {
    errorCode,
    jsonResponse,
    meterStub,
    mockFetch,
    RATE_LIMIT_TEST_TIMEOUT_MILLIS,
    registeredPair,
    roomInRateLimitWindow,
    send,
} from "./support/worker.ts";

const TURN_RESPONSE = {
    iceServers: [
        { urls: ["stun:stun.cloudflare.com:3478", "stun:stun.cloudflare.com:53"] },
        {
            urls: ["turn:turn.cloudflare.com:3478?transport=udp", "turns:turn.cloudflare.com:443?transport=tcp"],
            username: "user-1",
            credential: "secret-1",
        },
    ],
};

afterEach(() => {
    vi.restoreAllMocks();
});

async function setUsage(totalBytes: number): Promise<void> {
    const now = Date.now();
    const snapshot: Snapshot = { periodStart: billingPeriod(now, 1).start, takenAt: now, totalBytes, byTag: {} };
    await runInDurableObject(meterStub(), (_, state) => state.storage.put("snapshot", snapshot));
}

describe("POST /v1/pairs/{pairId}/ice-servers", () => {
    it("mints 1-hour TURN credentials tagged with the pair", async () => {
        const { producer, pairId } = await registeredPair();
        const { requests } = mockFetch(() => jsonResponse(TURN_RESPONSE, 201));

        const response = await send(await producer.request("POST", `/v1/pairs/${pairId}/ice-servers`));
        expect(response.status).toBe(200);
        expect(await response.json()).toEqual({
            iceServers: TURN_RESPONSE.iceServers,
            ttlSeconds: 3600,
            relay: { available: true },
        } satisfies IceServersResponse);

        expect(requests).toHaveLength(1);
        const request = requests[0]!;
        expect(request.method).toBe("POST");
        expect(request.url).toBe(
            "https://rtc.live.cloudflare.com/v1/turn/keys/test-turn-key-id/credentials/generate-ice-servers",
        );
        expect(request.headers.get("authorization")).toBe("Bearer test-turn-api-token");
        expect(request.json()).toEqual({ ttl: 3600, customIdentifier: pairTag(pairId) });
    });

    it("hands out STUN only, with the reset date, once the budget is used up", async () => {
        const { consumer, pairId } = await registeredPair();
        const { requests } = mockFetch(() => jsonResponse(TURN_RESPONSE, 201));
        await setUsage(950 * BYTES_PER_GB);

        const response = await send(await consumer.request("POST", `/v1/pairs/${pairId}/ice-servers`));
        expect(response.status).toBe(200);
        const body = (await response.json()) as IceServersResponse;
        expect(body.iceServers).toEqual(STUN_SERVERS);
        expect(body.relay).toEqual({
            available: false,
            reason: "budget_exhausted",
            resumesAt: new Date(billingPeriod(Date.now(), 1).end).toISOString(),
        });
        expect(requests).toHaveLength(0);
    });

    it("still mints credentials just below the budget", async () => {
        const { consumer, pairId } = await registeredPair();
        mockFetch(() => jsonResponse(TURN_RESPONSE, 201));
        await setUsage(950 * BYTES_PER_GB - 1);
        const body = (await (await send(await consumer.request("POST", `/v1/pairs/${pairId}/ice-servers`))).json()) as IceServersResponse;
        expect(body.relay).toEqual({ available: true });
    });

    it.each([
        ["an HTTP error", () => new Response("nope", { status: 500 })],
        ["a response without credentials", () => jsonResponse({ iceServers: [{ urls: ["stun:x"] }] }, 201)],
        ["a network failure", () => Promise.reject(new Error("offline"))],
    ])("falls back to STUN only after %s from Cloudflare", async (_, reply) => {
        const { consumer, pairId } = await registeredPair();
        mockFetch(reply as () => Response);
        const response = await send(await consumer.request("POST", `/v1/pairs/${pairId}/ice-servers`));
        expect(response.status).toBe(200);
        const body = (await response.json()) as IceServersResponse;
        expect(body.iceServers).toEqual(STUN_SERVERS);
        expect(body.relay).toEqual({ available: false, reason: "unavailable" });
    });

    it("hands out STUN only when no TURN key is configured", async () => {
        const { consumer, pairId } = await registeredPair();
        const { requests } = mockFetch(() => jsonResponse(TURN_RESPONSE, 201));
        const request = await consumer.request("POST", `/v1/pairs/${pairId}/ice-servers`);
        const context: RequestContext = {
            request,
            env: { ...env, TURN_KEY_ID: "", TURN_KEY_API_TOKEN: "" },
            ctx: { waitUntil: () => {}, passThroughOnException: () => {}, props: {} } as unknown as ExecutionContext,
            url: new URL(request.url),
            body: new Uint8Array(),
            device: { id: consumer.id, nonce: request.headers.get("x-mediasync-nonce")!, timestamp: 0 },
            pairId,
            now: Date.now(),
        };
        const body = (await (await iceServers(context)).json()) as IceServersResponse;
        expect(body.relay).toEqual({ available: false, reason: "not_configured" });
        expect(requests).toHaveLength(0);
    });

    it("refuses devices outside the pair", async () => {
        const { pairId } = await registeredPair();
        const { requests } = mockFetch(() => jsonResponse(TURN_RESPONSE, 201));
        const stranger = await TestDevice.create();
        const response = await send(await stranger.request("POST", `/v1/pairs/${pairId}/ice-servers`));
        expect(response.status).toBe(403);
        expect(requests).toHaveLength(0);
    });

    it("limits how often one device asks for credentials", async () => {
        await roomInRateLimitWindow();
        const { producer, pairId } = await registeredPair();
        mockFetch(() => jsonResponse(TURN_RESPONSE, 201));
        const statuses: number[] = [];
        for (let i = 0; i < 11; i++) {
            statuses.push((await send(await producer.request("POST", `/v1/pairs/${pairId}/ice-servers`))).status);
        }
        expect(statuses.slice(0, 10).every((status) => status === 200)).toBe(true);
        const limited = await send(await producer.request("POST", `/v1/pairs/${pairId}/ice-servers`));
        expect(limited.status).toBe(429);
        expect(limited.headers.get("retry-after")).toBe("60");
        expect(await errorCode(limited)).toBe("rate_limited");
    }, RATE_LIMIT_TEST_TIMEOUT_MILLIS);
});

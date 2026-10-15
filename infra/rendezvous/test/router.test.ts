/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import { describe, expect, it } from "vitest";
import { MAX_BODY_BYTES } from "../src/http.ts";
import { BASE_URL, TestDevice } from "./support/device.ts";
import { errorCode, registeredPair, send } from "./support/worker.ts";

describe("routing", () => {
    it("answers the health check without authentication", async () => {
        const response = await send(new Request(`${BASE_URL}/v1/health`));
        expect(response.status).toBe(200);
        expect(await response.json()).toEqual({ status: "ok" });
        expect(response.headers.get("cache-control")).toBe("no-store");
        expect((await send(new Request(`${BASE_URL}/v1/health`, { method: "POST" }))).status).toBe(405);
    });

    it.each(["/", "/v1/pairs", "/v1/pairs/ABC", `/v1/pairs/${"a".repeat(64)}/other`, "/v2/health"])(
        "answers 404 for %s",
        async (path) => {
            const response = await send(new Request(`${BASE_URL}${path}`));
            expect(response.status).toBe(404);
            expect(await errorCode(response)).toBe("not_found");
        },
    );

    it("answers 405 with the allowed methods", async () => {
        const response = await send(new Request(`${BASE_URL}/v1/pairs/${"a".repeat(64)}/wake`));
        expect(response.status).toBe(405);
        expect(response.headers.get("allow")).toBe("POST");
    });

    it("requires authentication everywhere else", async () => {
        const response = await send(new Request(`${BASE_URL}/v1/pairs/${"a".repeat(64)}`));
        expect(response.status).toBe(401);
        expect(await errorCode(response)).toBe("auth_missing");
    });

    it("refuses bodies over the limit before checking anything else", async () => {
        const device = await TestDevice.create();
        const body = new Uint8Array(MAX_BODY_BYTES + 1);
        const response = await send(await device.request("POST", `/v1/pairs/${"a".repeat(64)}/wake`, body));
        expect(response.status).toBe(413);
        expect(await errorCode(response)).toBe("payload_too_large");
    });

    it("stops reading a streamed body without Content-Length once it's over the limit", async () => {
        const chunk = new Uint8Array(MAX_BODY_BYTES / 2 + 1);
        const body = new ReadableStream<Uint8Array>({
            start(controller) {
                controller.enqueue(chunk);
                controller.enqueue(chunk);
                controller.close();
            },
        });
        const request = new Request(`${BASE_URL}/v1/pairs/${"a".repeat(64)}/wake`, { method: "POST", body });
        expect(request.headers.get("content-length")).toBeNull();
        const response = await send(request);
        expect(response.status).toBe(413);
    });
});

describe("rate limiting", () => {
    it("limits requests per client address before authentication", async () => {
        const address = "192.0.2.77";
        const statuses: number[] = [];
        for (let i = 0; i < 121; i++) {
            const response = await send(
                new Request(`${BASE_URL}/v1/pairs/${"a".repeat(64)}`, { headers: { "cf-connecting-ip": address } }),
            );
            statuses.push(response.status);
        }
        expect(statuses.slice(0, 120).every((status) => status === 401)).toBe(true);
        expect(statuses[120]).toBe(429);
    });

    it("limits requests per device", async () => {
        const { consumer, pairId } = await registeredPair();
        const statuses: number[] = [];
        // Registration counted once already.
        for (let i = 0; i < 60; i++) {
            statuses.push((await send(await consumer.request("GET", `/v1/pairs/${pairId}`))).status);
        }
        expect(statuses.slice(0, 59).every((status) => status === 200)).toBe(true);
        expect(statuses[59]).toBe(429);
    });
});

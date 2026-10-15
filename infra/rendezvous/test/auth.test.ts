/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import { describe, expect, it } from "vitest";
import { AUTH_HEADERS, authenticate, canonicalString, MAX_CLOCK_SKEW_SECONDS, SERVER_TIME_HEADER } from "../src/auth.ts";
import { base64UrlEncode, utf8 } from "../src/encoding.ts";
import { ApiError } from "../src/http.ts";
import { derivePairId } from "../src/identity.ts";
import golden from "./fixtures/golden-request.json";
import { BASE_URL, TestDevice } from "./support/device.ts";
import { send } from "./support/worker.ts";

async function check(request: Request, now = Date.now()) {
    const body = new Uint8Array(await request.clone().arrayBuffer());
    return authenticate(request, body, now);
}

async function failure(request: Request, now = Date.now()): Promise<string> {
    try {
        await check(request, now);
    } catch (error) {
        expect(error).toBeInstanceOf(ApiError);
        expect((error as ApiError).status).toBe(401);
        return (error as ApiError).code;
    }
    throw new Error("Expected authentication to fail");
}

describe("canonicalString", () => {
    it("joins the fields with newlines, upper-cases the method and lower-cases the host", () => {
        expect(
            canonicalString({
                method: "post",
                host: "Rendezvous.Example.com",
                path: "/v1/pairs/ab/wake",
                query: "x=1",
                timestamp: "1792000000",
                nonce: "AAECAwQFBgcICQoLDA0ODw",
                bodySha256Hex: "e3b0",
            }),
        ).toBe("MEDIASYNC-AUTH-V1\nPOST\nrendezvous.example.com\n/v1/pairs/ab/wake\nx=1\n1792000000\nAAECAwQFBgcICQoLDA0ODw\ne3b0");
    });
});

describe("authenticate", () => {
    it("accepts a request signed by the certificate's key and returns the certificate hash as device ID", async () => {
        const device = await TestDevice.create();
        const request = await device.request("POST", "/v1/pairs/abc/wake?x=1", { payload: "a" });
        const verified = await check(request);
        expect(verified.id).toBe(device.id);
        expect(verified.nonce).toBe(request.headers.get(AUTH_HEADERS.nonce));
    });

    it("verifies a request signed by OpenSSL, as an independent implementation (golden vector)", async () => {
        const request = new Request(golden.url, { method: golden.method, headers: golden.headers, body: golden.body });
        const verified = await check(request, Number(golden.headers["X-MediaSync-Timestamp"]) * 1000);
        expect(verified.id).toBe(golden.deviceId);
        expect(await derivePairId(golden.deviceId, golden.producerId)).toBe(golden.pairId);
    });

    it("refuses the golden vector once anything in it changes", async () => {
        const now = Number(golden.headers["X-MediaSync-Timestamp"]) * 1000;
        const tampered = new Request(golden.url, { method: golden.method, headers: golden.headers, body: `${golden.body} ` });
        expect(await failure(tampered, now)).toBe("auth_bad_signature");
    });

    it.each([
        ["method", { method: "PUT" }],
        ["host", { url: "https://other.test/v1/pairs/abc" }],
        ["path", { url: `${BASE_URL}/v1/pairs/abd` }],
        ["query", { url: `${BASE_URL}/v1/pairs/abc?x=2` }],
        ["body", { body: utf8('{"a":2}') }],
    ])("refuses a signature over a different %s", async (_, sign) => {
        const device = await TestDevice.create();
        const request = await device.request("POST", "/v1/pairs/abc", { a: 1 }, { sign });
        expect(await failure(request)).toBe("auth_bad_signature");
    });

    it("refuses another device's signature over the same certificate", async () => {
        const device = await TestDevice.create();
        const impostor = await TestDevice.create();
        const request = await impostor.request("GET", "/v1/pairs/abc");
        const headers = new Headers(request.headers);
        headers.set(AUTH_HEADERS.certificate, base64UrlEncode(device.certificate));
        expect(await failure(new Request(request.url, { headers }))).toBe("auth_bad_signature");
    });

    it.each(Object.values(AUTH_HEADERS))("refuses a request without %s", async (header) => {
        const device = await TestDevice.create();
        const request = await device.request("GET", "/v1/pairs/abc");
        const headers = new Headers(request.headers);
        headers.delete(header);
        expect(await failure(new Request(request.url, { headers }))).toBe("auth_missing");
    });

    it.each([
        ["a padded nonce", AUTH_HEADERS.nonce, "AAECAwQFBgcICQoLDA0ODw=="],
        ["a short nonce", AUTH_HEADERS.nonce, "AAECAwQFBgcICQoLDA0O"],
        ["a non-numeric timestamp", AUTH_HEADERS.timestamp, "12a"],
        ["a certificate that isn't DER", AUTH_HEADERS.certificate, base64UrlEncode(utf8("hello"))],
        ["a huge certificate", AUTH_HEADERS.certificate, base64UrlEncode(new Uint8Array(5000))],
        ["a raw (non-DER) signature", AUTH_HEADERS.signature, base64UrlEncode(new Uint8Array(64).fill(1))],
        ["standard base64", AUTH_HEADERS.signature, "ab+/"],
    ])("refuses %s", async (_, header, value) => {
        const device = await TestDevice.create();
        const request = await device.request("GET", "/v1/pairs/abc");
        const headers = new Headers(request.headers);
        headers.set(header, value);
        expect(await failure(new Request(request.url, { headers }))).toBe("auth_malformed");
    });

    it("refuses a certificate whose key isn't P-256", async () => {
        const device = await TestDevice.create("P-384");
        expect(await failure(await device.request("GET", "/v1/pairs/abc"))).toBe("auth_malformed");
    });

    it(`allows ${MAX_CLOCK_SKEW_SECONDS} s of clock difference, not more`, async () => {
        const device = await TestDevice.create();
        const now = Date.now();
        const nowSeconds = Math.floor(now / 1000);
        const slow = await device.request("GET", "/v1/pairs/abc", undefined, { timestamp: nowSeconds - MAX_CLOCK_SKEW_SECONDS });
        await expect(check(slow, nowSeconds * 1000)).resolves.toMatchObject({ id: device.id });
        const tooSlow = await device.request("GET", "/v1/pairs/abc", undefined, {
            timestamp: nowSeconds - MAX_CLOCK_SKEW_SECONDS - 1,
        });
        expect(await failure(tooSlow, nowSeconds * 1000)).toBe("auth_clock_skew");
        const tooFast = await device.request("GET", "/v1/pairs/abc", undefined, {
            timestamp: nowSeconds + MAX_CLOCK_SKEW_SECONDS + 1,
        });
        expect(await failure(tooFast, nowSeconds * 1000)).toBe("auth_clock_skew");
    });

    it("tells a device with a wrong clock the Worker's time", async () => {
        const device = await TestDevice.create();
        const request = await device.request("GET", `/v1/pairs/${"a".repeat(64)}`, undefined, { timestamp: 1_000_000 });
        const response = await send(request);
        expect(response.status).toBe(401);
        const serverTime = Number(response.headers.get(SERVER_TIME_HEADER));
        expect(Math.abs(serverTime - Date.now() / 1000)).toBeLessThan(5);
    });
});

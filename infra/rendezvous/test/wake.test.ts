/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import { env, runInDurableObject } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { RequestContext } from "../src/context.ts";
import { base64UrlDecode, utf8 } from "../src/encoding.ts";
import { clearAccessTokenCache, FCM_SCOPE } from "../src/fcm.ts";
import { wake } from "../src/handlers/wake.ts";
import { pairTag } from "../src/identity.ts";
import type { PairRecord } from "../src/pair-mailbox.ts";
import type { Pair } from "./support/worker.ts";
import { errorCode, jsonResponse, mockFetch, pairStub, type RecordedRequest, registeredPair, send } from "./support/worker.ts";

const TOKEN_URL = "https://oauth2.googleapis.com/token";
const FCM_URL = "https://fcm.googleapis.com/v1/projects/mediasync-test/messages:send";

beforeEach(() => clearAccessTokenCache());
afterEach(() => {
    vi.restoreAllMocks();
});

interface Google {
    tokenRequests: number;
    fcmStatus: number[];
    fcmBody?: string;
}

/** Fakes Google's token endpoint and FCM. [fcmStatus] answers the sends in order (default 200). */
function fakeGoogle(google: Google) {
    return mockFetch((request) => {
        if (request.url === TOKEN_URL) {
            google.tokenRequests += 1;
            return jsonResponse({ access_token: `access-${google.tokenRequests}`, expires_in: 3600, token_type: "Bearer" });
        }
        if (request.url === FCM_URL) {
            const status = google.fcmStatus.shift() ?? 200;
            return new Response(google.fcmBody ?? JSON.stringify({ name: "projects/mediasync-test/messages/1" }), {
                status,
            });
        }
        throw new Error(`Unexpected request to ${request.url}`);
    });
}

async function pemPublicKey(pem: string): Promise<CryptoKey> {
    const base64 = pem.replace(/-----(BEGIN|END) PUBLIC KEY-----/g, "").replace(/\s+/g, "");
    const der = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
    return crypto.subtle.importKey("spki", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]);
}

async function wakeRequest(pair: Pair, body?: unknown) {
    return send(await pair.producer.request("POST", `/v1/pairs/${pair.pairId}/wake`, body));
}

async function record(pairId: string): Promise<PairRecord | undefined> {
    return runInDurableObject(pairStub(pairId), (_, state) => state.storage.get<PairRecord>("pair"));
}

async function allowNextWake(pairId: string): Promise<void> {
    await runInDurableObject(pairStub(pairId), async (_, state) => {
        const current = (await state.storage.get<PairRecord>("pair"))!;
        await state.storage.put("pair", { ...current, lastWakeAt: 0 });
    });
}

describe("POST /v1/pairs/{pairId}/wake", () => {
    it("sends a high-priority FCM data message to the consumer's token", async () => {
        const pair = await registeredPair("fcm-token-1");
        const google: Google = { tokenRequests: 0, fcmStatus: [] };
        const { requests } = fakeGoogle(google);

        const response = await wakeRequest(pair, { payload: "c2VhbGVk" });
        expect(response.status).toBe(200);
        expect(await response.json()).toEqual({ sent: true });

        const send = requests.find((r) => r.url === FCM_URL)!;
        expect(send.headers.get("authorization")).toBe("Bearer access-1");
        expect(send.json()).toEqual({
            message: {
                token: "fcm-token-1",
                data: { type: "wake", v: "1", pair: pair.pairId, payload: "c2VhbGVk" },
                android: { priority: "HIGH", ttl: "60s", collapse_key: `wake-${pairTag(pair.pairId)}` },
            },
        });
    });

    it("authenticates to Google with a JWT signed by the service account", async () => {
        const pair = await registeredPair("fcm-token-1");
        const { requests } = fakeGoogle({ tokenRequests: 0, fcmStatus: [] });
        expect((await wakeRequest(pair)).status).toBe(200);

        const tokenRequest: RecordedRequest = requests.find((r) => r.url === TOKEN_URL)!;
        expect(tokenRequest.headers.get("content-type")).toBe("application/x-www-form-urlencoded");
        const form = new URLSearchParams(tokenRequest.body);
        expect(form.get("grant_type")).toBe("urn:ietf:params:oauth:grant-type:jwt-bearer");

        const [header, claims, signature] = form.get("assertion")!.split(".") as [string, string, string];
        const verified = await crypto.subtle.verify(
            "RSASSA-PKCS1-v1_5",
            await pemPublicKey(env.TEST_FCM_PUBLIC_KEY),
            base64UrlDecode(signature),
            utf8(`${header}.${claims}`),
        );
        expect(verified).toBe(true);
        const decode = (part: string) => JSON.parse(new TextDecoder().decode(base64UrlDecode(part))) as Record<string, unknown>;
        expect(decode(header)).toEqual({ alg: "RS256", typ: "JWT", kid: "test-key-id" });
        const payload = decode(claims);
        expect(payload).toMatchObject({
            iss: "fcm@mediasync-test.iam.gserviceaccount.com",
            scope: FCM_SCOPE,
            aud: TOKEN_URL,
        });
        expect((payload.exp as number) - (payload.iat as number)).toBe(3600);
        expect(Math.abs((payload.iat as number) - Date.now() / 1000)).toBeLessThan(60);
    });

    it("reuses the access token for later wake-ups", async () => {
        const pair = await registeredPair("fcm-token-1");
        const google: Google = { tokenRequests: 0, fcmStatus: [] };
        fakeGoogle(google);
        expect((await wakeRequest(pair)).status).toBe(200);
        await allowNextWake(pair.pairId);
        expect((await wakeRequest(pair)).status).toBe(200);
        expect(google.tokenRequests).toBe(1);
    });

    it("gets a new access token and retries once when FCM rejects the old one", async () => {
        const pair = await registeredPair("fcm-token-1");
        const google: Google = { tokenRequests: 0, fcmStatus: [401, 200] };
        const { requests } = fakeGoogle(google);
        expect((await wakeRequest(pair)).status).toBe(200);
        expect(google.tokenRequests).toBe(2);
        expect(requests.filter((r) => r.url === FCM_URL).map((r) => r.headers.get("authorization"))).toEqual([
            "Bearer access-1",
            "Bearer access-2",
        ]);
    });

    it("allows one wake-up per pair every 20 seconds", async () => {
        const pair = await registeredPair("fcm-token-1");
        fakeGoogle({ tokenRequests: 0, fcmStatus: [] });
        expect((await wakeRequest(pair)).status).toBe(200);
        const throttled = await wakeRequest(pair);
        expect(throttled.status).toBe(429);
        expect(await errorCode(throttled)).toBe("wake_throttled");
        expect(Number(throttled.headers.get("retry-after"))).toBeGreaterThan(0);
        expect(Number(throttled.headers.get("retry-after"))).toBeLessThanOrEqual(20);
    });

    it("forgets a token FCM reports as unregistered", async () => {
        const pair = await registeredPair("fcm-token-1");
        fakeGoogle({
            tokenRequests: 0,
            fcmStatus: [404],
            fcmBody: JSON.stringify({ error: { status: "NOT_FOUND", details: [{ errorCode: "UNREGISTERED" }] } }),
        });
        const response = await wakeRequest(pair);
        expect(response.status).toBe(410);
        expect(await errorCode(response)).toBe("push_token_unregistered");
        expect((await record(pair.pairId))?.fcmToken).toBeUndefined();
    });

    it("reports an FCM outage and keeps the token", async () => {
        const pair = await registeredPair("fcm-token-1");
        fakeGoogle({ tokenRequests: 0, fcmStatus: [503] });
        const response = await wakeRequest(pair);
        expect(response.status).toBe(502);
        expect(await errorCode(response)).toBe("push_failed");
        expect((await record(pair.pairId))?.fcmToken).toBe("fcm-token-1");
    });

    it("reports a failing token endpoint", async () => {
        const pair = await registeredPair("fcm-token-1");
        mockFetch(() => new Response("down", { status: 500 }));
        const response = await wakeRequest(pair);
        expect(response.status).toBe(502);
    });

    it("needs the consumer to have registered a token", async () => {
        const pair = await registeredPair();
        const { requests } = fakeGoogle({ tokenRequests: 0, fcmStatus: [] });
        const response = await wakeRequest(pair);
        expect(response.status).toBe(409);
        expect(await errorCode(response)).toBe("no_push_token");
        expect(requests).toHaveLength(0);
    });

    it("lets only the producer wake the consumer", async () => {
        const pair = await registeredPair("fcm-token-1");
        fakeGoogle({ tokenRequests: 0, fcmStatus: [] });
        const response = await send(await pair.consumer.request("POST", `/v1/pairs/${pair.pairId}/wake`));
        expect(response.status).toBe(403);
        expect(await errorCode(response)).toBe("not_producer");
    });

    it("refuses a payload that isn't base64url", async () => {
        const pair = await registeredPair("fcm-token-1");
        expect((await wakeRequest(pair, { payload: "not base64!" })).status).toBe(400);
        expect((await wakeRequest(pair, { payload: "a".repeat(2049) })).status).toBe(400);
    });

    it.each([
        ["missing", ""],
        ["not JSON", "{"],
        ["incomplete", JSON.stringify({ project_id: "p" })],
        [
            "holding a bad key",
            JSON.stringify({ project_id: "p", client_email: "e", private_key: "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----" }),
        ],
    ])("answers 503 when the service account is %s", async (_, account) => {
        const pair = await registeredPair("fcm-token-1");
        fakeGoogle({ tokenRequests: 0, fcmStatus: [] });
        const request = await pair.producer.request("POST", `/v1/pairs/${pair.pairId}/wake`);
        const context: RequestContext = {
            request,
            env: { ...env, FCM_SERVICE_ACCOUNT: account },
            ctx: {} as ExecutionContext,
            url: new URL(request.url),
            body: new Uint8Array(),
            device: { id: pair.producer.id, nonce: request.headers.get("x-mediasync-nonce")!, timestamp: 0 },
            pairId: pair.pairId,
            now: Date.now(),
        };
        await expect(wake(context)).rejects.toMatchObject({ status: 503, code: "push_not_configured" });
    });
});

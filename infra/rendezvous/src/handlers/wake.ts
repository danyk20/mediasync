/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import { caller, pairStub, type RequestContext } from "../context.ts";
import { FcmConfigError, parseServiceAccount, type ServiceAccount, sendWake, type WakeOutcome } from "../fcm.ts";
import { ApiError, badRequest, json, optionalString, parseJsonObject, unwrap } from "../http.ts";
import { pairTag } from "../identity.ts";
import { enforceLimit } from "../rate-limit.ts";

/** The optional payload is the producer's own ciphertext (pair key); 2 KiB fits easily in FCM's 4 KB. */
const MAX_PAYLOAD_LENGTH = 2048;
const PAYLOAD_PATTERN = /^[A-Za-z0-9_-]*$/;

function serviceAccount(env: Env): ServiceAccount {
    if (!env.FCM_SERVICE_ACCOUNT) {
        throw new ApiError(503, "push_not_configured", "This rendezvous has no FCM service account");
    }
    try {
        return parseServiceAccount(env.FCM_SERVICE_ACCOUNT);
    } catch (error) {
        console.warn(error instanceof FcmConfigError ? error.message : "FCM_SERVICE_ACCOUNT is invalid");
        throw new ApiError(503, "push_not_configured", "This rendezvous' FCM service account is invalid");
    }
}

type Sender = (token: string, data: Record<string, string>, collapseKey: string) => Promise<WakeOutcome>;

/**
 * How this Worker delivers wake-ups: through FCM with its own service account, or, as a household's Worker (bring
 * your own Cloudflare, PLAN §10.4), through the developer's wake relay, which holds the app's FCM service account.
 */
function sender(env: Env): Sender {
    if (env.FCM_SERVICE_ACCOUNT) {
        const account = serviceAccount(env);
        return async (token, data, collapseKey) => {
            try {
                return await sendWake({ account, token, data, collapseKey });
            } catch (error) {
                if (!(error instanceof FcmConfigError)) throw error;
                console.warn(error.message);
                throw new ApiError(503, "push_not_configured", "This rendezvous' FCM service account is invalid");
            }
        };
    }
    if (env.WAKE_RELAY_URL) return (token, data, collapseKey) => relayWake(env.WAKE_RELAY_URL, token, data, collapseKey);
    throw new ApiError(503, "push_not_configured", "This rendezvous has no FCM service account or wake relay");
}

/**
 * Hands the wake-up to the wake relay at [relayUrl] (`POST /v1/relay/wake`). FCM's own answer comes back as an
 * outcome; when the relay itself can't be used, `wake_relay_failed` says why, so the sending phone's log does too.
 */
async function relayWake(
    relayUrl: string,
    token: string,
    data: Record<string, string>,
    collapseKey: string,
): Promise<WakeOutcome> {
    let response: Response;
    try {
        response = await fetch(new URL("/v1/relay/wake", relayUrl), {
            method: "POST",
            headers: { "content-type": "application/json" },
            body: JSON.stringify({ token, data, collapseKey }),
        });
    } catch (error) {
        const reason = error instanceof Error ? error.message : "unknown error";
        throw new ApiError(502, "wake_relay_failed", `The wake relay couldn't be reached: ${reason}`);
    }
    if (response.ok) return "sent";
    if (response.status === 410) return "unregistered";
    const answer = await relayAnswer(response);
    if (answer === "push_failed") return "failed"; // The relay works; FCM didn't take the message.
    throw new ApiError(502, "wake_relay_failed", `The wake relay answered HTTP ${response.status} (${answer})`);
}

/**
 * What a failing relay said: its error code, or Cloudflare's own, such as `error code: 1042` for a fetch to a Worker on
 * the same zone that Cloudflare refused.
 */
async function relayAnswer(response: Response): Promise<string> {
    const text = (await response.text()).slice(0, MAX_ANSWER_LENGTH);
    try {
        const code = (JSON.parse(text) as { error?: { code?: unknown } }).error?.code;
        if (typeof code === "string") return code;
    } catch {
        // Not the relay's JSON: an error page of Cloudflare's.
    }
    const cloudflare = /error code:?\s*(\d+)/i.exec(text);
    return cloudflare ? `Cloudflare error ${cloudflare[1]}` : "no explanation";
}

const MAX_ANSWER_LENGTH = 2048;

/**
 * `POST /v1/pairs/{pairId}/wake`: the producer asks the Worker to wake the consumer (PLAN §10.4).
 * Body (optional): `{"payload": "<base64url, at most 2048 characters>"}`, passed through untouched.
 *
 * The consumer receives an FCM data message `{"type": "wake", "v": "1", "pair": "<pair ID>", "payload"?}`.
 */
export async function wake(context: RequestContext): Promise<Response> {
    const body = parseJsonObject(context.body);
    const payload = optionalString(body, "payload", MAX_PAYLOAD_LENGTH);
    if (payload !== undefined && !PAYLOAD_PATTERN.test(payload)) throw badRequest('"payload" must be base64url');
    const send = sender(context.env);

    const pair = pairStub(context.env, context.pairId);
    const { token } = unwrap(await pair.claimWake(caller(context), context.now));
    const data: Record<string, string> = { type: "wake", v: "1", pair: context.pairId };
    if (payload !== undefined) data.payload = payload;

    return delivered(pair, token, await send(token, data, `wake-${pairTag(context.pairId)}`));
}

/**
 * `POST /v1/pairs/{pairId}/wake-check`: the consumer checks that wake-ups reach it, as a producer's would: the same
 * way out (FCM, or the wake relay) to its own token. It receives `{"type": "wake-check", "v": "1", "pair": "<pair ID>"}`,
 * which wakes nothing. Answers like a wake-up.
 */
export async function wakeCheck(context: RequestContext): Promise<Response> {
    const send = sender(context.env);
    const pair = pairStub(context.env, context.pairId);
    const { token } = unwrap(await pair.claimWakeCheck(caller(context), context.now));
    const data = { type: "wake-check", v: "1", pair: context.pairId };
    return delivered(pair, token, await send(token, data, `check-${pairTag(context.pairId)}`));
}

async function delivered(
    pair: ReturnType<typeof pairStub>,
    token: string,
    outcome: WakeOutcome,
): Promise<Response> {
    switch (outcome) {
        case "sent":
            return json({ sent: true });
        case "unregistered":
            await pair.forgetPushToken(token);
            throw new ApiError(410, "push_token_unregistered", "The consumer's FCM token is no longer valid");
        case "failed":
            throw new ApiError(502, "push_failed", "FCM didn't accept the wake-up; try again later");
    }
}

const MAX_TOKEN_LENGTH = 4096;
const MAX_DATA_ENTRIES = 8;
const MAX_DATA_LENGTH = 3072;
const MAX_COLLAPSE_KEY_LENGTH = 100;

/**
 * `POST /v1/relay/wake`, on the developer's wake relay only (`WAKE_RELAY_ENABLED`): sends a household Worker's
 * wake-up through FCM with the app's service account (PLAN §10.4). Body: `{"token", "data", "collapseKey"}`. There's
 * no device to authenticate: the caller is another Worker. The FCM token is the capability, and each is limited to a
 * few wake-ups a minute. Nothing is stored or logged.
 */
export async function relayedWake(env: Env, body: Uint8Array): Promise<Response> {
    if (env.WAKE_RELAY_ENABLED !== "true") throw new ApiError(404, "not_found", "No such endpoint");
    const account = serviceAccount(env);
    const fields = parseJsonObject(body);
    const token = optionalString(fields, "token", MAX_TOKEN_LENGTH);
    const collapseKey = optionalString(fields, "collapseKey", MAX_COLLAPSE_KEY_LENGTH);
    const data = fields.data;
    if (!token || !collapseKey) throw badRequest('"token" and "collapseKey" are required');
    if (typeof data !== "object" || data === null || Array.isArray(data)) throw badRequest('"data" must be an object');
    const entries = Object.entries(data as Record<string, unknown>);
    const strings = entries.every(([, value]) => typeof value === "string");
    if (!strings || entries.length > MAX_DATA_ENTRIES || JSON.stringify(data).length > MAX_DATA_LENGTH) {
        throw badRequest('"data" must be a few short strings');
    }
    // Per phone: one rate-limit key per token, hashed so the limiter never holds the token itself.
    const key = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(token)));
    const hex = Array.from(key.slice(0, 16), (b) => b.toString(16).padStart(2, "0")).join("");
    await enforceLimit(env.TURN_LIMITER, `wake:${hex}`);

    let outcome: WakeOutcome;
    try {
        outcome = await sendWake({ account, token, data: data as Record<string, string>, collapseKey });
    } catch (error) {
        if (!(error instanceof FcmConfigError)) throw error;
        console.warn(error.message);
        throw new ApiError(503, "push_not_configured", "The wake relay's FCM service account is invalid");
    }
    switch (outcome) {
        case "sent":
            return json({ sent: true });
        case "unregistered":
            throw new ApiError(410, "push_token_unregistered", "The FCM token is no longer valid");
        case "failed":
            throw new ApiError(502, "push_failed", "FCM didn't accept the wake-up; try again later");
    }
}

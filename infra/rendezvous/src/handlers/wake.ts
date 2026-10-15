/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import { caller, pairStub, type RequestContext } from "../context.ts";
import { FcmConfigError, parseServiceAccount, type ServiceAccount, sendWake } from "../fcm.ts";
import { ApiError, badRequest, json, optionalString, parseJsonObject, unwrap } from "../http.ts";
import { pairTag } from "../identity.ts";

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
    const account = serviceAccount(context.env);

    const pair = pairStub(context.env, context.pairId);
    const { token } = unwrap(await pair.claimWake(caller(context), context.now));
    const data: Record<string, string> = { type: "wake", v: "1", pair: context.pairId };
    if (payload !== undefined) data.payload = payload;

    let outcome;
    try {
        outcome = await sendWake({ account, token, data, collapseKey: `wake-${pairTag(context.pairId)}` });
    } catch (error) {
        if (!(error instanceof FcmConfigError)) throw error;
        console.warn(error.message);
        throw new ApiError(503, "push_not_configured", "This rendezvous' FCM service account is invalid");
    }
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

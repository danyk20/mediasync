/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import { caller, pairStub, type RequestContext } from "../context.ts";
import { ApiError, badRequest, json, optionalString, parseJsonObject, requiredString, unwrap } from "../http.ts";
import { derivePairId, isDeviceId } from "../identity.ts";
import { INTERNAL_DEVICE_HEADER, INTERNAL_NONCE_HEADER } from "../pair-mailbox.ts";

/** FCM registration tokens are around 160 characters; this leaves room for format changes. */
const MAX_FCM_TOKEN_LENGTH = 4096;

/**
 * `PUT /v1/pairs/{pairId}`: the consumer registers a pair after pairing, and later updates its FCM token.
 * Body: `{"producer": "<device ID>", "fcmToken": "<token>" | null}` (`fcmToken` optional).
 */
export async function registerPair(context: RequestContext): Promise<Response> {
    const body = parseJsonObject(context.body);
    const producer = requiredString(body, "producer", 64);
    if (!isDeviceId(producer)) throw badRequest('"producer" must be a device ID: 64 lowercase hex characters');
    if (producer === context.device.id) throw badRequest("A device can't pair with itself");
    const fcmToken = body.fcmToken === null ? null : optionalString(body, "fcmToken", MAX_FCM_TOKEN_LENGTH);
    if (fcmToken === "") throw badRequest('"fcmToken" must not be empty; send null to remove it');

    // The signer is the consumer by construction: the ID only matches with the signer in the consumer slot.
    if ((await derivePairId(context.device.id, producer)) !== context.pairId) {
        throw new ApiError(400, "pair_id_mismatch", "The pair ID isn't derived from this consumer and producer");
    }
    const result = unwrap(
        await pairStub(context.env, context.pairId).register(caller(context), context.pairId, producer, fcmToken, context.now),
    );
    return json({ pairId: context.pairId, created: result.created }, result.created ? 201 : 200);
}

/** `GET /v1/pairs/{pairId}`: whether the pair is registered, this device's role, and the peer's presence. */
export async function getPair(context: RequestContext): Promise<Response> {
    const membership = unwrap(await pairStub(context.env, context.pairId).authorize(caller(context), "member", context.now));
    return json({
        pairId: membership.pairId,
        role: membership.role,
        pushTokenRegistered: membership.pushTokenRegistered,
        peerOnline: membership.peerOnline,
    });
}

/** `DELETE /v1/pairs/{pairId}`: unpairing; either device removes everything stored for the pair. */
export async function deletePair(context: RequestContext): Promise<Response> {
    unwrap(await pairStub(context.env, context.pairId).unpair(caller(context), context.now));
    return json({ deleted: true });
}

/**
 * `GET /v1/pairs/{pairId}/signal` with `Upgrade: websocket`: the signaling mailbox.
 *
 * The Durable Object does the membership and replay checks, so the verified identity travels in internal
 * headers. Any client-supplied copies of those headers are dropped first.
 */
export async function openSignaling(context: RequestContext): Promise<Response> {
    if (context.request.headers.get("upgrade")?.toLowerCase() !== "websocket") {
        throw new ApiError(426, "websocket_required", "This endpoint needs a WebSocket upgrade");
    }
    const headers = new Headers(context.request.headers);
    headers.delete(INTERNAL_DEVICE_HEADER);
    headers.delete(INTERNAL_NONCE_HEADER);
    headers.set(INTERNAL_DEVICE_HEADER, context.device.id);
    headers.set(INTERNAL_NONCE_HEADER, context.device.nonce);
    return pairStub(context.env, context.pairId).fetch(new Request(context.request.url, { headers }));
}

/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import { concatBytes, fromHex, sha256, toHex, utf8 } from "./encoding.ts";

/**
 * Device and pair identifiers (ADR 0009).
 *
 * A device ID is the same value the app pins at pairing: the lowercase hex SHA-256 of the device's
 * DER-encoded self-signed certificate (`DeviceId` in `:core:model`, PLAN §11).
 */

const ID_PATTERN = /^[0-9a-f]{64}$/;

export function isDeviceId(value: string): boolean {
    return ID_PATTERN.test(value);
}

export function isPairId(value: string): boolean {
    return ID_PATTERN.test(value);
}

const PAIR_ID_LABEL = utf8("mediasync-pair-v1");

/**
 * The mailbox ID of a consumer–producer pair:
 * `hex(SHA-256("mediasync-pair-v1" || consumerId || producerId))` over the raw 32-byte IDs.
 *
 * Both phones compute it from IDs they already know after pairing, so nothing new has to be exchanged.
 * It isn't secret: only the two member devices can use the mailbox, which the Worker enforces.
 */
export async function derivePairId(consumerId: string, producerId: string): Promise<string> {
    return toHex(await sha256(concatBytes(PAIR_ID_LABEL, fromHex(consumerId), fromHex(producerId))));
}

/**
 * The per-pair tag attached to TURN credentials as Cloudflare's `customIdentifier`, so the analytics can
 * break relay usage down by pair (PLAN §8.5). The first 128 bits of the pair ID: unique in practice, and
 * short enough for any identifier limit.
 */
export function pairTag(pairId: string): string {
    return pairId.slice(0, 32);
}

/** Short form for log lines; never used to identify anything. */
export function short(id: string): string {
    return id.slice(0, 8);
}

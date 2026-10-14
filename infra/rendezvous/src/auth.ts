/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import { DerError, ecdsaDerToP1363, subjectPublicKeyInfo } from "./der.ts";
import { base64UrlDecode, sha256, toHex, utf8 } from "./encoding.ts";
import { ApiError } from "./http.ts";

/**
 * Device authentication (ADR 0009, PLAN §8.4).
 *
 * There are no accounts. Every request is signed with the phone's EC P-256 identity key, the same
 * keystore key that authenticates its TLS sessions. The request carries the device's self-signed
 * certificate; the Worker hashes it to get the device ID (the pin the paired phone already stores),
 * takes the public key from it and checks the signature over a canonical description of the request.
 *
 * Replays are refused in two layers: the timestamp must be within [MAX_CLOCK_SKEW_SECONDS] of the
 * Worker's clock, and the nonce must not have been seen from that device before. Nonces are remembered by
 * the Durable Object that handles the request (`NonceStore`), because only it has storage.
 */

export const AUTH_LABEL = "MEDIASYNC-AUTH-V1";

export const AUTH_HEADERS = {
    certificate: "X-MediaSync-Certificate",
    timestamp: "X-MediaSync-Timestamp",
    nonce: "X-MediaSync-Nonce",
    signature: "X-MediaSync-Signature",
} as const;

/** Sent with `auth_clock_skew`: the Worker's time in Unix seconds, so the app can correct its clock. */
export const SERVER_TIME_HEADER = "X-MediaSync-Server-Time";

export const MAX_CLOCK_SKEW_SECONDS = 300;
export const NONCE_BYTES = 16;
/** Keystore certificates are around 400–600 bytes; anything far bigger isn't one. */
export const MAX_CERTIFICATE_BYTES = 4096;

export interface CanonicalRequest {
    method: string;
    host: string;
    path: string;
    /** The raw query string without the leading `?`; empty when there is none. */
    query: string;
    timestamp: string;
    nonce: string;
    bodySha256Hex: string;
}

/** The exact text a device signs, UTF-8 encoded, lines separated by `\n`, no trailing newline. */
export function canonicalString(request: CanonicalRequest): string {
    return [
        AUTH_LABEL,
        request.method.toUpperCase(),
        request.host.toLowerCase(),
        request.path,
        request.query,
        request.timestamp,
        request.nonce,
        request.bodySha256Hex,
    ].join("\n");
}

export interface AuthenticatedDevice {
    /** Lowercase hex SHA-256 of the certificate: the device ID the paired phone pins. */
    id: string;
    /** Base64url nonce from the request, for the replay check. */
    nonce: string;
    /** Unix seconds from the request. */
    timestamp: number;
}

function unauthorized(code: string, message: string): ApiError {
    return new ApiError(401, code, message);
}

function decodeHeader(request: Request, name: string): Uint8Array {
    const value = request.headers.get(name);
    if (value === null || value === "") throw unauthorized("auth_missing", `Missing ${name}`);
    try {
        return base64UrlDecode(value);
    } catch {
        throw unauthorized("auth_malformed", `${name} isn't base64url without padding`);
    }
}

/**
 * Verifies the request's signature and returns the device that made it.
 *
 * [body] must be the exact bytes the request carried; the caller reads it once and passes it on.
 * Throws an [ApiError] with status 401 for every failure.
 */
export async function authenticate(request: Request, body: Uint8Array, nowMillis: number): Promise<AuthenticatedDevice> {
    const certificate = decodeHeader(request, AUTH_HEADERS.certificate);
    const signature = decodeHeader(request, AUTH_HEADERS.signature);
    const nonceText = request.headers.get(AUTH_HEADERS.nonce) ?? "";
    const nonce = decodeHeader(request, AUTH_HEADERS.nonce);
    const timestampText = request.headers.get(AUTH_HEADERS.timestamp) ?? "";

    if (nonce.length !== NONCE_BYTES) throw unauthorized("auth_malformed", `The nonce must be ${NONCE_BYTES} bytes`);
    if (certificate.length > MAX_CERTIFICATE_BYTES) throw unauthorized("auth_malformed", "Certificate too large");
    if (!/^[0-9]{1,12}$/.test(timestampText)) {
        throw unauthorized(timestampText === "" ? "auth_missing" : "auth_malformed", "Bad timestamp");
    }
    const timestamp = Number(timestampText);
    if (Math.abs(nowMillis / 1000 - timestamp) > MAX_CLOCK_SKEW_SECONDS) {
        // The app can correct for a wrong phone clock with the Worker's time and retry once.
        throw new ApiError(401, "auth_clock_skew", `The timestamp is more than ${MAX_CLOCK_SKEW_SECONDS} s off`, {
            [SERVER_TIME_HEADER]: `${Math.floor(nowMillis / 1000)}`,
        });
    }

    let key: CryptoKey;
    let rawSignature: Uint8Array;
    try {
        key = await crypto.subtle.importKey(
            "spki",
            subjectPublicKeyInfo(certificate),
            { name: "ECDSA", namedCurve: "P-256" },
            false,
            ["verify"],
        );
        rawSignature = ecdsaDerToP1363(signature);
    } catch (error) {
        // DerError: not a certificate or signature. DOMException: not an EC P-256 key.
        const detail = error instanceof DerError ? error.message : "The certificate's key isn't EC P-256";
        throw unauthorized("auth_malformed", detail);
    }

    const url = new URL(request.url);
    const message = canonicalString({
        method: request.method,
        host: url.host,
        path: url.pathname,
        query: url.search.replace(/^\?/, ""),
        timestamp: timestampText,
        nonce: nonceText,
        bodySha256Hex: toHex(await sha256(body)),
    });
    const valid = await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, key, rawSignature, utf8(message));
    if (!valid) throw unauthorized("auth_bad_signature", "The signature doesn't match the request");

    return { id: toHex(await sha256(certificate)), nonce: nonceText, timestamp };
}

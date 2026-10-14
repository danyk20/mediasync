/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */

/** Byte and text encodings shared by the protocol code. Strict decoders: malformed input throws. */

const encoder = new TextEncoder();

export function utf8(text: string): Uint8Array {
    return encoder.encode(text);
}

/** Base64url without padding (RFC 4648 §5), the encoding used in every MediaSync header. */
export function base64UrlEncode(bytes: Uint8Array): string {
    let binary = "";
    for (const byte of bytes) binary += String.fromCharCode(byte);
    return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
}

const BASE64URL = /^[A-Za-z0-9_-]*$/;

/** Decodes base64url without padding. Rejects padding, other alphabets and impossible lengths. */
export function base64UrlDecode(text: string): Uint8Array {
    if (!BASE64URL.test(text) || text.length % 4 === 1) throw new Error("Not base64url");
    const padded = text.replaceAll("-", "+").replaceAll("_", "/") + "=".repeat((4 - (text.length % 4)) % 4);
    const binary = atob(padded);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    // Non-canonical encodings (unused low bits set) would let two strings name the same bytes.
    if (base64UrlEncode(bytes) !== text) throw new Error("Non-canonical base64url");
    return bytes;
}

export function toHex(bytes: Uint8Array): string {
    let hex = "";
    for (const byte of bytes) hex += byte.toString(16).padStart(2, "0");
    return hex;
}

const LOWER_HEX = /^(?:[0-9a-f]{2})*$/;

/** Decodes lowercase hex, the form device and pair IDs take everywhere. */
export function fromHex(hex: string): Uint8Array {
    if (!LOWER_HEX.test(hex)) throw new Error("Not lowercase hex");
    const bytes = new Uint8Array(hex.length / 2);
    for (let i = 0; i < bytes.length; i++) bytes[i] = Number.parseInt(hex.slice(i * 2, i * 2 + 2), 16);
    return bytes;
}

export function concatBytes(...parts: Uint8Array[]): Uint8Array {
    const out = new Uint8Array(parts.reduce((sum, part) => sum + part.length, 0));
    let offset = 0;
    for (const part of parts) {
        out.set(part, offset);
        offset += part.length;
    }
    return out;
}

export async function sha256(bytes: Uint8Array): Promise<Uint8Array> {
    return new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
}

/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import { AUTH_HEADERS, canonicalString } from "../../src/auth.ts";
import { base64UrlDecode, base64UrlEncode, concatBytes, sha256, toHex, utf8 } from "../../src/encoding.ts";

/**
 * A test phone: an EC P-256 key and a self-signed certificate shaped like the Android Keystore's, and the
 * signing side of ADR 0009, written independently of the Worker's verifier.
 *
 * Pure WebCrypto, so it runs both in workerd (tests) and in Node (vitest.config.ts).
 */

// --- Minimal DER encoder ------------------------------------------------------------------------------

function tlv(tag: number, content: Uint8Array): Uint8Array {
    const length = content.length;
    let header: number[];
    if (length < 0x80) header = [tag, length];
    else if (length < 0x100) header = [tag, 0x81, length];
    else header = [tag, 0x82, length >> 8, length & 0xff];
    return concatBytes(new Uint8Array(header), content);
}

export const der = {
    sequence: (...items: Uint8Array[]) => tlv(0x30, concatBytes(...items)),
    set: (...items: Uint8Array[]) => tlv(0x31, concatBytes(...items)),
    integer: (bytes: Uint8Array) => {
        let start = 0;
        while (start < bytes.length - 1 && bytes[start] === 0 && (bytes[start + 1]! & 0x80) === 0) start++;
        const trimmed = bytes.subarray(start);
        return tlv(0x02, trimmed[0]! & 0x80 ? concatBytes(new Uint8Array([0]), trimmed) : trimmed);
    },
    oid: (bytes: number[]) => tlv(0x06, new Uint8Array(bytes)),
    utf8String: (text: string) => tlv(0x0c, utf8(text)),
    utcTime: (text: string) => tlv(0x17, utf8(text)),
    bitString: (bytes: Uint8Array) => tlv(0x03, concatBytes(new Uint8Array([0]), bytes)),
    explicit: (tag: number, content: Uint8Array) => tlv(0xa0 | tag, content),
    raw: tlv,
};

const OID_ECDSA_WITH_SHA256 = [0x2a, 0x86, 0x48, 0xce, 0x3d, 0x04, 0x03, 0x02];
const OID_COMMON_NAME = [0x55, 0x04, 0x03];

/** WebCrypto signs in IEEE P1363 (`r || s`); Java's SHA256withECDSA and the protocol use DER. */
export function p1363ToDer(signature: Uint8Array): Uint8Array {
    const half = signature.length / 2;
    return der.sequence(der.integer(signature.subarray(0, half)), der.integer(signature.subarray(half)));
}

async function selfSignedCertificate(keys: CryptoKeyPair, curveSize: 256 | 384 = 256): Promise<Uint8Array> {
    const spki = new Uint8Array((await crypto.subtle.exportKey("spki", keys.publicKey)) as ArrayBuffer);
    const algorithm = der.sequence(der.oid(OID_ECDSA_WITH_SHA256));
    const name = der.sequence(der.set(der.sequence(der.oid(OID_COMMON_NAME), der.utf8String("MediaSync device"))));
    const tbs = der.sequence(
        der.explicit(0, der.integer(new Uint8Array([2]))),
        der.integer(crypto.getRandomValues(new Uint8Array(8))),
        algorithm,
        name,
        der.sequence(der.utcTime("260101000000Z"), der.utcTime("491231235959Z")),
        name,
        spki,
    );
    const hash = curveSize === 256 ? "SHA-256" : "SHA-384";
    const signature = new Uint8Array(await crypto.subtle.sign({ name: "ECDSA", hash }, keys.privateKey, tbs));
    return der.sequence(tbs, algorithm, der.bitString(p1363ToDer(signature)));
}

// --- The test device ------------------------------------------------------------------------------------

export const BASE_URL = "https://rendezvous.test";

export interface SignOptions {
    timestamp?: number;
    nonce?: string;
    /** Sign a different method, host, path or body than the request carries (tamper tests). */
    sign?: { method?: string; url?: string; body?: Uint8Array };
}

export class TestDevice {
    /**
     * The client address Cloudflare would report for this device. Each test device gets its own, so the
     * per-address rate limit only trips in the test that means to trip it.
     */
    clientAddress = `10.${Array.from(crypto.getRandomValues(new Uint8Array(3))).join(".")}`;

    private constructor(
        readonly privateKey: CryptoKey,
        readonly certificate: Uint8Array,
        readonly id: string,
    ) {}

    static async create(curve: "P-256" | "P-384" = "P-256"): Promise<TestDevice> {
        const keys = (await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: curve }, true, [
            "sign",
            "verify",
        ])) as CryptoKeyPair;
        const certificate = await selfSignedCertificate(keys, curve === "P-256" ? 256 : 384);
        return new TestDevice(keys.privateKey, certificate, toHex(await sha256(certificate)));
    }

    /** Recreates a device from [exported] (see [export]). */
    static async restore(exported: string): Promise<TestDevice> {
        const [pkcs8, certificate] = exported.split(".").map(base64UrlDecode) as [Uint8Array, Uint8Array];
        const key = await crypto.subtle.importKey("pkcs8", pkcs8, { name: "ECDSA", namedCurve: "P-256" }, false, [
            "sign",
        ]);
        return new TestDevice(key, certificate, toHex(await sha256(certificate)));
    }

    async export(): Promise<string> {
        const pkcs8 = new Uint8Array((await crypto.subtle.exportKey("pkcs8", this.privateKey)) as ArrayBuffer);
        return `${base64UrlEncode(pkcs8)}.${base64UrlEncode(this.certificate)}`;
    }

    /** A DER-encoded SHA256withECDSA signature, as Android's keystore produces. */
    async sign(message: Uint8Array): Promise<Uint8Array> {
        const signature = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, this.privateKey, message);
        return p1363ToDer(new Uint8Array(signature));
    }

    /** The four authentication headers for a request (ADR 0009). */
    async authHeaders(method: string, url: string, body: Uint8Array, options: SignOptions = {}): Promise<Record<string, string>> {
        const timestamp = `${options.timestamp ?? Math.floor(Date.now() / 1000)}`;
        const nonce = options.nonce ?? base64UrlEncode(crypto.getRandomValues(new Uint8Array(16)));
        const signedUrl = new URL(options.sign?.url ?? url);
        const message = canonicalString({
            method: options.sign?.method ?? method,
            host: signedUrl.host,
            path: signedUrl.pathname,
            query: signedUrl.search.replace(/^\?/, ""),
            timestamp,
            nonce,
            bodySha256Hex: toHex(await sha256(options.sign?.body ?? body)),
        });
        return {
            [AUTH_HEADERS.certificate]: base64UrlEncode(this.certificate),
            [AUTH_HEADERS.timestamp]: timestamp,
            [AUTH_HEADERS.nonce]: nonce,
            [AUTH_HEADERS.signature]: base64UrlEncode(await this.sign(utf8(message))),
        };
    }

    /** A signed request to the Worker. [body] objects are sent as JSON. */
    async request(
        method: string,
        path: string,
        body?: unknown,
        options: SignOptions & { headers?: Record<string, string> } = {},
    ): Promise<Request> {
        const url = path.startsWith("http") ? path : `${BASE_URL}${path}`;
        const bytes =
            body === undefined ? new Uint8Array() : body instanceof Uint8Array ? body : utf8(JSON.stringify(body));
        const headers: Record<string, string> = {
            "cf-connecting-ip": this.clientAddress,
            ...(await this.authHeaders(method, url, bytes, options)),
            ...options.headers,
        };
        if (body !== undefined) headers["content-type"] = "application/json";
        return new Request(url, { method, headers, ...(body === undefined ? {} : { body: bytes }) });
    }
}

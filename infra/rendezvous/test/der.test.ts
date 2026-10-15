/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import { describe, expect, it } from "vitest";
import { DerError, ecdsaDerToP1363, subjectPublicKeyInfo } from "../src/der.ts";
import { base64UrlDecode, concatBytes } from "../src/encoding.ts";
import golden from "./fixtures/golden-request.json";
import { der, p1363ToDer, TestDevice } from "./support/device.ts";

const bytes = (...values: number[]) => new Uint8Array(values);

describe("subjectPublicKeyInfo", () => {
    it("returns the key WebCrypto exported for a generated certificate", async () => {
        const device = await TestDevice.create();
        const spki = subjectPublicKeyInfo(device.certificate);
        const key = await crypto.subtle.importKey("spki", spki, { name: "ECDSA", namedCurve: "P-256" }, true, ["verify"]);
        expect(new Uint8Array((await crypto.subtle.exportKey("spki", key)) as ArrayBuffer)).toEqual(spki);
    });

    it("reads an OpenSSL certificate with v3 extensions", async () => {
        const certificate = base64UrlDecode(golden.headers["X-MediaSync-Certificate"]);
        const spki = subjectPublicKeyInfo(certificate);
        await expect(
            crypto.subtle.importKey("spki", spki, { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]),
        ).resolves.toBeDefined();
    });

    it("reads a v1 certificate without the version field", () => {
        const spki = der.sequence(der.sequence(der.oid([0x2a])), der.bitString(bytes(4, 1, 2)));
        const name = der.sequence();
        const tbs = der.sequence(der.integer(bytes(1)), der.sequence(), name, der.sequence(), name, spki);
        expect(subjectPublicKeyInfo(der.sequence(tbs, der.sequence(), der.bitString(bytes(0))))).toEqual(spki);
    });

    it.each([
        ["empty input", bytes()],
        ["a non-sequence", bytes(0x02, 0x01, 0x00)],
        ["trailing bytes", concatBytes(der.sequence(der.sequence()), bytes(0))],
        ["a truncated length", bytes(0x30, 0x82, 0x01)],
        ["an overrunning length", bytes(0x30, 0x05, 0x00)],
        ["an indefinite length", bytes(0x30, 0x80, 0x00, 0x00)],
        ["a non-minimal length", bytes(0x30, 0x81, 0x01, 0x00)],
        ["a high tag number", bytes(0x1f, 0x01, 0x00)],
        ["too few fields", der.sequence(der.sequence(der.integer(bytes(1))))],
        ["a missing serial number", der.sequence(der.sequence(der.sequence(), der.sequence(), der.sequence(), der.sequence(), der.sequence(), der.sequence()))],
    ])("rejects %s", (_, input) => {
        expect(() => subjectPublicKeyInfo(input)).toThrow(DerError);
    });
});

describe("ecdsaDerToP1363", () => {
    it("round-trips WebCrypto signatures", async () => {
        for (let i = 0; i < 20; i++) {
            const raw = crypto.getRandomValues(new Uint8Array(64));
            expect(ecdsaDerToP1363(p1363ToDer(raw))).toEqual(raw);
        }
    });

    it("left-pads short integers and strips sign bytes", () => {
        const r = new Uint8Array(32).fill(0xff);
        const s = bytes(5);
        const signature = der.sequence(der.raw(0x02, concatBytes(bytes(0), r)), der.raw(0x02, s));
        const raw = ecdsaDerToP1363(signature);
        expect(raw.subarray(0, 32)).toEqual(r);
        expect(raw.subarray(32)).toEqual(concatBytes(new Uint8Array(31), s));
    });

    it.each([
        ["a negative integer", der.sequence(der.raw(0x02, bytes(0x80)), der.raw(0x02, bytes(1)))],
        ["an empty integer", der.sequence(der.raw(0x02, bytes()), der.raw(0x02, bytes(1)))],
        ["an oversized integer", der.sequence(der.raw(0x02, bytes(1, ...new Uint8Array(32))), der.raw(0x02, bytes(1)))],
        ["three integers", der.sequence(der.integer(bytes(1)), der.integer(bytes(1)), der.integer(bytes(1)))],
        ["a non-integer", der.sequence(der.oid([1]), der.integer(bytes(1)))],
        ["trailing bytes", concatBytes(der.sequence(der.integer(bytes(1)), der.integer(bytes(1))), bytes(0))],
    ])("rejects %s", (_, input) => {
        expect(() => ecdsaDerToP1363(input)).toThrow(DerError);
    });
});

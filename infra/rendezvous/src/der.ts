/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Just enough DER (ITU-T X.690) to read what a phone sends:
 * - the public key inside its self-signed X.509 certificate, and
 * - its ECDSA signatures, which Java encodes as an ASN.1 `SEQUENCE { r INTEGER, s INTEGER }`
 *   while WebCrypto wants the fixed-size `r || s` form (IEEE P1363).
 *
 * The parser is strict on purpose: definite lengths only, no trailing bytes, no length overruns.
 */

const TAG_INTEGER = 0x02;
const TAG_SEQUENCE = 0x30;
const TAG_CONTEXT_0 = 0xa0;

interface Element {
    tag: number;
    /** Offset of the tag byte. */
    start: number;
    /** Offset of the first content byte. */
    contentStart: number;
    /** Offset just past the content. */
    end: number;
}

export class DerError extends Error {}

function readElement(der: Uint8Array, offset: number, limit: number): Element {
    if (offset + 2 > limit) throw new DerError("Truncated element");
    const tag = der[offset]!;
    // Multi-byte tags never occur in certificates' outer structure or in signatures.
    if ((tag & 0x1f) === 0x1f) throw new DerError("High tag numbers are not supported");
    const first = der[offset + 1]!;
    let length: number;
    let contentStart: number;
    if (first < 0x80) {
        length = first;
        contentStart = offset + 2;
    } else {
        const lengthBytes = first & 0x7f;
        // 0x80 is BER's indefinite length, which DER forbids; 3 length bytes allow 16 MiB, plenty here.
        if (lengthBytes === 0 || lengthBytes > 3) throw new DerError("Unsupported length encoding");
        if (offset + 2 + lengthBytes > limit) throw new DerError("Truncated length");
        length = 0;
        for (let i = 0; i < lengthBytes; i++) length = length * 256 + der[offset + 2 + i]!;
        if (length < 0x80) throw new DerError("Non-minimal length");
        contentStart = offset + 2 + lengthBytes;
    }
    const end = contentStart + length;
    if (end > limit) throw new DerError("Element longer than its container");
    return { tag, start: offset, contentStart, end };
}

function children(der: Uint8Array, parent: Element): Element[] {
    const result: Element[] = [];
    let offset = parent.contentStart;
    while (offset < parent.end) {
        const child = readElement(der, offset, parent.end);
        result.push(child);
        offset = child.end;
    }
    return result;
}

function expect(element: Element | undefined, tag: number, what: string): Element {
    if (element === undefined || element.tag !== tag) throw new DerError(`Expected ${what}`);
    return element;
}

/**
 * Returns the DER-encoded SubjectPublicKeyInfo of an X.509 certificate (RFC 5280 §4.1), ready for
 * `crypto.subtle.importKey("spki", …)`.
 *
 * The certificate's own signature isn't checked: a MediaSync device is identified by the SHA-256 of its
 * certificate, and proves it holds the matching private key by signing each request (ADR 0009).
 */
export function subjectPublicKeyInfo(certificate: Uint8Array): Uint8Array {
    const cert = expect(readElement(certificate, 0, certificate.length), TAG_SEQUENCE, "Certificate");
    if (cert.end !== certificate.length) throw new DerError("Trailing bytes after the certificate");
    const tbs = expect(children(certificate, cert)[0], TAG_SEQUENCE, "TBSCertificate");
    const fields = children(certificate, tbs);
    // TBSCertificate: [0] version (optional), serialNumber, signature, issuer, validity, subject, SPKI, …
    const index = fields[0]?.tag === TAG_CONTEXT_0 ? 6 : 5;
    expect(fields[index - 5], TAG_INTEGER, "serialNumber");
    const spki = expect(fields[index], TAG_SEQUENCE, "SubjectPublicKeyInfo");
    return certificate.slice(spki.start, spki.end);
}

/** The unsigned big-endian integer in `bytes`, left-padded to `size` bytes. */
function fixedWidth(der: Uint8Array, element: Element, size: number): Uint8Array {
    let start = element.contentStart;
    if (element.end === start) throw new DerError("Empty INTEGER");
    if (der[start]! & 0x80) throw new DerError("Negative INTEGER");
    // Strip the sign byte (and any other leading zeros) before checking the width.
    while (start < element.end - 1 && der[start] === 0) start++;
    const length = element.end - start;
    if (length > size) throw new DerError("INTEGER too large");
    const out = new Uint8Array(size);
    out.set(der.subarray(start, element.end), size - length);
    return out;
}

/** Converts an ASN.1 DER ECDSA signature to the IEEE P1363 `r || s` form for a curve of `size`-byte order. */
export function ecdsaDerToP1363(signature: Uint8Array, size = 32): Uint8Array {
    const sequence = expect(readElement(signature, 0, signature.length), TAG_SEQUENCE, "ECDSA signature");
    if (sequence.end !== signature.length) throw new DerError("Trailing bytes after the signature");
    const parts = children(signature, sequence);
    if (parts.length !== 2) throw new DerError("Expected exactly r and s");
    const r = fixedWidth(signature, expect(parts[0], TAG_INTEGER, "r"), size);
    const s = fixedWidth(signature, expect(parts[1], TAG_INTEGER, "s"), size);
    const out = new Uint8Array(size * 2);
    out.set(r, 0);
    out.set(s, size);
    return out;
}

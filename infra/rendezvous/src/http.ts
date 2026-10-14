/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * HTTP plumbing: the JSON error format every endpoint uses, body limits and small input validators.
 *
 * Errors look like `{"error":{"code":"snake_case","message":"…"}}`. The app branches on `code`;
 * `message` is for humans and logs.
 */

export class ApiError extends Error {
    constructor(
        readonly status: number,
        readonly code: string,
        message: string,
        readonly headers: Record<string, string> = {},
    ) {
        super(message);
    }
}

/** What Durable Object RPC methods return instead of throwing: thrown errors lose their fields over RPC. */
export type Result<T> =
    | { ok: true; value: T }
    | { ok: false; status: number; code: string; message: string; headers?: Record<string, string> };

export function ok<T>(value: T): Result<T> {
    return { ok: true, value };
}

export function fail<T = never>(
    status: number,
    code: string,
    message: string,
    headers?: Record<string, string>,
): Result<T> {
    return headers === undefined ? { ok: false, status, code, message } : { ok: false, status, code, message, headers };
}

/** Unwraps a [Result], turning a failure back into an [ApiError] on the Worker side. */
export function unwrap<T>(result: Result<T>): T {
    if (!result.ok) throw new ApiError(result.status, result.code, result.message, result.headers);
    return result.value;
}

export function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
    return new Response(JSON.stringify(body), {
        status,
        headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...headers },
    });
}

export function errorResponse(error: ApiError): Response {
    return json({ error: { code: error.code, message: error.message } }, error.status, error.headers);
}

/** Every request body is small JSON; anything bigger is refused before it's hashed or parsed. */
export const MAX_BODY_BYTES = 16 * 1024;

export async function readBody(request: Request, maxBytes = MAX_BODY_BYTES): Promise<Uint8Array> {
    const declared = request.headers.get("content-length");
    if (declared !== null && Number(declared) > maxBytes) {
        throw new ApiError(413, "payload_too_large", `Request bodies are limited to ${maxBytes} bytes`);
    }
    const body = new Uint8Array(await request.arrayBuffer());
    if (body.length > maxBytes) {
        throw new ApiError(413, "payload_too_large", `Request bodies are limited to ${maxBytes} bytes`);
    }
    return body;
}

/** Parses a JSON object body; an empty body counts as `{}`. */
export function parseJsonObject(body: Uint8Array): Record<string, unknown> {
    if (body.length === 0) return {};
    let value: unknown;
    try {
        value = JSON.parse(new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(body));
    } catch {
        throw new ApiError(400, "bad_request", "The body isn't valid UTF-8 JSON");
    }
    if (typeof value !== "object" || value === null || Array.isArray(value)) {
        throw new ApiError(400, "bad_request", "The body must be a JSON object");
    }
    return value as Record<string, unknown>;
}

export function badRequest(message: string): ApiError {
    return new ApiError(400, "bad_request", message);
}

/** A string field, or `undefined` when absent; throws for any other type or an over-long value. */
export function optionalString(body: Record<string, unknown>, field: string, maxLength: number): string | undefined {
    const value = body[field];
    if (value === undefined || value === null) return undefined;
    if (typeof value !== "string" || value.length > maxLength) {
        throw badRequest(`"${field}" must be a string of at most ${maxLength} characters`);
    }
    return value;
}

export function requiredString(body: Record<string, unknown>, field: string, maxLength: number): string {
    const value = optionalString(body, field, maxLength);
    if (value === undefined) throw badRequest(`"${field}" is required`);
    return value;
}

/** A non-negative safe integer field (byte counters reach terabytes, well within 2^53). */
export function requiredCount(body: Record<string, unknown>, field: string): number {
    const value = body[field];
    if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0) {
        throw badRequest(`"${field}" must be a non-negative integer`);
    }
    return value;
}

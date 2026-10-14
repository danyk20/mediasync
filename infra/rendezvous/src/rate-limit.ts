/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import { ApiError } from "./http.ts";

/**
 * Rate limiting with the Workers Rate Limiting API (PLAN §8.4). Limits are configured per binding in
 * wrangler.toml and counted per Cloudflare location, which is fine for abuse protection.
 *
 * The pre-authentication limit is keyed by the client address Cloudflare reports. The address is only
 * used as a counter key inside Cloudflare's limiter; the Worker never stores or logs it.
 */
export async function enforceLimit(limiter: RateLimit, key: string): Promise<void> {
    const { success } = await limiter.limit({ key });
    if (!success) {
        throw new ApiError(429, "rate_limited", "Too many requests; try again in a minute", { "retry-after": "60" });
    }
}

/** The pre-authentication key: the client address, or one shared bucket if Cloudflare didn't provide it. */
export function clientKey(request: Request): string {
    return `ip:${request.headers.get("cf-connecting-ip") ?? "unknown"}`;
}

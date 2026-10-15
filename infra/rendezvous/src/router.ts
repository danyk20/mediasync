/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import { authenticate } from "./auth.ts";
import type { RequestContext } from "./context.ts";
import { deletePair, getPair, openSignaling, registerPair } from "./handlers/pairs.ts";
import { getRelayUsage, iceServers, reportRelayUsage, updateRelaySettings } from "./handlers/relay.ts";
import { wake } from "./handlers/wake.ts";
import { ApiError, errorResponse, json, readBody } from "./http.ts";
import { isPairId } from "./identity.ts";
import { clientKey, enforceLimit } from "./rate-limit.ts";

/**
 * The HTTP API (ADR 0009 lists it with request and response formats).
 *
 * Every route except `/v1/health` runs the same pipeline: rate limit by client address, read the body,
 * verify the device's signature, rate limit by device, then the handler. Handlers that touch a pair
 * check membership and the nonce in the pair's Durable Object.
 */

type Handler = (context: RequestContext) => Promise<Response>;

interface Route {
    pattern: RegExp;
    methods: Record<string, Handler>;
}

const PAIR = "(?<pairId>[0-9a-f]{64})";

const ROUTES: Route[] = [
    { pattern: new RegExp(`^/v1/pairs/${PAIR}$`), methods: { PUT: registerPair, GET: getPair, DELETE: deletePair } },
    { pattern: new RegExp(`^/v1/pairs/${PAIR}/signal$`), methods: { GET: openSignaling } },
    { pattern: new RegExp(`^/v1/pairs/${PAIR}/ice-servers$`), methods: { POST: iceServers } },
    { pattern: new RegExp(`^/v1/pairs/${PAIR}/wake$`), methods: { POST: wake } },
    { pattern: new RegExp(`^/v1/pairs/${PAIR}/relay-usage$`), methods: { GET: getRelayUsage, POST: reportRelayUsage } },
    { pattern: /^\/v1\/relay\/settings$/, methods: { PUT: updateRelaySettings } },
];

function match(url: URL, method: string): { handler: Handler; pairId: string } {
    for (const route of ROUTES) {
        const found = route.pattern.exec(url.pathname);
        if (!found) continue;
        const handler = route.methods[method];
        if (!handler) {
            throw new ApiError(405, "method_not_allowed", `${method} isn't supported here`, {
                allow: Object.keys(route.methods).join(", "),
            });
        }
        const pairId = found.groups?.pairId ?? "";
        if (pairId !== "" && !isPairId(pairId)) throw new ApiError(404, "not_found", "No such endpoint");
        return { handler, pairId };
    }
    throw new ApiError(404, "not_found", "No such endpoint");
}

export async function handleRequest(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    try {
        const url = new URL(request.url);
        if (url.pathname === "/v1/health") {
            if (request.method !== "GET") throw new ApiError(405, "method_not_allowed", "Use GET", { allow: "GET" });
            return json({ status: "ok" });
        }
        const { handler, pairId } = match(url, request.method);
        await enforceLimit(env.IP_LIMITER, clientKey(request));
        const body = await readBody(request);
        const now = Date.now();
        const device = await authenticate(request, body, now);
        await enforceLimit(env.DEVICE_LIMITER, `device:${device.id}`);
        return await handler({ request, env, ctx, url, body, device, pairId, now });
    } catch (error) {
        if (error instanceof ApiError) return errorResponse(error);
        // Only the error's message: never request data, addresses or headers (PLAN §8.4).
        console.error(`Unhandled error: ${error instanceof Error ? error.message : "unknown"}`);
        return errorResponse(new ApiError(500, "internal", "Something went wrong"));
    }
}

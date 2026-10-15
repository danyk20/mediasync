/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import type { AuthenticatedDevice } from "./auth.ts";
import { METER_NAME } from "./meter/usage-meter.ts";
import type { Caller } from "./pair-mailbox.ts";

/** Everything a handler gets once the request passed rate limiting and authentication. */
export interface RequestContext {
    request: Request;
    env: Env;
    ctx: ExecutionContext;
    url: URL;
    /** The exact request body, already read for the signature check. */
    body: Uint8Array;
    device: AuthenticatedDevice;
    /** The `{pairId}` path parameter, for routes under `/v1/pairs/`. */
    pairId: string;
    now: number;
}

export function caller(context: RequestContext): Caller {
    return { device: context.device.id, nonce: context.device.nonce };
}

export function pairStub(env: Env, pairId: string): DurableObjectStub<import("./pair-mailbox.ts").PairMailbox> {
    return env.PAIRS.get(env.PAIRS.idFromName(pairId));
}

export function meterStub(env: Env): DurableObjectStub<import("./meter/usage-meter.ts").UsageMeter> {
    return env.METER.get(env.METER.idFromName(METER_NAME));
}

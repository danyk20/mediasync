/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import { meterStub } from "./context.ts";
import { handleRequest } from "./router.ts";

/**
 * MediaSync rendezvous Worker (PLAN §1.2, §8.4, §8.5, §10.4; ADR 0003, ADR 0009).
 *
 * The phonebook and doorbell for internet sync: it relays encrypted WebRTC signaling between paired
 * phones, mints short-lived TURN credentials while the relay budget allows, wakes the consumer through
 * FCM, and meters relay usage. It never sees media, and can't read the signaling it relays.
 */

export { UsageMeter } from "./meter/usage-meter.ts";
export { PairMailbox } from "./pair-mailbox.ts";

export default {
    fetch(request, env, ctx): Promise<Response> {
        return handleRequest(request, env, ctx);
    },

    /** Every 15 minutes: read the authoritative relay usage from Cloudflare's analytics (PLAN §8.5). */
    async scheduled(controller, env, ctx): Promise<void> {
        ctx.waitUntil(meterStub(env).refresh(controller.scheduledTime));
    },
} satisfies ExportedHandler<Env>;

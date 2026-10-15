/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */

// Bindings that `wrangler types` can't see, added to the generated `Env` (worker-configuration.d.ts).

interface Env {
    /**
     * Optional secret: comma-separated device IDs allowed to change the relay settings
     * (`PUT /v1/relay/settings`). Unset means the settings come from the vars only.
     */
    RELAY_ADMIN_DEVICE_IDS?: string;
}

declare namespace Cloudflare {
    interface Env {
        RELAY_ADMIN_DEVICE_IDS?: string;
    }
}

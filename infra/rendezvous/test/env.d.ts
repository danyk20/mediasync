/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */

// Test-only bindings set in vitest.config.ts.
declare namespace Cloudflare {
    interface Env {
        /** PEM public key matching the generated FCM service account. */
        TEST_FCM_PUBLIC_KEY: string;
        /** The generated admin device (`TestDevice.export()`), whose ID is in RELAY_ADMIN_DEVICE_IDS. */
        TEST_ADMIN_DEVICE: string;
    }
}

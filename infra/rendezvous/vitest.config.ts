/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import { generateKeyPairSync } from "node:crypto";
import { cloudflareTest } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";
import { TestDevice } from "./test/support/device.ts";

/**
 * Tests run inside the Workers runtime (workerd) with the bindings from wrangler.toml. The secrets get
 * test values here; nothing in the tests reaches the network (outbound fetches are mocked per test).
 *
 * The FCM service account's RSA key is generated per run, so no private key is ever committed; the tests
 * get the public half to verify the JWTs the Worker signs. Likewise, the admin device allowed to change the
 * relay settings is generated here and handed to the tests.
 */
const fcmKey = generateKeyPairSync("rsa", {
    modulusLength: 2048,
    privateKeyEncoding: { type: "pkcs8", format: "pem" },
    publicKeyEncoding: { type: "spki", format: "pem" },
});

export default defineConfig({
    plugins: [
        cloudflareTest(async () => {
            const admin = await TestDevice.create();
            return {
                wrangler: { configPath: "./wrangler.toml" },
                miniflare: {
                    bindings: {
                        CF_ACCOUNT_ID: "test-account-id",
                        CF_ANALYTICS_TOKEN: "test-analytics-token",
                        TURN_KEY_ID: "test-turn-key-id",
                        TURN_KEY_API_TOKEN: "test-turn-api-token",
                        FCM_SERVICE_ACCOUNT: JSON.stringify({
                            type: "service_account",
                            project_id: "mediasync-test",
                            private_key_id: "test-key-id",
                            private_key: fcmKey.privateKey,
                            client_email: "fcm@mediasync-test.iam.gserviceaccount.com",
                            token_uri: "https://oauth2.googleapis.com/token",
                        }),
                        RELAY_ADMIN_DEVICE_IDS: admin.id,
                        TEST_FCM_PUBLIC_KEY: fcmKey.publicKey,
                        TEST_ADMIN_DEVICE: await admin.export(),
                    },
                },
            };
        }),
    ],
    test: {
        include: ["test/**/*.test.ts"],
    },
});

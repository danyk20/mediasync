/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

/**
 * Engine API that exists for the test support of this engine (`sync:engine-testing`), not for production code. Using
 * it needs an explicit opt-in.
 */
@RequiresOptIn(
    message = "Internal to sync:engine and its test support: production code must not use it.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
annotation class EngineInternalApi

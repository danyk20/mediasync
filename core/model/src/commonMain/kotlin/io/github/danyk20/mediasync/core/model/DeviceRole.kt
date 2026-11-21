/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.model

/** What this install does. Chosen on first launch; one role per device. */
enum class DeviceRole {
    /** Sends its photos and videos to a consumer. */
    PRODUCER,

    /** Receives photos and videos from producers and hands them to a connector app such as Google Photos. */
    CONSUMER,
}

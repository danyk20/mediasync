/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.lan.apple

import io.github.danyk20.mediasync.core.model.DeviceId

/** The JVM InteropServer's port and pin, as Gradle wrote them to `<INTEROP_DIR>/server.properties`. */
internal class InteropServerInfo(val port: Int, val pin: DeviceId)

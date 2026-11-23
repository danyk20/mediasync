/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.lan.apple

import io.github.danyk20.mediasync.core.model.DeviceId

/**
 * The JVM InteropServer's ports and pin, as Gradle wrote them to `<INTEROP_DIR>/server.properties`: [port] echoes
 * bulk data, [silentPort] never reads or writes, [dropPort] writes a fixed amount and closes.
 */
internal class InteropServerInfo(val port: Int, val silentPort: Int, val dropPort: Int, val pin: DeviceId)

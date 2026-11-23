/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import java.net.InetAddress
import javax.net.SocketFactory

/**
 * A direct link this phone joined: the address of the phone that opened it, and the [sockets] that reach it there (a
 * Wi-Fi Aware link is a network of its own).
 */
@Suppress("FunctionName") // A factory that stands in for the constructor, so callers passing an address keep compiling.
fun JoinedLink(host: InetAddress, sockets: SocketFactory = SocketFactory.getDefault()): JoinedLink =
    JoinedLink(host.hostAddress ?: error("Unresolved address"), SocketRoute(host, sockets))

/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.lan.apple

/** Thrown when Security or Network.framework refuses an operation. The message carries the framework's code. */
class AppleLanException(message: String) : Exception(message)

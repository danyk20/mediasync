/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.util

import kotlinx.coroutines.CoroutineDispatcher

/**
 * The dispatcher for blocking I/O. `Dispatchers.IO` is not visible from commonMain with this module's targets (checked
 * in the 7b spike), so each platform names it.
 */
expect val ioDispatcher: CoroutineDispatcher

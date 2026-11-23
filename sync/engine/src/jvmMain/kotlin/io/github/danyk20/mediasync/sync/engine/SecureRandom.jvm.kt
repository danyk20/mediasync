/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom

actual fun secureRandom(): Random = SecureRandom().asKotlinRandom()

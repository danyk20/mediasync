/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import kotlin.random.Random

/** A cryptographically secure random source, for keys, passphrases and names that must not be guessable. */
expect fun secureRandom(): Random

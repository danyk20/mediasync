/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.metadata

import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/** The value of the environment variable [name], or `null` when it is not set. */
internal expect fun env(name: String): String?

/** A golden file (core/metadata/golden): the folder is `GOLDEN_DIR`, set by build.gradle.kts for JVM and iOS tests. */
internal fun goldenBytes(name: String): ByteArray {
    val dir = env("GOLDEN_DIR") ?: error("GOLDEN_DIR is not set")
    return FileSystem.SYSTEM.source("$dir/$name".toPath()).buffer().use { it.readByteArray() }
}

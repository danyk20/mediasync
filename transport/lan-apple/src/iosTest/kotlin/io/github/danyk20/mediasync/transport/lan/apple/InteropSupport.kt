/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
@file:OptIn(ExperimentalForeignApi::class)

package io.github.danyk20.mediasync.transport.lan.apple

import io.github.danyk20.mediasync.core.model.DeviceId
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import platform.posix.getenv

/**
 * The directory Gradle gives the test (`SIMCTL_CHILD_INTEROP_DIR` reaches the test as `INTEROP_DIR`), or `null` when
 * the test was started without Gradle. Gradle always sets it for `iosSimulatorArm64Test`; CI checks the results file.
 */
internal fun interopDirectory(): String? = getenv("INTEROP_DIR")?.toKString()

internal fun readInteropServer(dir: String): InteropServerInfo {
    val text = FileSystem.SYSTEM.read("$dir/server.properties".toPath()) { readUtf8() }
    val properties = text.lines()
        .filter { '=' in it }
        .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
    return InteropServerInfo(
        port = properties.getValue("port").toInt(),
        silentPort = properties.getValue("silentPort").toInt(),
        dropPort = properties.getValue("dropPort").toInt(),
        pin = DeviceId(properties.getValue("pin")),
    )
}

/**
 * Appends one line per passed test to `<INTEROP_DIR>/interop-result.txt`. CI reads the file: a test that skipped
 * for lack of the directory leaves no line, so CI fails instead of passing silently.
 */
internal fun recordPass(test: String) {
    val dir = interopDirectory() ?: return
    FileSystem.SYSTEM.appendingSink("$dir/interop-result.txt".toPath()).buffer().use { sink ->
        sink.writeUtf8("PASS $test\n")
    }
}

/** Appends one line of text (not a PASS line) to the same result file: CI prints it, e.g. a throughput figure. */
internal fun recordLine(text: String) {
    println(text)
    val dir = interopDirectory() ?: return
    FileSystem.SYSTEM.appendingSink("$dir/interop-result.txt".toPath()).buffer().use { sink ->
        sink.writeUtf8("$text\n")
    }
}

/** Printed when the directory is absent; the test then returns without asserting. */
internal const val SKIP_REASON = "SKIPPED: INTEROP_DIR is not set; run this test through Gradle " +
    "(:transport:lan-apple:iosSimulatorArm64Test starts the JVM InteropServer)"

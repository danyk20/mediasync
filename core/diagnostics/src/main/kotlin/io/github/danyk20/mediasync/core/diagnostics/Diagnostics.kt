/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.diagnostics

/** What part of MediaSync an entry is about. */
enum class Area(val label: String) {
    APP("app"),
    SCHEDULE("schedule"),
    WORK("work"),
    SEND("send"),
    RECEIVE("receive"),
}

/** Where diagnostic entries go. */
fun interface DiagnosticLog {
    fun log(area: Area, message: String)
}

/**
 * The diagnostic log (docs/PLAN.md §11): what MediaSync did and why, for the user to share when something doesn't
 * work. It stays on the phone until the user exports it.
 *
 * Entries say what happened in counts, states and error types. They never name files, places or people, and
 * [Sanitizer] removes paths and the last part of addresses from anything that slips in, such as error messages.
 */
object Diagnostics {
    @Volatile
    private var sink: DiagnosticLog = DiagnosticLog { _, _ -> }

    /** Where entries go from now on; call once at start-up. */
    fun install(log: DiagnosticLog) {
        sink = log
    }

    fun log(area: Area, message: String) = sink.log(area, Sanitizer.clean(message))

    /** [message], followed by what [error] says. */
    fun log(area: Area, message: String, error: Throwable) = log(area, "$message: ${describe(error)}")

    /** The error's type and message, and its cause's: "SocketTimeoutException: Read timed out". */
    fun describe(error: Throwable): String = generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }
        .take(MAX_CAUSES)
        .joinToString(" ← ") { listOfNotNull(it.javaClass.simpleName, it.message).joinToString(": ") }

    private const val MAX_CAUSES = 3
}

/** Removes what could identify files or the network from a message. */
object Sanitizer {
    private val replacements = listOf(
        Regex("""(content|file)://\S+""") to "<$1 uri>",
        Regex("""(?<![\w.])/(storage|sdcard|data|mnt|system|proc)/\S*""") to "<path>",
        Regex("""\b(\d{1,3}\.\d{1,3}\.\d{1,3})\.\d{1,3}\b""") to "$1.x",
        Regex("""\b(?:[0-9a-fA-F]{1,4}:){3,7}[0-9a-fA-F]{1,4}\b""") to "<ipv6>",
        Regex("""(?<![\w:])[0-9a-fA-F]{0,4}(?::[0-9a-fA-F]{1,4})*::(?:[0-9a-fA-F]{1,4}:?)*(?:%\w+)?""") to "<ipv6>",
    )

    fun clean(message: String): String = replacements.fold(message) { text, (pattern, replacement) ->
        pattern.replace(text, replacement)
    }
}

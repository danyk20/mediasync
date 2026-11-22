/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector.folder

private const val MAX_NAME_LENGTH = 150
private const val MAX_EXTENSION_LENGTH = 16
private const val FALLBACK_NAME = "file"
private const val REPLACEMENT = '_'
private const val FIRST_PRINTABLE = ' '
private const val FORBIDDEN = "<>:\"/\\|?*"
private val WINDOWS_RESERVED = setOf("CON", "PRN", "AUX", "NUL") +
    (1..9).flatMap { listOf("COM$it", "LPT$it") }

/**
 * A file name that is safe on macOS, Windows and Linux (and a NAS): a sender's display name is untrusted. Keeps only
 * the last path part, replaces control characters and `<>:"/\|?*`, drops trailing dots and spaces, avoids Windows'
 * reserved names (`CON`, `NUL`, ...) and keeps the name and its extension within a limit.
 */
internal fun safeFileName(displayName: String): String {
    val last = displayName.substringAfterLast('/').substringAfterLast('\\')
    val cleaned = last.map { if (it < FIRST_PRINTABLE || it in FORBIDDEN) REPLACEMENT else it }
        .joinToString("")
        .trim()
        .trimEnd('.', ' ')
    val name = cleaned.ifEmpty { FALLBACK_NAME }
    val limited = if (name.length <= MAX_NAME_LENGTH) name else shorten(name)
    return if (limited.substringBefore('.').uppercase() in WINDOWS_RESERVED) "$REPLACEMENT$limited" else limited
}

private fun shorten(name: String): String {
    val dot = name.lastIndexOf('.')
    val extension = if (dot > 0 && name.length - dot <= MAX_EXTENSION_LENGTH) name.substring(dot) else ""
    return name.take(MAX_NAME_LENGTH - extension.length) + extension
}

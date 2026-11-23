/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.metadata

/**
 * Seconds east of UTC for the texts `ZoneOffset.of` accepts: `Z`, `+h`, `+hh`, `+hh:mm`, `+hhmm`, `+hh:mm:ss`,
 * `+hhmmss` (and `-`), up to 18:00; `null` for anything else.
 */
internal fun parseUtcOffsetSeconds(text: String): Int? {
    val id = when {
        text == "Z" -> "+00"
        text.length == 2 -> "${text[0]}0${text[1]}"
        else -> text
    }
    val sign = SIGNS[id.firstOrNull()] ?: return null
    val (hours, minutes, seconds) = offsetFields(id) ?: return null
    return (sign * (hours * SECONDS_PER_HOUR + minutes * SECONDS_PER_MINUTE + seconds))
        .takeIf { withinLimits(hours, minutes, seconds) }
}

/** Hours, minutes and seconds of [id] (sign removed), or `null` for another shape of text. */
private fun offsetFields(id: String): List<Int>? {
    val digits = FIELDS[id.length] ?: return null
    return digits.map { (at, colonBefore) -> twoDigits(id, at, colonBefore) ?: return null } + listOf(0, 0)
}

/** `ZoneOffset`'s limits: hours up to 18, minutes and seconds up to 59, 18 hours in all. */
private fun withinLimits(hours: Int, minutes: Int, seconds: Int): Boolean {
    val total = hours * SECONDS_PER_HOUR + minutes * SECONDS_PER_MINUTE + seconds
    return hours <= MAX_HOURS && total <= MAX_HOURS * SECONDS_PER_HOUR &&
        listOf(minutes, seconds).all { it <= MAX_SIXTY }
}

private fun twoDigits(text: String, at: Int, colonBefore: Boolean): Int? {
    if (colonBefore && text[at - 1] != ':') return null
    val (a, b) = text[at] to text[at + 1]
    if (a !in '0'..'9' || b !in '0'..'9') return null
    return (a - '0') * DECIMAL + (b - '0')
}

/** Where each field's two digits start in the text, by its length; `true` if a colon comes before them. */
private val FIELDS: Map<Int, List<Pair<Int, Boolean>>> = mapOf(
    3 to listOf(1 to false),
    5 to listOf(1 to false, 3 to false),
    6 to listOf(1 to false, 4 to true),
    7 to listOf(1 to false, 3 to false, 5 to false),
    9 to listOf(1 to false, 4 to true, 7 to true),
)
private val SIGNS = mapOf('+' to 1, '-' to -1)
private const val SECONDS_PER_HOUR = 3600
private const val SECONDS_PER_MINUTE = 60
private const val MAX_HOURS = 18
private const val MAX_SIXTY = 59
private const val DECIMAL = 10

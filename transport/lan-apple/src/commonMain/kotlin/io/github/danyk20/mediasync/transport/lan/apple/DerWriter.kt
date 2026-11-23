/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.lan.apple

/**
 * The part of DER (ITU-T X.690) that a self-signed X.509 certificate needs: definite lengths and a few universal
 * types. Written by hand because iOS can parse a certificate but has no API to create one.
 */
internal object DerWriter {
    private const val TAG_INTEGER = 0x02
    private const val TAG_BIT_STRING = 0x03
    private const val TAG_OID = 0x06
    private const val TAG_UTF8_STRING = 0x0C
    private const val TAG_UTC_TIME = 0x17
    private const val TAG_GENERALIZED_TIME = 0x18
    private const val TAG_SEQUENCE = 0x30
    private const val TAG_SET = 0x31
    private const val TAG_CONTEXT_0 = 0xA0

    private const val SHORT_FORM_MAX = 0x7F
    private const val LONG_FORM_FLAG = 0x80
    private const val BYTE_MASK = 0xFF
    private const val BITS_PER_BYTE = 8

    /** UTCTime is used for years before this; RFC 5280 section 4.1.2.5 requires GeneralizedTime from 2050 on. */
    private const val GENERALIZED_TIME_FROM_YEAR = 2050
    private const val EARLIEST_UTC_TIME_YEAR = 1950

    private const val MILLIS_PER_SECOND = 1_000L
    private const val SECONDS_PER_DAY = 86_400L
    private const val SECONDS_PER_HOUR = 3_600L
    private const val SECONDS_PER_MINUTE = 60L

    fun tlv(tag: Int, content: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + length(content.size) + content

    fun sequence(vararg parts: ByteArray): ByteArray = tlv(TAG_SEQUENCE, concat(parts.toList()))

    fun set(vararg parts: ByteArray): ByteArray = tlv(TAG_SET, concat(parts.toList()))

    /** The `[0] EXPLICIT` tag used for the certificate version. */
    fun explicitContext0(content: ByteArray): ByteArray = tlv(TAG_CONTEXT_0, content)

    /** [content] is the two's-complement value, already minimal. */
    fun integer(content: ByteArray): ByteArray = tlv(TAG_INTEGER, content)

    /** [encoded] is the OID's content octets (not the dotted name). */
    fun oid(encoded: ByteArray): ByteArray = tlv(TAG_OID, encoded)

    fun utf8String(text: String): ByteArray = tlv(TAG_UTF8_STRING, text.encodeToByteArray())

    /** A bit string whose length is a whole number of bytes (no unused bits). */
    fun bitString(content: ByteArray): ByteArray = tlv(TAG_BIT_STRING, byteArrayOf(0) + content)

    /** An X.509 time for [epochMillis] (whole seconds, UTC): UTCTime before 2050, GeneralizedTime from 2050. */
    fun time(epochMillis: Long): ByteArray {
        require(epochMillis >= 0) { "Certificate times before 1970 are not supported" }
        val totalSeconds = epochMillis / MILLIS_PER_SECOND
        val (year, month, day) = civilFromDays(totalSeconds / SECONDS_PER_DAY)
        val secondOfDay = totalSeconds % SECONDS_PER_DAY
        val text = buildString {
            if (year < GENERALIZED_TIME_FROM_YEAR) {
                require(year >= EARLIEST_UTC_TIME_YEAR) { "UTCTime cannot encode year $year" }
                append((year % 100).pad(2))
            } else {
                append(year.pad(4))
            }
            append(month.pad(2)).append(day.pad(2))
            append((secondOfDay / SECONDS_PER_HOUR).pad(2))
            append(((secondOfDay % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE).pad(2))
            append((secondOfDay % SECONDS_PER_MINUTE).pad(2))
            append('Z')
        }
        val tag = if (year < GENERALIZED_TIME_FROM_YEAR) TAG_UTC_TIME else TAG_GENERALIZED_TIME
        return tlv(tag, text.encodeToByteArray())
    }

    /** A DER definite length: one byte below 128, otherwise `0x80 | count` followed by the big-endian value. */
    fun length(size: Int): ByteArray {
        require(size >= 0) { "Negative length $size" }
        if (size <= SHORT_FORM_MAX) return byteArrayOf(size.toByte())
        val octets = ArrayList<Byte>()
        var rest = size
        while (rest > 0) {
            octets.add(0, (rest and BYTE_MASK).toByte())
            rest = rest shr BITS_PER_BYTE
        }
        return byteArrayOf((LONG_FORM_FLAG or octets.size).toByte()) + octets.toByteArray()
    }
}

private fun concat(parts: List<ByteArray>): ByteArray {
    val out = ByteArray(parts.sumOf { it.size })
    var offset = 0
    for (part in parts) {
        part.copyInto(out, offset)
        offset += part.size
    }
    return out
}

private fun Int.pad(width: Int): String = toString().padStart(width, '0')

private fun Long.pad(width: Int): String = toString().padStart(width, '0')

/** Days from 0000-03-01 to 1970-01-01, and days in a 400-year Gregorian cycle (Hinnant's civil_from_days). */
private const val DAYS_0000_TO_1970 = 719_468L
private const val DAYS_PER_400_YEARS = 146_097L

/**
 * Year, month and day for a count of days since 1970-01-01 (proleptic Gregorian). Howard Hinnant's
 * `civil_from_days`, which needs no calendar library, so it works in common code.
 */
internal fun civilFromDays(daysSinceEpoch: Long): Triple<Int, Int, Int> {
    val z = daysSinceEpoch + DAYS_0000_TO_1970
    val era = z / DAYS_PER_400_YEARS
    val dayOfEra = z - era * DAYS_PER_400_YEARS
    val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val monthIndex = (5 * dayOfYear + 2) / 153
    val day = dayOfYear - (153 * monthIndex + 2) / 5 + 1
    val month = if (monthIndex < 10) monthIndex + 3 else monthIndex - 9
    val year = yearOfEra + era * 400 + if (month <= 2) 1 else 0
    return Triple(year.toInt(), month.toInt(), day.toInt())
}

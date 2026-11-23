/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

/**
 * The `application/x-www-form-urlencoded` codec of PairingLink, with the results of java.net.URLEncoder and
 * URLDecoder in UTF-8, on every platform: the quirks included (a lone surrogate encodes as `?`, `+` is a space, and a
 * `%` escape takes the two characters after it as a hex number, so `%+F` is byte 0x0F).
 */
internal object PercentCodec {
    private const val RADIX = 16
    private const val ESCAPE_LENGTH = 3
    private const val HEX_DIGITS = "0123456789ABCDEF"
    private const val NIBBLE_BITS = 4
    private const val HEX_NIBBLE_MASK = 0x0F
    private const val UNSAFE_MARK = '?'
    private const val UTF8_TWO_BYTES = 0x80
    private const val UTF8_THREE_BYTES = 0x800
    private const val UTF8_FOUR_BYTES = 0x10000
    private const val SURROGATE_MIN = 0xD800
    private const val LOW_SURROGATE_MIN = 0xDC00
    private const val SURROGATE_BITS = 10
    private const val SURROGATE_OFFSET = 0x10000
    private const val LEAD_TWO = 0xC0
    private const val LEAD_THREE = 0xE0
    private const val LEAD_FOUR = 0xF0
    private const val CONTINUATION = 0x80
    private const val PAYLOAD_BITS = 6
    private const val PAYLOAD_MASK = 0x3F
    private const val THREE_PAYLOADS = PAYLOAD_BITS * 3
    private const val BYTE_MASK = 0xFF

    fun encode(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val high = value[i]
            val codePoint: Int
            if (high.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate()) {
                val highBits = (high.code - SURROGATE_MIN) shl SURROGATE_BITS
                codePoint = highBits + (value[i + 1].code - LOW_SURROGATE_MIN) + SURROGATE_OFFSET
                i += 2
            } else {
                codePoint = if (high.isSurrogate()) UNSAFE_MARK.code else high.code
                i++
            }
            when {
                codePoint == ' '.code -> out.append('+')
                codePoint < UTF8_TWO_BYTES && codePoint.toChar().isUnreserved() -> out.append(codePoint.toChar())
                else -> utf8Of(codePoint).forEach { appendEscape(out, it) }
            }
        }
        return out.toString()
    }

    fun decode(value: String): String {
        val out = StringBuilder(value.length)
        val bytes = ByteArray(value.length)
        var i = 0
        while (i < value.length) {
            when (value[i]) {
                '+' -> {
                    out.append(' ')
                    i++
                }

                '%' -> {
                    var count = 0
                    while (i < value.length && value[i] == '%') {
                        require(i + ESCAPE_LENGTH - 1 < value.length) { "Incomplete trailing escape (%) pattern" }
                        bytes[count++] = hexEscape(value[i + 1], value[i + 2]).toByte()
                        i += ESCAPE_LENGTH
                    }
                    out.append(bytes.decodeToString(0, count))
                }

                else -> {
                    out.append(value[i])
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun Char.isUnreserved(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '.' || this == '-' || this == '*' ||
            this == '_'

    private fun appendEscape(out: StringBuilder, byte: Int) {
        out.append('%')
        out.append(HEX_DIGITS[(byte and BYTE_MASK) shr NIBBLE_BITS])
        out.append(HEX_DIGITS[byte and HEX_NIBBLE_MASK])
    }

    /** The UTF-8 bytes of one code point. */
    private fun utf8Of(codePoint: Int): IntArray = when {
        codePoint < UTF8_TWO_BYTES -> intArrayOf(codePoint)

        codePoint < UTF8_THREE_BYTES -> intArrayOf(
            LEAD_TWO or (codePoint shr PAYLOAD_BITS),
            CONTINUATION or (codePoint and PAYLOAD_MASK),
        )

        codePoint < UTF8_FOUR_BYTES -> intArrayOf(
            LEAD_THREE or (codePoint shr (PAYLOAD_BITS * 2)),
            CONTINUATION or ((codePoint shr PAYLOAD_BITS) and PAYLOAD_MASK),
            CONTINUATION or (codePoint and PAYLOAD_MASK),
        )

        else -> intArrayOf(
            LEAD_FOUR or (codePoint shr THREE_PAYLOADS),
            CONTINUATION or ((codePoint shr (PAYLOAD_BITS * 2)) and PAYLOAD_MASK),
            CONTINUATION or ((codePoint shr PAYLOAD_BITS) and PAYLOAD_MASK),
            CONTINUATION or (codePoint and PAYLOAD_MASK),
        )
    }

    /**
     * The byte of a `%` escape whose two characters are [high] and [low], read the way `Integer.parseInt(text, 16)`
     * reads them: a leading `+` or `-` is a sign, and a negative result is an error.
     */
    private fun hexEscape(high: Char, low: Char): Int = when (high) {
        '+', '-' -> {
            val value = digit(low)
            require(high == '+' || value == 0) { "Illegal hex characters in escape (%) pattern - negative value" }
            value
        }

        else -> digit(high) * RADIX + digit(low)
    }

    private fun digit(char: Char): Int = char.digitToIntOrNull(RADIX)
        ?: throw IllegalArgumentException("Illegal hex characters in escape (%) pattern")
}

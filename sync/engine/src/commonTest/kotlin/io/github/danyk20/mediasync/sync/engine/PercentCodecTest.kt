/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** Pinned from java.net.URLEncoder and URLDecoder (UTF-8), which the codec replaces. */
class PercentCodecTest {

    @Test
    fun encodesLikeFormEncoding() {
        PercentCodec.encode("a b+c") shouldBe "a+b%2Bc"
        PercentCodec.encode("%") shouldBe "%25"
        PercentCodec.encode("ü") shouldBe "%C3%BC"
        PercentCodec.encode("*-._~") shouldBe "*-._%7E"
        PercentCodec.encode("/?#&=") shouldBe "%2F%3F%23%26%3D"
        PercentCodec.encode("日本語") shouldBe "%E6%97%A5%E6%9C%AC%E8%AA%9E"
        PercentCodec.encode("x\u0000y") shouldBe "x%00y"
    }

    @Test
    fun encodesAPairOfSurrogatesAsOneCharacterAndALoneOneAsAQuestionMark() {
        PercentCodec.encode("😀") shouldBe "%F0%9F%98%80"
        PercentCodec.encode("\uD83D") shouldBe "%3F"
        PercentCodec.encode("\uDE00\uD83D") shouldBe "%3F%3F"
        PercentCodec.encode("\uD83Dz") shouldBe "%3Fz"
    }

    @Test
    fun decodesLikeFormDecoding() {
        PercentCodec.decode("a+b") shouldBe "a b"
        PercentCodec.decode("%41%42") shouldBe "AB"
        PercentCodec.decode("%e3%81%82") shouldBe "あ"
        PercentCodec.decode("%F0%9F%98%80") shouldBe "😀"
    }

    @Test
    fun signsInEscapesAreReadTheWayJavaReadsThem() {
        PercentCodec.decode("%+F") shouldBe "\u000F"
        PercentCodec.decode("%-0") shouldBe "\u0000"
    }

    @Test
    fun malformedUtf8DecodesToReplacementCharactersAsOnTheJvm() {
        PercentCodec.decode("%C3%28") shouldBe "�("
        PercentCodec.decode("%FF%FE") shouldBe "��"
        PercentCodec.decode("%C3") shouldBe "�"
        PercentCodec.decode("%E2%82") shouldBe "�"
        PercentCodec.decode("%F0%9F%98") shouldBe "�"
    }

    @Test
    fun brokenEscapesAreErrors() {
        listOf("%zz", "%1", "x%", "%-F", "%").forEach {
            shouldThrow<IllegalArgumentException> { PercentCodec.decode(it) }
        }
    }

    @Test
    fun decodingUndoesEncoding() {
        listOf("", "plain", "a b+c%d*e~f&g=h/i", "日本語", "x😀y").forEach { text ->
            PercentCodec.decode(PercentCodec.encode(text)) shouldBe text
        }
        PercentCodec.decode(PercentCodec.encode("\uD83D")) shouldBe "?" // A lone surrogate becomes '?' on the way.
    }
}

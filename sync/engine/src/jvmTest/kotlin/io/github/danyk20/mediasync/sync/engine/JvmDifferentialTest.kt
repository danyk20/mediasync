/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

import io.github.danyk20.mediasync.core.model.DeviceId
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random
import kotlin.test.Test
import okio.ByteString.Companion.toByteString

/**
 * The shared code, checked against the JDK code it replaced (the JVM is where that code still runs): proof that the
 * JVM results didn't change. The old implementations are copied here, not called from the shipped code.
 */
class JvmDifferentialTest {

    @Test
    fun sha256IsTheMessageDigestWithAnyChunking() {
        val random = Random(11)
        repeat(200) {
            val size = random.nextInt(0, 5_000)
            val offset = random.nextInt(0, 7)
            val buffer = random.nextBytes(offset + size + 3)
            val reference = MessageDigest.getInstance("SHA-256")
            val sha = newSha256()
            var pos = 0
            while (pos < size) {
                val count = minOf(random.nextInt(1, 300), size - pos)
                sha.update(buffer, offset + pos, count)
                reference.update(buffer, offset + pos, count)
                pos += count
            }
            sha.digest() shouldBe reference.digest().toByteString()
        }
    }

    @Test
    fun hmacDerivationIsTheJavaxMacOnRandomKeysAndEpochs() {
        val random = Random(12)
        repeat(300) {
            val key = random.nextBytes(TURBO_KEY_BYTES)
            val epoch = random.nextLong(0, 1_000_000_000_000L)

            DirectLink.derived(key.toByteString(), epoch) shouldBe oldDerived(key, epoch)
        }
    }

    @Test
    fun pairingLinksAreTheJdkEncodingForRandomAndMutatedText() {
        val random = Random(13)
        repeat(50_000) {
            val link = randomLink(random)
            val text = OldPairingLink.toText(link)

            link.toString() shouldBe text
            OldPairingLink.parse(text) shouldBe PairingLink.parse(text)
            val mutated = mutate(text, random)
            PairingLink.parse(mutated) shouldBe OldPairingLink.parse(mutated)
        }
    }

    @Test
    fun oneDecimalIsTheJdkFormatExceptAtTies() {
        val random = Random(14)
        var mismatches = 0
        repeat(100_000) {
            val value = random.nextDouble(0.0, 5_000.0)
            val ours = oneDecimal(value)
            val jdk = String.format(Locale.ROOT, "%.1f", value)
            if (ours != jdk) {
                mismatches++
                val scaled = value * 10
                // Only a value a floating-point step away from a tie may round differently.
                (abs(scaled - floor(scaled) - 0.5) < TIE_WINDOW) shouldBe true
            }
        }
        mismatches.toDouble() shouldBeLessThan 100.0
    }

    private fun oldDerived(key: ByteArray, epoch: Long): Pair<String, String> {
        fun text(label: String, length: Int): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            val bytes = mac.doFinal("mediasync-turbo-v1:$label:$epoch".toByteArray(Charsets.UTF_8))
            return String(CharArray(length) { ALPHABET[(bytes[it].toInt() and 0xFF) % ALPHABET.length] })
        }
        return "DIRECT-${text("prefix", 2)}-MediaSync-${text("name", 6)}" to text("passphrase", 24)
    }

    private fun randomLink(random: Random): PairingLink {
        val name = StringBuilder()
        val target = random.nextInt(0, MAX_NAME)
        while (name.length < target) {
            name.append(Character.toChars(random.nextInt(0, MAX_CODE_POINT)))
        }
        val addresses = List(random.nextInt(1, 4)) { randomText(random, 1, 30) }
        return PairingLink(
            consumer = DeviceId.of(random.nextBytes(32)),
            token = random.nextBytes(16).toByteString(),
            addresses = addresses,
            consumerName = name.toString().take(MAX_NAME),
        )
    }

    private fun randomText(random: Random, min: Int, max: Int): String {
        val chars = "abcXYZ019 .:/[]%+=&?-_*~#日😀"
        return String(CharArray(random.nextInt(min, max + 1)) { chars[random.nextInt(chars.length)] })
    }

    private fun mutate(text: String, random: Random): String {
        val noise = "%+/=- \n&?#zZ9"
        val out = StringBuilder(text)
        repeat(random.nextInt(1, 4)) {
            val at = random.nextInt(0, out.length + 1)
            when (random.nextInt(3)) {
                0 -> out.insert(at, noise[random.nextInt(noise.length)])
                1 -> if (at < out.length) out.deleteCharAt(at)
                else -> if (at < out.length) out.setCharAt(at, noise[random.nextInt(noise.length)])
            }
        }
        return out.toString()
    }

    /** The shipped PairingLink before it was made common, with the JDK's Base64 and URL codecs. */
    private object OldPairingLink {
        private val base64 = Base64.getUrlEncoder().withoutPadding()

        fun toText(link: PairingLink): String = buildString {
            append("mediasync://pair?")
            append("v=1")
            append("&id=").append(link.consumer.hex)
            append("&t=").append(base64.encodeToString(link.token.toByteArray()))
            append("&a=").append(link.addresses.joinToString(",") { encode(it) })
            append("&n=").append(encode(link.consumerName))
        }

        fun parse(text: String): PairingLink? {
            val trimmed = text.trim()
            if (!trimmed.startsWith("mediasync://pair?")) return null
            val params = trimmed.removePrefix("mediasync://pair?").split("&").mapNotNull { pair ->
                val separator = pair.indexOf('=')
                if (separator <= 0) null else pair.substring(0, separator) to pair.substring(separator + 1)
            }.toMap()
            if (params["v"] != "1") return null
            return runCatching {
                PairingLink(
                    consumer = DeviceId(params.getValue("id")),
                    token = Base64.getUrlDecoder().decode(params.getValue("t")).toByteString(),
                    addresses = params.getValue("a").split(",").map(::decode).filter { it.isNotBlank() },
                    consumerName = decode(params.getValue("n")),
                )
            }.getOrNull()
        }

        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

        private fun decode(value: String) = URLDecoder.decode(value, "UTF-8")
    }

    private companion object {
        const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        const val MAX_NAME = 64
        const val MAX_CODE_POINT = 0x110000
        const val TIE_WINDOW = 1e-6
    }
}

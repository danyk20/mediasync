/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.pairing

import com.google.zxing.RGBLuminanceSource
import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.sync.engine.PairingLink
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import okio.ByteString.Companion.toByteString
import org.junit.Test

class QrCodesTest {

    private val link = PairingLink(
        consumer = DeviceId.of(ByteArray(32) { it.toByte() }),
        token = ByteArray(16) { (it * 3).toByte() }.toByteString(),
        addresses = listOf("192.168.1.20:47420", "[fd00:0:0:0:0:0:0:1]:47420"),
        consumerName = "Daniel's Pixel 2 XL",
    ).toString()

    @Test
    fun aPairingLinkSurvivesEncodingAndScanning() {
        QrCodes.decode(render(link, pixelsPerModule = 6)) shouldBe link
    }

    @Test
    fun theCodeStaysSmallEnoughToScanFromAcrossATable() {
        // Version 10 or below (57 modules + quiet zone) scans reliably from about half a metre.
        QrCodes.encode(link).width shouldBe QrCodes.encode(link).height
        (QrCodes.encode(link).width <= 61) shouldBe true
    }

    @Test
    fun anImageWithoutACodeReadsAsNothing() {
        val blank = RGBLuminanceSource(100, 100, IntArray(100 * 100) { WHITE })

        QrCodes.decode(blank).shouldBeNull()
    }

    private fun render(text: String, pixelsPerModule: Int): RGBLuminanceSource {
        val matrix = QrCodes.encode(text)
        val size = matrix.width * pixelsPerModule
        val pixels = IntArray(size * size) { index ->
            val x = (index % size) / pixelsPerModule
            val y = (index / size) / pixelsPerModule
            if (matrix[x, y]) BLACK else WHITE
        }
        return RGBLuminanceSource(size, size, pixels)
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}

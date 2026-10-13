/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.pairing

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.danyk20.mediasync.core.model.ExercisedOnDevice

/** Encoding and decoding of pairing QR codes (ZXing, Apache-2.0). */
internal object QrCodes {
    private const val QUIET_ZONE_MODULES = 2

    /** The QR modules for [text], at one pixel per module. */
    fun encode(text: String): BitMatrix = QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        ),
    )

    /** The text of a QR code in [image], or `null` if there isn't a readable one. */
    fun decode(image: LuminanceSource): String? {
        val reader = MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
        }
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(image))).text
        } catch (_: NotFoundException) {
            null
        } catch (_: ReaderException) {
            null
        }
    }
}

/** Draws [text] as a QR code: black on white, whatever the theme, so every camera can read it. */
@Composable
internal fun QrCode(text: String, description: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) { QrCodes.encode(text) }
    Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .background(Color.White)
            .semantics { contentDescription = description },
    ) {
        val cell = size.width / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) {
                    // Slight overlap avoids hairline gaps between modules on fractional pixel sizes.
                    drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 1, cell + 1))
                }
            }
        }
    }
}

/** Reads QR codes from camera frames (the luminance plane is all ZXing needs). */
@ExercisedOnDevice(coveredBy = "manual: scanning a real code; decoding itself is QrCodesTest")
internal class QrAnalyzer(private val onCode: (String) -> Unit) : ImageAnalysis.Analyzer {
    override fun analyze(image: ImageProxy) {
        image.use { frame ->
            val plane = frame.planes[0]
            val bytes = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
            val luminance = PlanarYUVLuminanceSource(
                bytes,
                plane.rowStride,
                frame.height,
                0,
                0,
                frame.width,
                frame.height,
                false,
            )
            QrCodes.decode(luminance)?.let(onCode)
        }
    }
}

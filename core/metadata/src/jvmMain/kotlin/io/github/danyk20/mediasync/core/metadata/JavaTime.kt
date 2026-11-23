/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.metadata

import java.io.InputStream
import java.time.ZoneId
import kotlinx.datetime.toKotlinTimeZone
import okio.buffer
import okio.source

// The same functions over java.time and java.io, for the Android and desktop code that works in them.

/** [Redaction.Edited.open] for a java.io stream. */
fun Redaction.Edited.open(original: InputStream): InputStream = open(original.source()).buffer().inputStream()

/** [CaptureDateGuess.resolve] in [zone]. */
fun CaptureDateGuess.resolve(
    galleryMillis: Long?,
    fileName: String,
    modifiedAtMillis: Long?,
    zone: ZoneId,
): CaptureDate? = resolve(galleryMillis, fileName, modifiedAtMillis, zone.toKotlinTimeZone())

/** [CaptureDateGuess.fromName] in [zone]. */
fun CaptureDateGuess.fromName(fileName: String, modifiedAtMillis: Long?, zone: ZoneId): Long? =
    fromName(fileName, modifiedAtMillis, zone.toKotlinTimeZone())

/** [EmbeddedDates.capturedAt] in [zone]. */
fun EmbeddedDates.capturedAt(size: Long, reader: ByteReader, zone: ZoneId): CapturedAt? =
    capturedAt(size, reader, zone.toKotlinTimeZone())

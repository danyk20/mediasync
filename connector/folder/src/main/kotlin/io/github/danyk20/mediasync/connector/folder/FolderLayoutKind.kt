/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector.folder

import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId

/** How the folder connector arranges files below its folder. */
internal enum class FolderLayoutKind {
    /** `2025/05/IMG_1.jpg`, by capture date; items without a date go to `Undated`. */
    YEAR_MONTH,

    /** All files in the folder itself. */
    FLAT,
    ;

    fun directoryFor(root: Path, capturedAtMillis: Long?, zone: ZoneId): Path = when (this) {
        FLAT -> root

        YEAR_MONTH -> if (capturedAtMillis == null) {
            root.resolve(UNDATED)
        } else {
            val date = Instant.ofEpochMilli(capturedAtMillis).atZone(zone)
            root.resolve("%04d".format(date.year)).resolve("%02d".format(date.monthValue))
        }
    }
}

internal const val UNDATED = "Undated"

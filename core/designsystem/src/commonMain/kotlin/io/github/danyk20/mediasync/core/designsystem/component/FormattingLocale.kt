/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.designsystem.component

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** [formatBytes] in [locale] (the screens pass the app's language). */
fun formatBytes(bytes: Long, locale: Locale): String =
    formatBytesWith(bytes) { value, digits -> String.format(locale, "%.${digits}f", value) }

/** [formatMegabytes] in [locale] (the screens pass the app's language). */
fun formatMegabytes(bytes: Long, locale: Locale): String = formatMegabytesWith(bytes) { value ->
    DecimalFormat("0.##", DecimalFormatSymbols.getInstance(locale)).format(value)
}

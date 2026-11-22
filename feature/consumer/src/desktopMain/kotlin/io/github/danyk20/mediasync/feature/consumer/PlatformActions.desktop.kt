/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.consumer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalUriHandler
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale

private val NothingToAsk = ConsumerSystemActions({}, {}, {}, {}, {}, {}, {})

// A desktop has no permission prompts to answer; the desktop app (PLAN M8) opens its own settings.
@Composable
internal actual fun rememberConsumerSystemActions(
    onNotificationsAnswer: (granted: Boolean) -> Unit,
    onTurboAnswer: () -> Unit,
    onTurboReceiveAnswer: (allGranted: Boolean) -> Unit,
): ConsumerSystemActions = NothingToAsk

@Composable
internal actual fun rememberSignInBrowser(onUnavailable: () -> Unit): (url: String) -> Unit {
    val links = LocalUriHandler.current
    val unavailable by rememberUpdatedState(onUnavailable)
    return remember(links) {
        { url ->
            @Suppress("TooGenericExceptionCaught") // Whatever the platform throws without a browser, there's none.
            try {
                links.openUri(url)
            } catch (_: Exception) {
                unavailable()
            }
        }
    }
}

@Composable
internal actual fun rememberIs24HourFormat(): Boolean {
    val pattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern(
        null,
        FormatStyle.SHORT,
        IsoChronology.INSTANCE,
        Locale.getDefault(),
    )
    return 'a' !in pattern
}

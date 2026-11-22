/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
@file:Suppress("MatchingDeclarationName") // The platform actions of several kinds, one file.

package io.github.danyk20.mediasync.feature.consumer

import androidx.compose.runtime.Composable
import java.util.Locale

/** The system's side of the home screen: what it asks the user to allow, and which settings pages it opens. */
internal class ConsumerSystemActions(
    /** Asks to show sync progress as notifications, where the system asks for that. */
    val requestNotifications: () -> Unit,
    /** Asks for what Turbo transfer needs (nearby devices, or location), when a sending phone asked for it. */
    val requestTurbo: () -> Unit,
    /** Asks for what *Turbo receive* needs, to start it afterwards. */
    val requestTurboReceive: () -> Unit,
    /** MediaSync's page in the system settings: permissions, battery use. */
    val openAppSettings: () -> Unit,
    /** The page where storage is freed up. */
    val openStorageSettings: () -> Unit,
    /** The page where the location is turned on. */
    val openLocationSettings: () -> Unit,
    /** MediaSync's *Display over other apps* switch in the system settings. */
    val openDisplayOverOtherApps: () -> Unit,
)

/**
 * The system's side of the home screen. [onNotificationsAnswer] after the user answered the request to show
 * notifications, [onTurboAnswer] after Turbo's, and [onTurboReceiveAnswer] with whether everything *Turbo receive*
 * needs was allowed. On desktop nothing needs asking: every action does nothing.
 */
@Composable
internal expect fun rememberConsumerSystemActions(
    onNotificationsAnswer: (granted: Boolean) -> Unit,
    onTurboAnswer: () -> Unit,
    onTurboReceiveAnswer: (allGranted: Boolean) -> Unit,
): ConsumerSystemActions

/**
 * Opens the page where the user signs in with Cloudflare, in the browser, in a tab above the app on Android.
 * [onUnavailable] if there is no browser to open it in.
 */
@Composable
internal expect fun rememberSignInBrowser(onUnavailable: () -> Unit): (url: String) -> Unit

/** Whether the user's clock shows 24 hours, for the time picker. */
@Composable
internal expect fun rememberIs24HourFormat(): Boolean

/** The language the user's weekday names are in. */
@Composable
internal expect fun rememberDisplayLocale(): Locale

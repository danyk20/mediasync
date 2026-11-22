/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.consumer

import io.github.danyk20.mediasync.sync.android.SetupResult
import io.github.danyk20.mediasync.sync.android.SetupStep
import io.github.danyk20.mediasync.sync.android.WakeUpStatus
import kotlinx.coroutines.flow.Flow

/**
 * What the receiving phone's screens ask of the device they run on: what it allows MediaSync, what it keeps, and the
 * services behind its settings. The Android app binds [AndroidConsumerDevice]; the desktop app brings its own.
 */
interface ConsumerDevice {
    /** Whether MediaSync may show notifications (always, before Android 13). */
    fun canNotify(): Boolean

    /** Whether battery use is restricted for MediaSync, which receive windows at night can't have. */
    fun batteryRestricted(): Boolean

    /** Replaces the *Turbo receive* key after a sending phone was removed, so it can't find this phone any more. */
    fun renewTurboKey()

    /** Gives what was received before the simple name too, if *Simple file names* is on; how many were renamed. */
    suspend fun simplifyStoredNames(): Int

    /** What the last check of this phone's wake-ups over the internet found; `null` before one could tell. */
    val wakeUpStatus: Flow<WakeUpStatus?>

    /** Cloud backup as the home screen's row shows it; `null` for a build without any. */
    fun backupLine(): Flow<BackupLine?>
}

/** Setting up internet sync with the user's Cloudflare account (docs/PLAN.md §8.4), by whatever deploys the Worker. */
interface InternetSetup {
    /** Whether this build carries the Worker; without it, only a Worker's address can be entered. */
    fun available(): Boolean

    /** Deploys the Worker with [token] and turns internet sync on with it; [onStep] reports the progress. */
    suspend fun deploy(token: String, onStep: (SetupStep) -> Unit): SetupResult

    /** Cloudflare's page to create an API token, filled in with what MediaSync needs. */
    val tokenUrl: String
}

/** MediaSync's OAuth client for signing in with Cloudflare; both empty in a build without one. */
class CloudflareOAuthClient(val clientId: String, val redirectUri: String)

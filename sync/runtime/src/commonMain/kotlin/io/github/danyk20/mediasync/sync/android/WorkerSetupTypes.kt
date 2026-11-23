/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

/** Where setting up internet sync is (docs/PLAN.md §8.4), as the screen shows it. */
enum class SetupStep { ACCOUNT, RELAY, UPLOAD, SCHEDULE, ADDRESS, STARTING, CHECKING }

/** Why setting up internet sync didn't work. */
enum class SetupFailure {
    /** Cloudflare doesn't accept the token. */
    INVALID_TOKEN,

    /** The token may not edit Workers. */
    NOT_ALLOWED,

    /** Cloudflare can't be reached. */
    OFFLINE,

    /** The Worker was deployed but didn't answer in time; it usually does a little later. */
    NOT_STARTED,

    /** Cloudflare refused for another reason, given in the result's detail. */
    REFUSED,

    /** Signing in with Cloudflare was cancelled, or not allowed. */
    SIGN_IN_CANCELLED,

    /** The browser came back with an answer that wasn't for this sign-in (another state): it was ignored. */
    SIGN_IN_MISMATCH,

    /** Cloudflare gave no token for the sign-in, for the reason in the result's detail. */
    SIGN_IN_REFUSED,
}

sealed interface SetupResult {
    /**
     * Internet sync is on, through the Worker at [url]; [relay]: with Cloudflare's relay. [wakeUps]: whether sending
     * phones can wake this phone through it; `null` if that couldn't be told yet.
     */
    data class Done(val url: String, val relay: Boolean, val wakeUps: WakeUpStatus? = null) : SetupResult

    data class Failed(val reason: SetupFailure, val detail: String? = null) : SetupResult
}

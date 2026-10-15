/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector

/** How an upload went. Connectors report failures this way instead of throwing. */
sealed interface UploadResult {
    /** The service has the item; [remoteId] finds it there. */
    data class Uploaded(val remoteId: String, val bytesSent: Long) : UploadResult

    /** Another app on this phone backs it up: it's in that app's folder, at [location] (docs/PLAN.md §9.3.1). */
    data class HandedOver(val location: String) : UploadResult

    /** Not backed up, and trying again wouldn't change that. */
    data class Skipped(val reason: SkipReason) : UploadResult

    /** It didn't work this time; [error] says whether, and when, to try again. */
    data class Failed(val error: ConnectorError) : UploadResult
}

/** Why an item isn't backed up. */
enum class SkipReason {
    /** Larger than the service accepts ([ConnectorLimits]). */
    TOO_LARGE,

    /** A kind of file the service doesn't take, such as a video for a photos-only service. */
    UNSUPPORTED_TYPE,

    /** The phone no longer has it, so there's nothing to back up. */
    NOT_ON_PHONE,

    /** Not in the folder the other app backs up. */
    OUTSIDE_BACKUP_FOLDER,

    /** The service refused it for good, for example as damaged. */
    REJECTED,
}

/**
 * Why an upload or a check failed. Messages say what went wrong in general terms: never a file name, a place or
 * an account name, as they reach the diagnostic log (docs/PLAN.md §11).
 */
sealed interface ConnectorError {
    /** Whether trying again soon may work. */
    val retryable: Boolean

    /** The network or the service had a problem. */
    data class Transient(val message: String) : ConnectorError {
        override val retryable: Boolean get() = true
    }

    /** Too many requests (HTTP 429); the service may say how long to wait. */
    data class RateLimited(val retryAfterMillis: Long? = null) : ConnectorError {
        override val retryable: Boolean get() = true
    }

    /** The daily allowance is used up: nothing goes through until [resetsAtMillis] (docs/PLAN.md §9.3.2). */
    data class QuotaExceeded(val resetsAtMillis: Long? = null) : ConnectorError {
        override val retryable: Boolean get() = false
    }

    /** The user has to sign in again: "Google account needs sign-in again → Fix" (docs/PLAN.md §3.3). */
    data object SignInNeeded : ConnectorError {
        override val retryable: Boolean get() = false
    }
}

/** Where a backed-up item stands, in the backup ledger (docs/PLAN.md §9.4). */
enum class BackupState {
    /** Stored where another app on this phone backs it up; that app uploads it on its own (docs/PLAN.md §9.6). */
    HANDED_OVER,

    /**
     * The other app removed this phone's copy. Its cleanup tools only remove what it has backed up, so this counts as
     * backed up (docs/PLAN.md §9.3.1).
     */
    REMOVED_BY_APP,

    /** Uploaded by MediaSync. */
    BACKED_UP,

    /** Uploaded, and confirmed to still be there just before a cleanup (docs/PLAN.md §9.4). */
    VERIFIED,

    /** Not backed up, for good: see [SkipReason]. */
    SKIPPED,
}

/**
 * One entry in the backup ledger: [contentId] went to [accountId], where [remoteId] finds it (another app's
 * connector: where it was stored on this phone).
 *
 * @property backedUpAtMillis when it was uploaded or handed over.
 * @property checkedAtMillis when its state last changed after that, or was confirmed.
 */
data class BackupReceipt(
    val contentId: ContentId,
    val accountId: AccountId,
    val remoteId: String,
    val state: BackupState,
    val backedUpAtMillis: Long,
    val checkedAtMillis: Long? = null,
)

/** What a check of the service found for one receipt. */
enum class VerifyResult {
    /** Still there. */
    PRESENT,

    /** Gone, for example deleted on the service's website: it must not be removed from the phone. */
    MISSING,

    /** Couldn't check now: count it as not confirmed. */
    UNKNOWN,

    /** This connector can't check ([Capability.VERIFY] missing). */
    UNSUPPORTED,
}

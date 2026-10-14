/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector

import io.github.danyk20.mediasync.core.diagnostics.Area
import io.github.danyk20.mediasync.core.diagnostics.Diagnostics
import java.time.Clock

/**
 * The receiving phone's backup ledger (docs/PLAN.md §9.4): which received items each account still needs, and a
 * receipt for each one it got. Receipts outlive the files, which the connector app may remove.
 */
interface BackupLedger {
    /** Up to [limit] received items that [account] has no receipt for yet, oldest first. */
    suspend fun pending(account: AccountId, limit: Int): List<BackupItem>

    /** Adds [receipts], replacing any earlier one for the same item and account. */
    suspend fun record(receipts: List<BackupReceipt>)

    suspend fun receipts(account: AccountId, state: BackupState): List<BackupReceipt>
}

/**
 * Prepares an item for an uploading connector: the *Photo details* policy and date repair, then the quality option
 * (docs/PLAN.md §9.1). Its result is what gets uploaded, typically a temporary upload copy.
 */
fun interface Preprocessor {
    suspend fun prepare(item: BackupItem, connector: ConnectorDescriptor): BackupItem

    companion object {
        /** Uploads items as they are. */
        val None = Preprocessor { item, _ -> item }
    }
}

/** What one backup run did. A run stops at the first failure, [stoppedBy]; the next run tries again. */
data class BackupRun(
    val handedOver: Int = 0,
    val uploaded: Int = 0,
    val skipped: Int = 0,
    val stoppedBy: ConnectorError? = null,
) {
    val total: Int get() = handedOver + uploaded + skipped
}

/**
 * Runs the same steps for every connector (docs/PLAN.md §9.1): select what's waiting → preprocess → upload → record a
 * receipt. Connectors where another app does the backup skip preprocessing: the file stored on arrival is exactly
 * what that app uploads, and it was prepared then (§9.3.1).
 */
class BackupOrchestrator(
    private val ledger: BackupLedger,
    private val clock: Clock,
    private val preprocessor: Preprocessor = Preprocessor.None,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) {
    /** Backs up everything waiting for [account] through [connector]. */
    suspend fun backUp(account: ConnectorAccount, connector: BackupConnector): BackupRun {
        var run = BackupRun()
        val seen = HashSet<ContentId>()
        while (run.stoppedBy == null) {
            val batch = ledger.pending(account.id, batchSize).filter { seen.add(it.contentId) }
            if (batch.isEmpty()) break // Done, or the ledger keeps offering what this run has already done.
            for (item in batch) {
                val result = upload(item, connector)
                run = run.after(result)
                if (result is UploadResult.Failed) break
                ledger.record(listOf(receiptFor(item, account, result)))
            }
        }
        if (run.total > 0 || run.stoppedBy != null) log(connector.descriptor, run)
        return run
    }

    /**
     * For a connector where another app backs up: the items that app has since removed from this phone are backed up
     * and removed (docs/PLAN.md §9.3.1). Returns how many were found.
     */
    suspend fun reconcile(account: ConnectorAccount, connector: DeviceAppConnector): Int {
        val handedOver = ledger.receipts(account.id, BackupState.HANDED_OVER)
        if (handedOver.isEmpty()) return 0
        val removed = connector.removedFromPhone(handedOver)
        val now = clock.millis()
        val changed = handedOver.filter { it.contentId in removed }
            .map { it.copy(state = BackupState.REMOVED_BY_APP, checkedAtMillis = now) }
        if (changed.isNotEmpty()) {
            ledger.record(changed)
            val id = connector.descriptor.id
            Diagnostics.log(Area.BACKUP, "$id: ${changed.size} backed up and removed from the phone")
        }
        return changed.size
    }

    private suspend fun upload(item: BackupItem, connector: BackupConnector): UploadResult {
        val descriptor = connector.descriptor
        if (item.video && Capability.VIDEO !in descriptor) return UploadResult.Skipped(SkipReason.UNSUPPORTED_TYPE)
        val prepared = if (descriptor.delegated) item else preprocessor.prepare(item, descriptor)
        if (!descriptor.limits.allows(prepared.size, prepared.video)) return UploadResult.Skipped(SkipReason.TOO_LARGE)
        return connector.upload(prepared)
    }

    private fun receiptFor(item: BackupItem, account: ConnectorAccount, result: UploadResult): BackupReceipt {
        val now = clock.millis()
        val (state, remoteId) = when (result) {
            is UploadResult.Uploaded -> BackupState.BACKED_UP to result.remoteId
            is UploadResult.HandedOver -> BackupState.HANDED_OVER to result.location
            is UploadResult.Skipped -> BackupState.SKIPPED to result.reason.name
            is UploadResult.Failed -> error("A failed upload has no receipt")
        }
        return BackupReceipt(item.contentId, account.id, remoteId, state, backedUpAtMillis = now)
    }

    private fun BackupRun.after(result: UploadResult): BackupRun = when (result) {
        is UploadResult.Uploaded -> copy(uploaded = uploaded + 1)
        is UploadResult.HandedOver -> copy(handedOver = handedOver + 1)
        is UploadResult.Skipped -> copy(skipped = skipped + 1)
        is UploadResult.Failed -> copy(stoppedBy = result.error)
    }

    private fun log(descriptor: ConnectorDescriptor, run: BackupRun) {
        val counts = listOf("handed over" to run.handedOver, "uploaded" to run.uploaded, "skipped" to run.skipped)
            .filter { it.second > 0 }
            .joinToString { "${it.second} ${it.first}" }
            .ifEmpty { "nothing done" }
        val stopped = run.stoppedBy?.let { "; stopped: ${it.describe()}" } ?: ""
        Diagnostics.log(Area.BACKUP, "${descriptor.id}: $counts$stopped")
    }

    private companion object {
        const val DEFAULT_BATCH_SIZE = 100
    }
}

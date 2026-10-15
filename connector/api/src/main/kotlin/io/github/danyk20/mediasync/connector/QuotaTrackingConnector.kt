/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Counts requests against the service's daily allowance ([ConnectorLimits.requestsPerDay]) and stops asking once
 * it's used up, until the service's day ends in [zone] (docs/PLAN.md §9.3.2). The service has the last word: when it
 * says the allowance is used up, which happens sooner when others share it, nothing more is sent that day.
 */
class QuotaTrackingConnector(
    override val inner: BackupConnector,
    private val requestsPerDay: Int,
    private val zone: ZoneId,
    private val clock: Clock,
) : DecoratedConnector,
    BackupConnector by inner {

    private val mutex = Mutex()
    private var day: LocalDate = today()
    private var used = 0

    /** Requests left today. */
    suspend fun remaining(): Int = mutex.withLock {
        rollOver()
        requestsPerDay - used
    }

    override suspend fun upload(item: BackupItem, progress: ProgressSink): UploadResult {
        if (!spend()) return UploadResult.Failed(ConnectorError.QuotaExceeded(resetsAtMillis()))
        val result = inner.upload(item, progress)
        val error = (result as? UploadResult.Failed)?.error as? ConnectorError.QuotaExceeded ?: return result
        useUp()
        return UploadResult.Failed(error.copy(resetsAtMillis = error.resetsAtMillis ?: resetsAtMillis()))
    }

    override suspend fun verify(receipts: List<BackupReceipt>): Map<ContentId, VerifyResult> {
        if (!spend()) return receipts.associate { it.contentId to VerifyResult.UNKNOWN }
        return inner.verify(receipts)
    }

    override suspend fun lookup(ids: List<ContentId>): Map<ContentId, String> =
        if (spend()) inner.lookup(ids) else emptyMap()

    /** Counts one request; `false` if none are left today. */
    private suspend fun spend(): Boolean = mutex.withLock {
        rollOver()
        (used < requestsPerDay).also { allowed -> if (allowed) used++ }
    }

    private suspend fun useUp() = mutex.withLock { used = requestsPerDay }

    private fun rollOver() {
        val now = today()
        if (now != day) {
            day = now
            used = 0
        }
    }

    private fun today(): LocalDate = clock.instant().atZone(zone).toLocalDate()

    private fun resetsAtMillis(): Long = today().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
}

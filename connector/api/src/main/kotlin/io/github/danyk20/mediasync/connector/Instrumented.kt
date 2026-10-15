/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector

import io.github.danyk20.mediasync.core.diagnostics.Area
import io.github.danyk20.mediasync.core.diagnostics.Diagnostics
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

/** What a connector did since it was created: for the diagnostic log, and speeds in the UI. */
data class ConnectorMetrics(
    val uploads: Long = 0,
    val handedOver: Long = 0,
    val skipped: Long = 0,
    val failures: Long = 0,
    val bytesSent: Long = 0,
    /** Time spent in uploads, all attempts included. */
    val uploadNanos: Long = 0,
)

/**
 * Measures every connector and writes its failures to the diagnostic log, by kind only: never which item
 * (docs/PLAN.md §11). A connector that throws instead of reporting a failure is caught here, so a bug in one
 * connector stops its backup run without stopping the app.
 */
class InstrumentedConnector(override val inner: BackupConnector, private val nanoTime: () -> Long = System::nanoTime) :
    DecoratedConnector,
    BackupConnector by inner {

    private val uploads = AtomicLong()
    private val handedOver = AtomicLong()
    private val skipped = AtomicLong()
    private val failures = AtomicLong()
    private val bytesSent = AtomicLong()
    private val uploadNanos = AtomicLong()

    fun metrics() = ConnectorMetrics(
        uploads = uploads.get(),
        handedOver = handedOver.get(),
        skipped = skipped.get(),
        failures = failures.get(),
        bytesSent = bytesSent.get(),
        uploadNanos = uploadNanos.get(),
    )

    @Suppress("TooGenericExceptionCaught") // Whatever a connector throws, the backup run reports it and stops.
    override suspend fun upload(item: BackupItem, progress: ProgressSink): UploadResult {
        val started = nanoTime()
        var threw = false
        val result = try {
            inner.upload(item, progress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diagnostics.log(Area.BACKUP, "${descriptor.id}: upload threw", e)
            threw = true
            UploadResult.Failed(ConnectorError.Transient(e.javaClass.simpleName))
        }
        uploadNanos.addAndGet(nanoTime() - started)
        when (result) {
            is UploadResult.Uploaded -> {
                uploads.incrementAndGet()
                bytesSent.addAndGet(result.bytesSent)
            }

            is UploadResult.HandedOver -> handedOver.incrementAndGet()

            is UploadResult.Skipped -> skipped.incrementAndGet()

            is UploadResult.Failed -> {
                failures.incrementAndGet()
                if (!threw) Diagnostics.log(Area.BACKUP, "${descriptor.id}: upload failed: ${result.error.describe()}")
            }
        }
        return result
    }

    @Suppress("TooGenericExceptionCaught") // A check that throws confirms nothing.
    override suspend fun verify(receipts: List<BackupReceipt>): Map<ContentId, VerifyResult> = try {
        inner.verify(receipts)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Diagnostics.log(Area.BACKUP, "${descriptor.id}: check threw", e)
        receipts.associate { it.contentId to VerifyResult.UNKNOWN }
    }
}

/** The error as the diagnostic log shows it: its kind, and what the service said, never which item. */
fun ConnectorError.describe(): String = when (this) {
    is ConnectorError.Transient -> "network or service problem ($message)"
    is ConnectorError.RateLimited -> "too many requests" + (retryAfterMillis?.let { ", wait $it ms" } ?: "")
    is ConnectorError.QuotaExceeded -> "daily allowance used up"
    ConnectorError.SignInNeeded -> "sign-in needed"
}

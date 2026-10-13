/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine

/** A snapshot of a running transfer, for progress UI. Totals grow as further batches are offered. */
data class TransferProgress(
    val itemsDone: Int,
    val itemsTotal: Int,
    val bytesDone: Long,
    val bytesTotal: Long,
    val currentItem: String?,
)

/** Called often (every chunk); implementations should be cheap and throttle UI updates themselves. */
fun interface ProgressListener {
    fun onProgress(progress: TransferProgress)
}

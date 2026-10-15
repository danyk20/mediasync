/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What internet sync sent over mobile data this month (docs/PLAN.md §2.2: the optional monthly mobile data cap). It
 * starts over each calendar month, in the phone's time zone.
 */
@Singleton
class MobileDataUsage @Inject constructor(@ApplicationContext context: Context, private val clock: Clock) {
    private val preferences = context.getSharedPreferences("mobile_data", Context.MODE_PRIVATE)
    private val _sentThisMonth = MutableStateFlow(read())

    /** Bytes sent over mobile data this month. */
    val sentThisMonth: StateFlow<Long> = _sentThisMonth.asStateFlow()

    /** Bytes sent over mobile data this month, as of now. */
    fun current(): Long = read().also { _sentThisMonth.value = it }

    @Synchronized
    fun add(bytes: Long) {
        if (bytes <= 0) return
        val total = read() + bytes
        preferences.edit {
            putString(MONTH, month())
            putLong(SENT, total)
        }
        _sentThisMonth.value = total
    }

    private fun read(): Long = if (preferences.getString(MONTH, null) == month()) preferences.getLong(SENT, 0) else 0

    private fun month(): String = YearMonth.now(clock).toString()

    private companion object {
        const val MONTH = "month"
        const val SENT = "sent"
    }
}

/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.model

import java.time.LocalDate
import java.time.ZoneId

/**
 * Which days a sending phone sends photos and videos from (docs/PLAN.md §2.2): those taken [from] that day
 * [until] that day, **both days included**. Either end may stay open; with both open, everything is sent. While
 * [until] is set, nothing taken later is sent, new photos included.
 */
data class SendRange(val from: LocalDate? = null, val until: LocalDate? = null) {
    val isEverything: Boolean get() = from == null && until == null

    /** The first moment sent, in [zone]: the start of [from]. */
    fun startMillis(zone: ZoneId): Long? = from?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()

    /** The first moment no longer sent, in [zone]: the start of the day after [until]. */
    fun endMillis(zone: ZoneId): Long? = until?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()

    companion object {
        val Everything = SendRange()
    }
}

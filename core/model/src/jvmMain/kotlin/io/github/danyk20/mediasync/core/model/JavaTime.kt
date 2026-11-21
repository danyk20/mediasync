/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.model

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant
import kotlinx.datetime.toKotlinTimeZone

// The same answers in java.time, for the Android code that works in it.

/** [SyncSchedule.previousDue] at [now], in [now]'s zone. */
fun SyncSchedule.previousDue(now: ZonedDateTime): ZonedDateTime? =
    previousDue(now.toInstant().toKotlinInstant(), now.zone.toKotlinTimeZone())?.toJavaInstant()?.atZone(now.zone)

/** [SyncSchedule.nextDue] at [now], in [now]'s zone. */
fun SyncSchedule.nextDue(now: ZonedDateTime): ZonedDateTime? =
    nextDue(now.toInstant().toKotlinInstant(), now.zone.toKotlinTimeZone())?.toJavaInstant()?.atZone(now.zone)

/** [SendRange.startMillis] in [zone]. */
fun SendRange.startMillis(zone: ZoneId): Long? = startMillis(zone.toKotlinTimeZone())

/** [SendRange.endMillis] in [zone]. */
fun SendRange.endMillis(zone: ZoneId): Long? = endMillis(zone.toKotlinTimeZone())

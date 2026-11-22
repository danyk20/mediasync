/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.designsystem.component

import androidx.compose.runtime.Composable
import java.time.ZonedDateTime
import kotlin.time.toKotlinInstant
import kotlinx.datetime.toKotlinTimeZone

/** [relativeMoment] for `java.time` callers; goes once the screens use kotlinx-datetime. */
@Composable
fun relativeMoment(moment: ZonedDateTime, now: ZonedDateTime): String =
    relativeMoment(moment.toInstant().toKotlinInstant(), now.toInstant().toKotlinInstant(), now.zone.toKotlinTimeZone())

/** [pastMoment] for `java.time` callers; goes once the screens use kotlinx-datetime. */
@Composable
fun pastMoment(moment: ZonedDateTime, now: ZonedDateTime): String =
    pastMoment(moment.toInstant().toKotlinInstant(), now.toInstant().toKotlinInstant(), now.zone.toKotlinTimeZone())

/** [formatTime] for a `java.time` time of day. */
@Composable
fun formatTime(time: java.time.LocalTime): String =
    formatTime(kotlinx.datetime.LocalTime(time.hour, time.minute, time.second, time.nano))

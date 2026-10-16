/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.media

import io.kotest.matchers.shouldBe
import org.junit.Test

/** Which items MediaStore lists to send: only finished files, and within the chosen days. */
class ReadyItemsTest {

    @Test
    fun withoutDaysEveryFinishedItemIsListed() {
        readyItems(startMillis = null, endMillis = null) shouldBe ("is_pending = 0" to null)
    }

    @Test
    fun fromADayItemsTakenThenOrLaterAreListedAndThoseWithoutADateByTheirFile() {
        val (where, args) = readyItems(startMillis = 1_735_686_000_000, endMillis = null)

        where shouldBe "is_pending = 0 AND ((datetaken > 0 AND datetaken >= ?) OR " +
            "((datetaken IS NULL OR datetaken = 0) AND date_modified >= ?))"
        args?.toList() shouldBe listOf("1735686000000", "1735686000") // The file's date is in seconds.
    }

    @Test
    fun untilADayOnlyItemsTakenBeforeItsEndAreListed() {
        val (where, args) = readyItems(startMillis = null, endMillis = 1_743_458_400_000)

        // An item without a date taken never counts as taken at 1970 here.
        where shouldBe "is_pending = 0 AND ((datetaken > 0 AND datetaken < ?) OR " +
            "((datetaken IS NULL OR datetaken = 0) AND date_modified < ?))"
        args?.toList() shouldBe listOf("1743458400000", "1743458400")
    }

    @Test
    fun withBothEndsItemsWithinAreListed() {
        val (where, args) = readyItems(startMillis = 1_000_000, endMillis = 2_000_000)

        where shouldBe "is_pending = 0 AND ((datetaken > 0 AND datetaken >= ? AND datetaken < ?) OR " +
            "((datetaken IS NULL OR datetaken = 0) AND date_modified >= ? AND date_modified < ?))"
        args?.toList() shouldBe listOf("1000000", "2000000", "1000", "2000")
    }
}

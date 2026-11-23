/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

/** How much of the month's free relay allowance is used, as far as the user is told (docs/PLAN.md §8.5). */
enum class RelayLevel {
    FINE,

    /** 80 % of the free allowance. */
    HIGH,

    /** 95 % of the budget: the relay stops soon. */
    NEARLY_USED_UP,

    /** The relay stopped for the month. */
    USED_UP,
}

val RelayUsage.level: RelayLevel
    get() {
        val used = usedBytes.toDouble()
        return when {
            !available || usedBytes >= limitBytes -> RelayLevel.USED_UP
            used >= limitBytes * NEARLY_USED_UP_SHARE -> RelayLevel.NEARLY_USED_UP
            used >= freeBytes * HIGH_SHARE -> RelayLevel.HIGH
            else -> RelayLevel.FINE
        }
    }

private const val HIGH_SHARE = 0.8
private const val NEARLY_USED_UP_SHARE = 0.95

/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

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

/**
 * Tells the receiving phone's user when the month's relay allowance runs low (docs/PLAN.md §8.5): once at 80 %, once
 * at 95 %, and when the relay stopped, each at most once a month.
 */
@Singleton
class RelayAlerts @Inject constructor(@ApplicationContext private val context: Context) {
    private val preferences = context.getSharedPreferences("relay_alerts", Context.MODE_PRIVATE)

    /** Notifies if [usage] reached a level the user wasn't told about yet this month. */
    @Synchronized
    fun check(usage: RelayUsage) {
        val period = usage.resetsAt?.toEpochMilli() ?: 0L
        val told = if (preferences.getLong(PERIOD, -1) == period) preferences.getInt(LEVEL, 0) else 0
        val level = usage.level
        if (level.ordinal <= told) return
        // Told only once shown: with notifications off, it's told when they're on again.
        if (notify(usage, level)) {
            preferences.edit {
                putLong(PERIOD, period)
                putInt(LEVEL, level.ordinal)
            }
        }
    }

    /** Whether it could be shown. */
    private fun notify(usage: RelayUsage, level: RelayLevel): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.relay_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val gigabytes = { bytes: Long -> (bytes / BYTES_PER_GB).toString() }
        val (title, text) = when (level) {
            RelayLevel.USED_UP ->
                R.string.relay_used_up_title to
                    context.getString(R.string.relay_used_up_text)

            RelayLevel.NEARLY_USED_UP ->
                R.string.relay_nearly_used_up_title to
                    context.getString(R.string.relay_nearly_used_up_text, gigabytes(usage.limitBytes))

            else ->
                R.string.relay_high_title to
                    context.getString(R.string.relay_high_text, gigabytes(usage.usedBytes), gigabytes(usage.freeBytes))
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(context.getString(title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        return try {
            manager.notify(NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false // The permission was revoked just now.
        }
    }

    private companion object {
        const val CHANNEL = "relay-allowance"
        const val NOTIFICATION_ID = 10
        const val PERIOD = "period"
        const val LEVEL = "level"
        const val BYTES_PER_GB = 1_000_000_000L
    }
}

private const val HIGH_SHARE = 0.8
private const val NEARLY_USED_UP_SHARE = 0.95

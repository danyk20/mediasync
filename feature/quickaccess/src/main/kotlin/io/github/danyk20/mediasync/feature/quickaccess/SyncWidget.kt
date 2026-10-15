/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.quickaccess

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.danyk20.mediasync.core.model.ExercisedOnDevice
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The home-screen widget: how syncing stands, and *Sync now*. [QuickAccess] keeps it current while MediaSync runs. */
@ExercisedOnDevice(coveredBy = "feature:quickaccess QuickAccessTest (the widget's views) and a check on an emulator")
@AndroidEntryPoint
class SyncWidget : AppWidgetProvider() {
    @Inject
    lateinit var quickSync: QuickSync

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                manager.updateAppWidget(ids, syncWidgetViews(context, quickSync.status.first()))
            } finally {
                pending.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_RUN) return
        val action = intent.getStringExtra(EXTRA_ACTION)?.let { name ->
            QuickAction.entries.firstOrNull {
                it.name ==
                    name
            }
        }
        action?.let(quickSync::run)
    }

    internal companion object {
        const val ACTION_RUN = "io.github.danyk20.mediasync.action.QUICK_SYNC"
        const val EXTRA_ACTION = "action"
    }
}

/** Keeps the widgets current while MediaSync runs. Call [start] when the app starts. */
@Singleton
class QuickAccess @Inject constructor(
    @ApplicationContext private val context: Context,
    private val quickSync: QuickSync,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        scope.launch {
            val manager = AppWidgetManager.getInstance(context) ?: return@launch
            val widgets = ComponentName(context, SyncWidget::class.java)
            quickSync.status.collect { status ->
                val ids = manager.getAppWidgetIds(widgets)
                if (ids.isNotEmpty()) manager.updateAppWidget(ids, syncWidgetViews(context, status))
            }
        }
    }
}

/** The widget for [status]. The button runs the action, or opens MediaSync when it isn't set up yet. */
internal fun syncWidgetViews(context: Context, status: QuickStatus): RemoteViews {
    val labels = context.quickLabels(status)
    return RemoteViews(context.packageName, R.layout.sync_widget).apply {
        setTextViewText(R.id.sync_widget_status, labels.detail)
        setTextViewText(R.id.sync_widget_action, labels.action)
        setBoolean(R.id.sync_widget_action, "setEnabled", !status.busy)
        tap(context, status.action)?.let { setOnClickPendingIntent(R.id.sync_widget_action, it) }
    }
}

private fun tap(context: Context, action: QuickAction): PendingIntent? = when (action) {
    QuickAction.OPEN_APP -> context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
        PendingIntent.getActivity(context, action.ordinal, it, PendingIntent.FLAG_IMMUTABLE)
    }

    else -> PendingIntent.getBroadcast(
        context,
        action.ordinal,
        Intent(context, SyncWidget::class.java)
            .setAction(SyncWidget.ACTION_RUN)
            .putExtra(SyncWidget.EXTRA_ACTION, action.name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

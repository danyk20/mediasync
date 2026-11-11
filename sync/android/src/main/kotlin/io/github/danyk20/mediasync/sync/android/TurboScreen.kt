/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.danyk20.mediasync.core.diagnostics.Area
import io.github.danyk20.mediasync.core.diagnostics.Diagnostics
import io.github.danyk20.mediasync.core.model.ExercisedOnDevice
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Shows MediaSync while a Turbo link opens on a receiving phone before Android 13 (docs/PLAN.md §8.3).
 *
 * There, Wi-Fi Direct and Wi-Fi Aware need location, which the user may allow only while MediaSync is in use. A sending
 * phone's Turbo wakes this phone over the internet, in the background, and Android then refuses the link. If the user
 * let MediaSync display over other apps, it starts [TurboScreenActivity] instead: over the lock screen, turning the
 * screen on, so MediaSync is in use while the link opens. It goes away when the link closes.
 */
@Singleton
@ExercisedOnDevice(coveredBy = "sync:android TurboScreenTest")
class TurboScreen @Inject constructor(@ApplicationContext private val context: Context) : LinkScreen {
    override fun show() {
        when (screenAction(Build.VERSION.SDK_INT, inForeground(), mayShow(context))) {
            ScreenAction.NOTHING -> Unit

            ScreenAction.NOT_ALLOWED -> Diagnostics.log(
                Area.RECEIVE,
                "Turbo: MediaSync isn't on screen and may not show itself (Display over other apps): " +
                    "Android may refuse the link",
            )

            ScreenAction.SHOW -> bringUp()
        }
    }

    /** Starts [TurboScreenActivity] and waits until it's on screen; `false` if it didn't come in time. */
    internal fun bringUp(): Boolean {
        val shown = CompletableDeferred<Unit>()
        TurboScreenActivity.waiting = shown
        context.startActivity(
            Intent(context, TurboScreenActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
        )
        val up = runBlocking { withTimeoutOrNull(SHOW_TIMEOUT_MILLIS) { shown.await() } } != null
        Diagnostics.log(
            Area.RECEIVE,
            if (up) {
                "Turbo: MediaSync shown for the link"
            } else {
                "Turbo: MediaSync didn't come on screen within ${since(0, SHOW_TIMEOUT_MILLIS)}"
            },
        )
        return up
    }

    override fun hide() {
        TurboScreenActivity.current?.let { screen -> screen.runOnUiThread { screen.finish() } }
    }

    override val needsPermission: Boolean get() = canNeedIt && !mayShow(context)

    /** On screen now: an activity of MediaSync's is visible. */
    private fun inForeground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    companion object {
        /** Android holds a screen started from the background for up to 5 s after the user went home. */
        private const val SHOW_TIMEOUT_MILLIS = 10_000L

        /** Before Android 13: a Turbo link may need MediaSync on screen. */
        val canNeedIt: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

        /** The user let MediaSync display over other apps, which lets it show itself from the background. */
        fun mayShow(context: Context): Boolean = Settings.canDrawOverlays(context)
    }
}

internal enum class ScreenAction {
    /** A link opens without it: from Android 13 (nearby devices), or with MediaSync on screen already. */
    NOTHING,

    /** Needed, but MediaSync may not show itself from the background. */
    NOT_ALLOWED,
    SHOW,
}

/** What [TurboScreen] does before a link opens on Android [sdk]. */
internal fun screenAction(sdk: Int, inForeground: Boolean, mayShow: Boolean): ScreenAction = when {
    sdk >= Build.VERSION_CODES.TIRAMISU || inForeground -> ScreenAction.NOTHING
    !mayShow -> ScreenAction.NOT_ALLOWED
    else -> ScreenAction.SHOW
}

/**
 * What [TurboScreen] shows: one line saying Turbo is receiving, over the lock screen, while the link is open. It keeps
 * the screen on meanwhile, as Wi-Fi Aware sets up each sending phone's data path after the link opened.
 */
@ExercisedOnDevice(coveredBy = "sync:android TurboScreenTest")
class TurboScreenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val padding = (PADDING_DP * resources.displayMetrics.density).toInt()
        setContentView(
            TextView(this).apply {
                text = getString(R.string.turbo_screen_receiving)
                gravity = Gravity.CENTER
                textSize = TEXT_SP
                setPadding(padding, padding, padding, padding)
            },
        )
        current = this
    }

    override fun onResume() {
        super.onResume()
        waiting?.complete(Unit)
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    internal companion object {
        private const val PADDING_DP = 32
        private const val TEXT_SP = 20f

        @Volatile
        var current: TurboScreenActivity? = null

        @Volatile
        var waiting: CompletableDeferred<Unit>? = null
    }
}

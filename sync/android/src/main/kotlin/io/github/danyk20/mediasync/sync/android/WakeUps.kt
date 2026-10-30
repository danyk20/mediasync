/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.content.edit
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.danyk20.mediasync.core.diagnostics.Area
import io.github.danyk20.mediasync.core.diagnostics.Diagnostics
import io.github.danyk20.mediasync.core.model.ExercisedOnDevice
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The receiving phone's FCM registration, its Firebase Installation ID, by which its Worker wakes it when a sending
 * phone syncs over the internet (docs/PLAN.md §10.4). Firebase only starts here, on a receiving phone with internet
 * sync: it doesn't run on its own (`firebase_messaging_auto_init_enabled` is off). `null` in a build without the
 * app's Firebase project.
 */
@ExercisedOnDevice(coveredBy = "the real-world internet sync test (docs/PLAN.md §15, M5)")
@Singleton
class PushTokens @Inject constructor(@ApplicationContext private val context: Context) {
    private val preferences = context.getSharedPreferences("push", Context.MODE_PRIVATE)

    /**
     * The FCM token, registering with FCM first if this phone hasn't yet: FCM hands it to [WakeUpService], which
     * keeps it here. Blocks up to a few seconds: call it off the main thread.
     */
    fun current(): String? {
        if (FirebaseApp.getApps(context).isEmpty()) return null
        return stored() ?: if (register()) awaitToken() else null
    }

    /** Asks FCM to register this phone; `false` if it couldn't. */
    private fun register(): Boolean = try {
        val messaging = FirebaseMessaging.getInstance()
        messaging.isAutoInitEnabled = true // Keeps the registration fresh from now on.
        Tasks.await(messaging.register(), TIMEOUT_SECONDS, TimeUnit.SECONDS)
        true
    } catch (e: ExecutionException) {
        Diagnostics.log(Area.RECEIVE, "Internet: couldn't register with FCM", e)
        false
    } catch (e: TimeoutException) {
        Diagnostics.log(Area.RECEIVE, "Internet: FCM didn't answer in time", e)
        false
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    /** The token arrives in [WakeUpService.onRegistered], possibly just after the registration completes. */
    private fun awaitToken(): String? {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (stored() == null && System.nanoTime() < end) Thread.sleep(POLL_MILLIS)
        return stored()
    }

    internal fun keep(token: String) = preferences.edit { putString(TOKEN, token) }

    private fun stored(): String? = preferences.getString(TOKEN, null)

    private companion object {
        const val TOKEN = "fcm_token"
        const val TIMEOUT_SECONDS = 10L
        const val POLL_MILLIS = 100L
    }
}

/**
 * Wake-ups (docs/PLAN.md §10.4): a sending phone that can't reach this phone asks the Worker, which sends a
 * high-priority FCM message here. That lets this phone open a short receive window even while MediaSync isn't
 * running; the sending phone, waiting, then connects.
 */
@ExercisedOnDevice(coveredBy = "the real-world internet sync test (docs/PLAN.md §15, M5)")
@AndroidEntryPoint
@SuppressLint("MissingFirebaseInstanceTokenRefresh") // onRegistered replaces the deprecated onNewToken.
class WakeUpService : FirebaseMessagingService() {
    @Inject
    lateinit var receiver: ConsumerReceiver

    @Inject
    lateinit var scheduler: SyncScheduler

    @Inject
    lateinit var tokens: PushTokens

    @Inject
    lateinit var wakeUps: WakeUpCheck

    override fun onMessageReceived(message: RemoteMessage) {
        when (message.data["type"]) {
            "wake" -> {
                Diagnostics.log(Area.RECEIVE, "Internet: woken by a sending phone")
                scheduler.receiveNow()
            }

            "wake-check" -> wakeUps.arrived() // Wakes nothing: it only shows the way works.
        }
    }

    /** A new token, or a renewed one: the Worker must know it, and listening again registers it there. */
    override fun onRegistered(token: String) {
        Diagnostics.log(Area.RECEIVE, "Internet: registered with FCM")
        tokens.keep(token)
        receiver.refreshInternet(again = true)
    }
}

/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.quickaccess

import android.content.Context
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import io.github.danyk20.mediasync.core.database.Peer
import io.github.danyk20.mediasync.core.database.PeerStore
import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.testing.FakeSettingsRepository
import io.github.danyk20.mediasync.sync.android.ConsumerReceiver
import io.github.danyk20.mediasync.sync.android.ProducerState
import io.github.danyk20.mediasync.sync.android.ProducerSync
import io.github.danyk20.mediasync.sync.android.ReceiverState
import io.github.danyk20.mediasync.sync.android.SyncHistory
import io.github.danyk20.mediasync.sync.android.SyncRecord
import io.github.danyk20.mediasync.sync.android.SyncScheduler
import io.github.danyk20.mediasync.sync.engine.TransferProgress
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class QuickAccessTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val idle = ProducerState.Idle()
    private val syncing = ProducerState.Syncing(TransferProgress(12, 40, 145_000_000, 830_000_000, "PXL_1.jpg"))
    private val quiet = ReceiverState()
    private val receiving = ReceiverState(transfers = mapOf(DeviceId.of(ByteArray(32) { 1 }) to syncing.progress!!))
    private val lastSync = SyncRecord(lastSyncMillis = 1_000)

    @Test
    fun aPairedSendingPhoneSyncsNow() {
        quickStatus(DeviceRole.PRODUCER, setOf(DeviceRole.CONSUMER), idle, quiet, lastSync) shouldBe
            QuickStatus(QuickAction.SYNC_NOW, busy = false, lastSyncMillis = 1_000)
        quickStatus(DeviceRole.PRODUCER, setOf(DeviceRole.CONSUMER), syncing, quiet, lastSync).busy shouldBe true
        quickStatus(DeviceRole.PRODUCER, setOf(DeviceRole.CONSUMER), ProducerState.Connecting, quiet, lastSync)
            .busy shouldBe true
    }

    @Test
    fun aReceivingPhoneWithSendingPhonesOpensAReceiveWindow() {
        quickStatus(DeviceRole.CONSUMER, setOf(DeviceRole.PRODUCER), idle, quiet, lastSync) shouldBe
            QuickStatus(QuickAction.RECEIVE_NOW, busy = false, lastSyncMillis = null)
        quickStatus(DeviceRole.CONSUMER, setOf(DeviceRole.PRODUCER), idle, receiving, lastSync).busy shouldBe true
    }

    @Test
    fun aPhoneNotSetUpOpensTheApp() {
        quickStatus(null, emptySet(), idle, quiet, lastSync).action shouldBe QuickAction.OPEN_APP
        quickStatus(DeviceRole.PRODUCER, emptySet(), idle, quiet, lastSync).action shouldBe QuickAction.OPEN_APP
        quickStatus(DeviceRole.CONSUMER, setOf(DeviceRole.CONSUMER), idle, quiet, lastSync).action shouldBe
            QuickAction.OPEN_APP
    }

    @Test
    fun runningSchedulesTheSyncOrTheReceiveWindow() = runBlocking<Unit> {
        val scheduler = mockk<SyncScheduler>(relaxed = true)
        val settings = FakeSettingsRepository().apply { setRole(DeviceRole.PRODUCER) }
        val consumer = Peer(DeviceId.of(ByteArray(32) { 2 }), "Pixel 2 XL", DeviceRole.CONSUMER, 0, null, null)
        val quickSync = QuickSync(
            settings = settings,
            peers = mockk<PeerStore> { every { observeAll() } returns flowOf(listOf(consumer)) },
            producer = mockk<ProducerSync> { every { state } returns MutableStateFlow(idle) },
            receiver = mockk<ConsumerReceiver> { every { state } returns MutableStateFlow(quiet) },
            history = mockk<SyncHistory> { every { record } returns MutableStateFlow(lastSync) },
            scheduler = scheduler,
        )

        quickSync.status.first() shouldBe QuickStatus(QuickAction.SYNC_NOW, lastSyncMillis = 1_000)
        quickSync.run(QuickAction.SYNC_NOW) shouldBe true
        quickSync.run(QuickAction.RECEIVE_NOW) shouldBe true
        quickSync.run(QuickAction.OPEN_APP) shouldBe false

        verify(exactly = 1) { scheduler.syncNow() }
        verify(exactly = 1) { scheduler.receiveNow() }
    }

    @Test
    fun theWordsSayWhatTappingDoesAndHowSyncingStands() {
        val now = 3 * 60 * 60 * 1_000L

        context.quickLabels(QuickStatus(QuickAction.SYNC_NOW, lastSyncMillis = 0), now) shouldBe
            QuickLabels("Sync now", "Last sync 3 hours ago")
        context.quickLabels(QuickStatus(QuickAction.SYNC_NOW), now).detail shouldBe "Not synced yet"
        context.quickLabels(QuickStatus(QuickAction.SYNC_NOW, busy = true), now).detail shouldBe "Syncing…"
        context.quickLabels(QuickStatus(QuickAction.RECEIVE_NOW), now) shouldBe
            QuickLabels("Receive now", "Ready to receive")
        context.quickLabels(QuickStatus(QuickAction.RECEIVE_NOW, busy = true), now).detail shouldBe "Receiving…"
        context.quickLabels(QuickStatus(QuickAction.OPEN_APP), now) shouldBe
            QuickLabels("Set up MediaSync", "Pair this phone first")
    }

    @Test
    fun theWidgetShowsTheStatusAndAButtonThatIsOffWhileSyncing() {
        val idleWidget = syncWidgetViews(
            context,
            QuickStatus(QuickAction.SYNC_NOW),
        ).apply(context, FrameLayout(context))
        val busyWidget =
            syncWidgetViews(
                context,
                QuickStatus(QuickAction.SYNC_NOW, busy = true),
            ).apply(context, FrameLayout(context))
        val setUp = syncWidgetViews(context, QuickStatus(QuickAction.OPEN_APP)).apply(context, FrameLayout(context))

        idleWidget.findViewById<TextView>(R.id.sync_widget_status).text.toString() shouldBe "Not synced yet"
        idleWidget.findViewById<Button>(R.id.sync_widget_action).isEnabled shouldBe true
        busyWidget.findViewById<TextView>(R.id.sync_widget_status).text.toString() shouldBe "Syncing…"
        busyWidget.findViewById<Button>(R.id.sync_widget_action).isEnabled shouldBe false
        setUp.findViewById<Button>(R.id.sync_widget_action).text.toString() shouldBe "Set up MediaSync"
    }
}

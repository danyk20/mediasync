/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import android.content.Context
import androidx.work.WorkManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.Qualifier
import dev.zacsweers.metro.SingleIn
import io.github.danyk20.mediasync.core.database.LeftOutStore
import io.github.danyk20.mediasync.core.database.PeerStore
import io.github.danyk20.mediasync.core.database.RoomReceivedLedger
import io.github.danyk20.mediasync.core.database.RoomSentLedger
import io.github.danyk20.mediasync.core.media.MediaStoreSink
import io.github.danyk20.mediasync.core.media.MediaStoreSource
import io.github.danyk20.mediasync.sync.engine.MediaSink
import io.github.danyk20.mediasync.sync.engine.MediaSource
import io.github.danyk20.mediasync.sync.engine.ReceivedLedger
import io.github.danyk20.mediasync.sync.engine.SentLedger
import io.github.danyk20.mediasync.transport.lan.IdentityProvider
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Lives as long as the app process; sync work outlives individual screens. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@ContributesTo(AppScope::class)
@BindingContainer
@Suppress("TooManyFunctions") // One binding each.
interface SyncModule {
    @Binds
    val MediaStoreSource.bindMediaSource: MediaSource

    @Binds
    val MediaStoreSink.bindMediaSink: MediaSink

    @Binds
    val RoomSentLedger.bindSentLedger: SentLedger

    @Binds
    val RoomReceivedLedger.bindReceivedLedger: ReceivedLedger

    @Binds
    val AndroidDeviceInfo.bindDeviceInfo: DeviceInfo

    @Binds
    val AndroidLocalNetwork.bindAddressProvider: AddressProvider

    @Binds
    val AndroidLocalNetwork.bindLanSockets: LanSockets

    @Binds
    val NsdServiceDiscovery.bindServiceDiscovery: ServiceDiscovery

    @Binds
    val AndroidDeviceConditions.bindDeviceConditions: DeviceConditions

    @Binds
    val TurboLinks.bindDirectLinks: DirectLinks

    @Binds
    val AndroidInternetLinks.bindInternetLinks: InternetLinks

    @Binds
    val TurboScreen.bindLinkScreen: LinkScreen

    @Binds
    val PeerStore.bindPeerBook: PeerBook

    @Binds
    val LeftOutStore.bindLeftOutList: LeftOutList

    @Binds
    val SyncHistory.bindSyncRecorder: SyncRecorder

    companion object {
        @Provides
        @SingleIn(AppScope::class)
        @ApplicationScope
        fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        @Provides
        @SingleIn(AppScope::class)
        fun provideWorkManager(context: Context): WorkManager = WorkManager.getInstance(context)

        @Provides
        fun provideClock(): Clock = PhoneClock

        // The providers below mirror the constructors the classes had: same parameters, same arguments.
        @Provides
        fun provideConnectivity(
            addresses: AddressProvider,
            sockets: LanSockets,
            discovery: ServiceDiscovery,
            directLinks: DirectLinks,
            internet: InternetLinks,
        ): Connectivity = Connectivity(addresses, sockets, discovery, directLinks, internet)

        @Provides
        @SingleIn(AppScope::class)
        fun provideLeftOutItems(store: LeftOutList, source: MediaSource): LeftOutItems = LeftOutItems(store, source)

        @Provides
        @SingleIn(AppScope::class)
        @Suppress("LongParameterList") // Collaborators, one each, as the constructor takes them.
        fun provideProducerSync(
            identity: IdentityProvider,
            peers: PeerStore,
            ledger: SentLedger,
            source: MediaSource,
            leftOut: LeftOutItems,
            device: DeviceInfo,
            network: Connectivity,
            history: SyncHistory,
            conditions: DeviceConditions,
            @ApplicationScope scope: CoroutineScope,
            testFiles: TestFiles,
        ): ProducerSync = ProducerSync(
            peers,
            ledger,
            source,
            leftOut,
            device,
            network.forSending(identity),
            history,
            conditions,
            scope,
            TurboReceiveSearch(),
            testFiles,
        )

        @Provides
        @SingleIn(AppScope::class)
        @Suppress("LongParameterList") // Collaborators, one each, as the constructor takes them.
        fun provideConsumerReceiver(
            identity: IdentityProvider,
            peers: PeerStore,
            ledger: ReceivedLedger,
            sink: MediaSink,
            device: DeviceInfo,
            network: Connectivity,
            @ApplicationScope scope: CoroutineScope,
            wakeUps: WakeUpCheck,
            linkScreen: LinkScreen,
            testFiles: TestFiles,
        ): ConsumerReceiver = ConsumerReceiver(
            identity,
            peers,
            ledger,
            sink,
            device,
            network,
            scope,
            ConsumerReceiver.PREFERRED_PORT,
            onInternet = wakeUps::checkIfDue,
            linkScreen = linkScreen,
            testFiles = testFiles,
        )
    }
}

/** The system clock, in whichever time zone the phone is in now: it can change while the app runs. */
private object PhoneClock : Clock() {
    override fun getZone(): ZoneId = ZoneId.systemDefault()

    override fun withZone(zone: ZoneId): Clock = system(zone)

    override fun instant(): Instant = Instant.now()
}

/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import android.content.Context
import androidx.work.WorkManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
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
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Lives as long as the app process; sync work outlives individual screens. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
@Suppress("TooManyFunctions") // One binding each.
internal abstract class SyncModule {
    @Binds
    abstract fun bindMediaSource(impl: MediaStoreSource): MediaSource

    @Binds
    abstract fun bindMediaSink(impl: MediaStoreSink): MediaSink

    @Binds
    abstract fun bindSentLedger(impl: RoomSentLedger): SentLedger

    @Binds
    abstract fun bindReceivedLedger(impl: RoomReceivedLedger): ReceivedLedger

    @Binds
    abstract fun bindDeviceInfo(impl: AndroidDeviceInfo): DeviceInfo

    @Binds
    abstract fun bindAddressProvider(impl: AndroidLocalNetwork): AddressProvider

    @Binds
    abstract fun bindLanSockets(impl: AndroidLocalNetwork): LanSockets

    @Binds
    abstract fun bindServiceDiscovery(impl: NsdServiceDiscovery): ServiceDiscovery

    @Binds
    abstract fun bindDeviceConditions(impl: AndroidDeviceConditions): DeviceConditions

    @Binds
    abstract fun bindDirectLinks(impl: TurboLinks): DirectLinks

    @Binds
    abstract fun bindInternetLinks(impl: AndroidInternetLinks): InternetLinks

    @Binds
    abstract fun bindLinkScreen(impl: TurboScreen): LinkScreen

    @Binds
    abstract fun bindPeerBook(impl: PeerStore): PeerBook

    @Binds
    abstract fun bindLeftOutList(impl: LeftOutStore): LeftOutList

    @Binds
    abstract fun bindSyncRecorder(impl: SyncHistory): SyncRecorder

    companion object {
        @Provides
        @Singleton
        @ApplicationScope
        fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        @Provides
        @Singleton
        fun provideWorkManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)

        @Provides
        fun provideClock(): Clock = PhoneClock

        // The providers below mirror the @Inject constructors the classes had: same parameters, same arguments.
        @Provides
        fun provideConnectivity(
            addresses: AddressProvider,
            sockets: LanSockets,
            discovery: ServiceDiscovery,
            directLinks: DirectLinks,
            internet: InternetLinks,
        ): Connectivity = Connectivity(addresses, sockets, discovery, directLinks, internet)

        @Provides
        @Singleton
        fun provideLeftOutItems(store: LeftOutList, source: MediaSource): LeftOutItems = LeftOutItems(store, source)

        @Provides
        @Singleton
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
            identity,
            peers,
            ledger,
            source,
            leftOut,
            device,
            network,
            history,
            conditions,
            scope,
            TurboReceiveSearch(),
            testFiles,
        )

        @Provides
        @Singleton
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

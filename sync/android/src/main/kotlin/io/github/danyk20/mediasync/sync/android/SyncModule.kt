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
import io.github.danyk20.mediasync.core.database.RoomReceivedLedger
import io.github.danyk20.mediasync.core.database.RoomSentLedger
import io.github.danyk20.mediasync.core.media.MediaStoreSink
import io.github.danyk20.mediasync.core.media.MediaStoreSource
import io.github.danyk20.mediasync.sync.engine.MediaSink
import io.github.danyk20.mediasync.sync.engine.MediaSource
import io.github.danyk20.mediasync.sync.engine.ReceivedLedger
import io.github.danyk20.mediasync.sync.engine.SentLedger
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
    abstract fun bindScheduleSource(impl: SettingsScheduleSource): ScheduleSource

    @Binds
    abstract fun bindDeviceConditions(impl: AndroidDeviceConditions): DeviceConditions

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
    }
}

/** The system clock, in whichever time zone the phone is in now: it can change while the app runs. */
private object PhoneClock : Clock() {
    override fun getZone(): ZoneId = ZoneId.systemDefault()

    override fun withZone(zone: ZoneId): Clock = system(zone)

    override fun instant(): Instant = Instant.now()
}

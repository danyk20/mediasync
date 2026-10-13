/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.danyk20.mediasync.core.database.RoomReceivedLedger
import io.github.danyk20.mediasync.core.database.RoomSentLedger
import io.github.danyk20.mediasync.core.media.MediaStoreSink
import io.github.danyk20.mediasync.core.media.MediaStoreSource
import io.github.danyk20.mediasync.sync.engine.MediaSink
import io.github.danyk20.mediasync.sync.engine.MediaSource
import io.github.danyk20.mediasync.sync.engine.ReceivedLedger
import io.github.danyk20.mediasync.sync.engine.SentLedger
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

    companion object {
        @Provides
        @Singleton
        @ApplicationScope
        fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

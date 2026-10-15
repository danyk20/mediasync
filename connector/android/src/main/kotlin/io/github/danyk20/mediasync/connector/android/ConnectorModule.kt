/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector.android

import dagger.Binds
import dagger.MapKey
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import io.github.danyk20.mediasync.connector.BackupConnectorFactory
import io.github.danyk20.mediasync.connector.BackupLedger
import io.github.danyk20.mediasync.connector.BackupOrchestrator
import io.github.danyk20.mediasync.connector.ConnectorDecorators
import io.github.danyk20.mediasync.connector.ConnectorRegistry
import java.time.Clock
import javax.inject.Singleton

/**
 * Registers a connector module's factory under its connector id (docs/PLAN.md §9.1):
 *
 * ```
 * @Binds @IntoMap @ConnectorKey("google-photos-app")
 * abstract fun bind(factory: GooglePhotosAppConnectorFactory): BackupConnectorFactory
 * ```
 */
@MapKey
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
annotation class ConnectorKey(val value: String)

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ConnectorModule {
    /** Every connector module adds its factory; with none, the app simply has nothing to back up to. */
    @Multibinds
    abstract fun connectorFactories(): Map<String, BackupConnectorFactory>

    @Binds
    abstract fun bindLedger(impl: RoomBackupLedger): BackupLedger

    @Binds
    abstract fun bindAccounts(impl: RoomAccountStore): AccountStore

    @Binds
    abstract fun bindContentOpener(impl: ContentResolverOpener): ContentOpener

    @Binds
    abstract fun bindInstalledApps(impl: PackageManagerApps): InstalledApps

    companion object {
        @Provides
        @Singleton
        fun provideRegistry(
            factories: Map<String, @JvmSuppressWildcards BackupConnectorFactory>,
            clock: Clock,
        ): ConnectorRegistry = ConnectorRegistry(factories, ConnectorDecorators(clock))

        @Provides
        fun provideOrchestrator(ledger: BackupLedger, clock: Clock): BackupOrchestrator =
            BackupOrchestrator(ledger, clock)
    }
}

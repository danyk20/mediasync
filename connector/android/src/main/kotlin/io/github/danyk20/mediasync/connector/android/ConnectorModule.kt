/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector.android

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.MapKey
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.github.danyk20.mediasync.connector.BackupConnectorFactory
import io.github.danyk20.mediasync.connector.BackupLedger
import io.github.danyk20.mediasync.connector.BackupOrchestrator
import io.github.danyk20.mediasync.connector.ConnectorDecorators
import io.github.danyk20.mediasync.connector.ConnectorRegistry
import java.time.Clock

/**
 * Registers a connector module's factory under its connector id (docs/PLAN.md §9.1):
 *
 * ```
 * @Binds @IntoMap @ConnectorKey("google-photos-app")
 * val GooglePhotosAppConnectorFactory.bind: BackupConnectorFactory
 * ```
 */
@MapKey(unwrapValue = true)
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.PROPERTY_GETTER)
annotation class ConnectorKey(val value: String)

@ContributesTo(AppScope::class)
@BindingContainer
interface ConnectorModule {
    /** Every connector module adds its factory; with none, the app simply has nothing to back up to. */
    @Multibinds(allowEmpty = true)
    fun connectorFactories(): Map<String, BackupConnectorFactory>

    @Binds
    val RoomBackupLedger.bindLedger: BackupLedger

    @Binds
    val RoomAccountStore.bindAccounts: AccountStore

    @Binds
    val ContentResolverOpener.bindContentOpener: ContentOpener

    @Binds
    val PackageManagerApps.bindInstalledApps: InstalledApps

    companion object {
        @Provides
        @SingleIn(AppScope::class)
        fun provideRegistry(factories: Map<String, BackupConnectorFactory>, clock: Clock): ConnectorRegistry =
            ConnectorRegistry(factories, ConnectorDecorators(clock))

        @Provides
        fun provideOrchestrator(ledger: BackupLedger, clock: Clock): BackupOrchestrator =
            BackupOrchestrator(ledger, clock)
    }
}

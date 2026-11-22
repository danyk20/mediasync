/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.Qualifier
import dev.zacsweers.metro.SingleIn
import io.github.danyk20.mediasync.connector.BackupLedger
import io.github.danyk20.mediasync.connector.BackupOrchestrator
import io.github.danyk20.mediasync.connector.ConnectorDecorators
import io.github.danyk20.mediasync.connector.ConnectorRegistry
import io.github.danyk20.mediasync.connector.folder.FolderConnectorFactory
import io.github.danyk20.mediasync.core.database.BackupDao
import io.github.danyk20.mediasync.core.database.LeftOutStore
import io.github.danyk20.mediasync.core.database.MediaSyncDatabase
import io.github.danyk20.mediasync.core.database.PeerStore
import io.github.danyk20.mediasync.core.database.ReceivedItemDao
import io.github.danyk20.mediasync.core.database.RoomReceivedLedger
import io.github.danyk20.mediasync.core.database.RoomSentLedger
import io.github.danyk20.mediasync.core.datastore.SettingsRepository
import io.github.danyk20.mediasync.core.datastore.createSettingsDataStore
import io.github.danyk20.mediasync.core.diagnostics.FileDiagnosticLog
import io.github.danyk20.mediasync.feature.consumer.CloudflareOAuthClient
import io.github.danyk20.mediasync.platform.desktop.DesktopDeviceConditions
import io.github.danyk20.mediasync.platform.desktop.DesktopIdentityProvider
import io.github.danyk20.mediasync.platform.desktop.DesktopLocalNetwork
import io.github.danyk20.mediasync.platform.desktop.DesktopMediaSink
import io.github.danyk20.mediasync.platform.desktop.DesktopServiceDiscovery
import io.github.danyk20.mediasync.platform.desktop.FileSecretStore
import io.github.danyk20.mediasync.platform.desktop.KeyringSecretStore
import io.github.danyk20.mediasync.platform.desktop.SecretStore
import io.github.danyk20.mediasync.sync.android.AddressProvider
import io.github.danyk20.mediasync.sync.android.Connectivity
import io.github.danyk20.mediasync.sync.android.ConsumerReceiver
import io.github.danyk20.mediasync.sync.android.DeviceConditions
import io.github.danyk20.mediasync.sync.android.DeviceInfo
import io.github.danyk20.mediasync.sync.android.DirectLinks
import io.github.danyk20.mediasync.sync.android.InternetLinks
import io.github.danyk20.mediasync.sync.android.LanSockets
import io.github.danyk20.mediasync.sync.android.LeftOutItems
import io.github.danyk20.mediasync.sync.android.LeftOutList
import io.github.danyk20.mediasync.sync.android.LinkScreen
import io.github.danyk20.mediasync.sync.android.PeerBook
import io.github.danyk20.mediasync.sync.android.ProducerSync
import io.github.danyk20.mediasync.sync.android.ServiceDiscovery
import io.github.danyk20.mediasync.sync.android.SyncRecorder
import io.github.danyk20.mediasync.sync.engine.MediaSink
import io.github.danyk20.mediasync.sync.engine.MediaSource
import io.github.danyk20.mediasync.sync.engine.ReceivedLedger
import io.github.danyk20.mediasync.sync.engine.SentLedger
import io.github.danyk20.mediasync.transport.lan.IdentityProvider
import java.nio.file.Paths
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okio.Path.Companion.toOkioPath

/** Lives as long as the app: sync work outlives individual screens. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * What the shared modules need from the computer, as the Android app's storage and sync modules give the phone: the
 * files under [AppPaths], and the desktop implementation of every port the receiving
 * side and the screens use. The sending side's ports are inert: the desktop only receives (docs/PLAN.md §9, M8).
 */
@ContributesTo(AppScope::class)
@BindingContainer
@Suppress("TooManyFunctions") // One binding each.
interface DesktopModule {
    @Binds
    val DesktopIdentityProvider.bindIdentityProvider: IdentityProvider

    @Binds
    val DesktopLocalNetwork.bindAddressProvider: AddressProvider

    @Binds
    val DesktopLocalNetwork.bindLanSockets: LanSockets

    @Binds
    val DesktopServiceDiscovery.bindServiceDiscovery: ServiceDiscovery

    @Binds
    val DesktopDeviceConditions.bindDeviceConditions: DeviceConditions

    @Binds
    val DesktopMediaSink.bindMediaSink: MediaSink

    @Binds
    val DesktopDeviceInfo.bindDeviceInfo: DeviceInfo

    @Binds
    val NoMediaSource.bindMediaSource: MediaSource

    @Binds
    val RoomSentLedger.bindSentLedger: SentLedger

    @Binds
    val RoomReceivedLedger.bindReceivedLedger: ReceivedLedger

    @Binds
    val PeerStore.bindPeerBook: PeerBook

    @Binds
    val LeftOutStore.bindLeftOutList: LeftOutList

    @Binds
    val DesktopSyncRecorder.bindSyncRecorder: SyncRecorder

    companion object {
        @Provides
        @SingleIn(AppScope::class)
        @ApplicationScope
        fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** No sign-in with Cloudflare on a computer yet: an empty client turns it off. */
        @Provides
        fun provideCloudflareOAuthClient(): CloudflareOAuthClient = CloudflareOAuthClient("", "")

        /** The folder connector is the computer's only backup connector. */
        @Provides
        @SingleIn(AppScope::class)
        fun provideConnectorRegistry(clock: Clock, copies: CopyGate): ConnectorRegistry = ConnectorRegistry(
            mapOf("folder" to GatedCopyFactory(AbsoluteFolderConnectorFactory(FolderConnectorFactory()), copies)),
            ConnectorDecorators(clock),
        )

        /** The copies in progress: quitting waits for them ([FolderBackupService.stop]). */
        @Provides
        @SingleIn(AppScope::class)
        fun provideCopyGate(): CopyGate = CopyGate()

        @Provides
        fun provideBackupLedger(dao: BackupDao, clock: Clock): BackupLedger = DesktopBackupLedger(dao, clock)

        @Provides
        fun provideBackupOrchestrator(ledger: BackupLedger, clock: Clock): BackupOrchestrator =
            BackupOrchestrator(ledger, clock)

        /** The operating system's login entries; none for a copy with its own data folder (checks and tests). */
        @Provides
        @SingleIn(AppScope::class)
        fun provideLoginItem(paths: AppPaths): LoginItem = if (paths.isolated) {
            NoLoginItem
        } else {
            loginItemFor(System.getProperty("os.name").orEmpty(), Paths.get(System.getProperty("user.home").orEmpty()))
        }

        @Provides
        fun provideLauncher(): Launcher = Launcher { installedLauncher() }

        @Provides
        fun provideFolderPicker(): FolderPicker = AwtFolderPicker()

        @Provides
        @SingleIn(AppScope::class)
        @Suppress("LongParameterList") // Collaborators, one each, as the constructor takes them.
        fun provideFolderBackupService(
            settings: SettingsRepository,
            registry: ConnectorRegistry,
            orchestrator: BackupOrchestrator,
            ledger: BackupLedger,
            backups: BackupDao,
            received: ReceivedItemDao,
            paths: AppPaths,
            clock: Clock,
            @ApplicationScope scope: CoroutineScope,
            copies: CopyGate,
        ): FolderBackupService = FolderBackupService(
            settings, registry, orchestrator, ledger, backups, received, paths.inbox, clock, scope, copies,
        )

        @Provides
        fun provideClock(): Clock = Clock.systemDefaultZone()

        @Provides
        @SingleIn(AppScope::class)
        fun provideDataStore(paths: AppPaths): DataStore<Preferences> = createSettingsDataStore {
            paths.settings.also { java.nio.file.Files.createDirectories(it.parent) }.toOkioPath()
        }

        @Provides
        @SingleIn(AppScope::class)
        fun provideDatabase(paths: AppPaths): MediaSyncDatabase {
            java.nio.file.Files.createDirectories(paths.database.parent)
            return Room.databaseBuilder<MediaSyncDatabase>(name = paths.database.toString())
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
        }

        /** The OS credential store, unless the data folder is overridden: then a file in it (tests, checks). */
        @Provides
        @SingleIn(AppScope::class)
        fun provideIdentity(paths: AppPaths, log: FileDiagnosticLog): DesktopIdentityProvider {
            val preferred: SecretStore = if (paths.isolated) {
                FileSecretStore(paths.identity.resolve("password"))
            } else {
                KeyringSecretStore(service = "MediaSync", account = "identity")
            }
            return DesktopIdentityProvider(paths.identity, preferred, log = { println("MediaSync: $it") })
        }

        @Provides
        @SingleIn(AppScope::class)
        fun provideDiagnosticLog(paths: AppPaths, clock: Clock): FileDiagnosticLog =
            FileDiagnosticLog(paths.logs.toFile().also { it.mkdirs() }, clock)

        @Provides
        @SingleIn(AppScope::class)
        fun provideLocalNetwork(): DesktopLocalNetwork = DesktopLocalNetwork()

        @Provides
        @SingleIn(AppScope::class)
        fun provideServiceDiscovery(network: DesktopLocalNetwork): DesktopServiceDiscovery =
            DesktopServiceDiscovery(network)

        @Provides
        fun provideDeviceConditions(): DesktopDeviceConditions = DesktopDeviceConditions()

        @Provides
        @SingleIn(AppScope::class)
        fun provideMediaSink(paths: AppPaths): DesktopMediaSink = DesktopMediaSink(paths.inbox)

        @Provides
        @SingleIn(AppScope::class)
        fun provideConnectivity(
            addresses: AddressProvider,
            sockets: LanSockets,
            discovery: ServiceDiscovery,
        ): Connectivity = Connectivity(addresses, sockets, discovery, DirectLinks.None, InternetLinks.None)

        @Provides
        fun provideLinkScreen(): LinkScreen = LinkScreen.None

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
            history: SyncRecorder,
            conditions: DeviceConditions,
            @ApplicationScope scope: CoroutineScope,
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
            io.github.danyk20.mediasync.sync.android.TurboReceiveSearch(),
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
        ): ConsumerReceiver = ConsumerReceiver(
            identity,
            peers,
            ledger,
            sink,
            device,
            network,
            scope,
            ConsumerReceiver.PREFERRED_PORT,
        )
    }
}

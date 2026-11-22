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
import io.github.danyk20.mediasync.core.database.MediaFingerprintDao
import io.github.danyk20.mediasync.core.database.MediaSyncDatabase
import io.github.danyk20.mediasync.core.database.PeerStore
import io.github.danyk20.mediasync.core.database.ReceivedItemDao
import io.github.danyk20.mediasync.core.database.RoomReceivedLedger
import io.github.danyk20.mediasync.core.database.RoomSentLedger
import io.github.danyk20.mediasync.core.datastore.SettingsRepository
import io.github.danyk20.mediasync.core.datastore.createSettingsDataStore
import io.github.danyk20.mediasync.core.diagnostics.FileDiagnosticLog
import io.github.danyk20.mediasync.core.media.MediaLibrary
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.feature.consumer.CloudflareOAuthClient
import io.github.danyk20.mediasync.platform.desktop.DesktopDeviceConditions
import io.github.danyk20.mediasync.platform.desktop.DesktopIdentityProvider
import io.github.danyk20.mediasync.platform.desktop.DesktopLocalNetwork
import io.github.danyk20.mediasync.platform.desktop.DesktopMediaSink
import io.github.danyk20.mediasync.platform.desktop.DesktopServiceDiscovery
import io.github.danyk20.mediasync.platform.desktop.FileSecretStore
import io.github.danyk20.mediasync.platform.desktop.FolderMediaLibrary
import io.github.danyk20.mediasync.platform.desktop.FolderMediaSource
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath

/** Lives as long as the app: sync work outlives individual screens. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * What the shared modules need from the computer, as the Android app's storage and sync modules give the phone: the
 * files under [AppPaths], and the desktop implementation of every port the receiving and the sending side and the
 * screens use (docs/PLAN.md §9, M8 and M9). What runs depends on the role ([RoleLifecycle]).
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

        /** The folders the user chose; the isolated copies (tests, checks) offer no default folders. */
        @Provides
        @SingleIn(AppScope::class)
        fun provideDesktopFolders(
            settings: SettingsRepository,
            store: DataStore<Preferences>,
            paths: AppPaths,
        ): DesktopFolders = DesktopFolders(
            settings,
            store,
            if (paths.isolated) {
                emptyList()
            } else {
                DesktopFolders.defaultsIn(Paths.get(System.getProperty("user.home").orEmpty()))
            },
        )

        /** What a sending computer sends: the chosen folders and days; fingerprints are kept in the database. */
        @Provides
        @SingleIn(AppScope::class)
        fun provideMediaSource(
            folders: DesktopFolders,
            settings: SettingsRepository,
            dao: MediaFingerprintDao,
        ): MediaSource = FolderMediaSource(
            folders = folders::chosen,
            range = { runBlocking { settings.sendRange.first() } },
            cache = RoomFingerprintCache(dao),
        )

        /** The folder list of the sending computer. */
        @Provides
        @SingleIn(AppScope::class)
        fun provideMediaLibrary(folders: DesktopFolders): MediaLibrary =
            FolderMediaLibrary(folders.changes, folders::candidates)

        @Provides
        @SingleIn(AppScope::class)
        @Suppress("LongParameterList") // Collaborators, one each.
        fun provideReceivingPart(
            receiver: ConsumerReceiver,
            folderBackup: FolderBackupService,
            startAtLogin: DesktopStartAtLogin,
            peers: PeerStore,
            network: DesktopLocalNetwork,
            discovery: DesktopServiceDiscovery,
        ): ReceivingPart =
            ReceivingPart(receiver, folderBackup, startAtLogin, peers, network, discovery, ::logStartProblem)

        @Provides
        @SingleIn(AppScope::class)
        fun provideSendingPart(
            sync: ProducerSync,
            identity: IdentityProvider,
            scheduler: SenderScheduler,
        ): SendingPart = SendingPart(sync::cancel, identity, scheduler, ::logStartProblem)

        /** The schedule the paired receiver told this computer; see [SenderScheduler]. */
        @Provides
        @SingleIn(AppScope::class)
        @Suppress("LongParameterList") // Collaborators, one each.
        fun provideSenderScheduler(
            peers: PeerStore,
            library: MediaLibrary,
            recorder: DesktopSyncRecorder,
            conditions: DeviceConditions,
            sync: ProducerSync,
            clock: Clock,
        ): SenderScheduler = SenderScheduler(
            consumerSchedule = peers.observeConsumer().map { consumer ->
                consumer?.let { it.schedule ?: SyncSchedule.Default }
            },
            folderChanges = library.changes(),
            lastSyncMillis = { recorder.record.value.lastSyncMillis },
            lastHandledMillis = recorder::handledMillis,
            saveHandledMillis = recorder::markHandled,
            conditions = conditions,
            sync = { pauseWhen -> sync.syncAndWait(pauseWhen = pauseWhen) },
            clock = clock,
            log = ::logStartProblem,
        )

        @Provides
        @SingleIn(AppScope::class)
        fun provideRoleLifecycle(
            settings: SettingsRepository,
            receiving: ReceivingPart,
            sending: SendingPart,
        ): RoleLifecycle = RoleLifecycle(
            settings.role,
            mapOf(DeviceRole.CONSUMER to receiving, DeviceRole.PRODUCER to sending),
            ::logStartProblem,
        )

        @Provides
        fun provideDesktopReset(
            settings: DataStore<Preferences>,
            database: MediaSyncDatabase,
            history: DesktopSyncRecorder,
        ): DesktopReset = DesktopReset(settings, database, history)

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

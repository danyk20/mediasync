/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A kind of backup destination (docs/PLAN.md §9.2). Each connector module registers one under its id, and the app
 * finds them at runtime through [ConnectorRegistry]: adding a cloud means adding a module, nothing else.
 */
interface BackupConnectorFactory {
    val descriptor: ConnectorDescriptor

    /** What the user can set for an account; drives the generic settings screen. */
    val settingsSchema: SettingsSchema get() = SettingsSchema.Empty

    fun authenticator(): ConnectorAuthenticator = ConnectorAuthenticator.None

    /** A connector backing up to [account]. */
    fun create(account: ConnectorAccount): BackupConnector
}

/**
 * Backs items up to one account. Implementations report failures as results; only cancellation is thrown. The
 * shared decorators add retries, rate limits, quota tracking and logging around every connector
 * ([ConnectorDecorators]), so connectors don't repeat them.
 */
interface BackupConnector {
    val descriptor: ConnectorDescriptor

    suspend fun authState(): AuthState

    /**
     * Backs [item] up. Must be idempotent per content id: an item uploaded before is reported as uploaded, not
     * stored twice. [item] is already preprocessed (docs/PLAN.md §9.1): its bytes are what the backup should hold.
     */
    suspend fun upload(item: BackupItem, progress: ProgressSink = ProgressSink.None): UploadResult

    /**
     * Checks that the service still has what [receipts] say, immediately before anything is removed from the
     * phone: a copy found missing blocks that (docs/PLAN.md §9.4). Every receipt gets a result.
     */
    suspend fun verify(receipts: List<BackupReceipt>): Map<ContentId, VerifyResult>

    /** Finds items on the service by content hash, without receipts ([Capability.HASH_LOOKUP]); remote ids. */
    suspend fun lookup(ids: List<ContentId>): Map<ContentId, String> = emptyMap()
}

/**
 * The kind of connector where another app on this phone does the backup ([Capability.DELEGATED_TO_DEVICE_APP]):
 * in v1.0, a small *connector app profile* (docs/PLAN.md §9).
 */
interface DeviceAppConnectorFactory : BackupConnectorFactory {
    val profile: DeviceAppProfile

    override fun create(account: ConnectorAccount): DeviceAppConnector
}

/** Backs up through another app on this phone: [upload] checks the item is where that app backs up. */
interface DeviceAppConnector : BackupConnector {
    /**
     * Which of [receipts] the other app has removed from this phone. Its cleanup tools only remove what it backed up,
     * so these count as backed up (docs/PLAN.md §9.3.1). Items in the system trash aren't among them: they may have
     * been deleted by hand.
     */
    suspend fun removedFromPhone(receipts: List<BackupReceipt>): Set<ContentId>

    /** Emits when files in the backed-up folder may have changed or gone, so their state is checked again. */
    fun folderChanges(): Flow<Unit> = emptyFlow()
}

/** How the app that backs up is used: where files go, what to set up in it, and where to free up space. */
data class DeviceAppProfile(
    /** The Android package of the app, such as `com.google.android.apps.photos`. */
    val packageName: String,
    /** The app's name: a brand, the same in every language. */
    val appName: String,
    /** The folder it backs up, as a MediaStore relative path such as `DCIM/MediaSync`. */
    val folder: String,
    val layout: FolderLayout,
    /** What the user sets up in the app, once, in this order. */
    val setup: List<SetupStep>,
    /** Where the user frees up space once the app has backed things up. MediaSync never deletes these files. */
    val cleanup: List<CleanupShortcut>,
)

/** How received files are laid out in the folder (docs/PLAN.md §2.2). */
enum class FolderLayout {
    /** All in one folder: one backup switch in the other app covers every sending phone, now and later. */
    SINGLE_FOLDER,

    /** A folder per sending phone and album, `<folder>/<Producer>/<Album>/`, for uploading connectors. */
    BY_PRODUCER_AND_ALBUM,
}

/** One thing the user sets up in the other app, guided by MediaSync (docs/PLAN.md §9.3.1). */
enum class SetupStep {
    /** Turn on backup for MediaSync's folder. */
    BACK_UP_FOLDER,

    /** Back up in *Storage saver* quality, which Pixel 2 to 5 phones get for free (docs/PLAN.md §1.5). */
    STORAGE_SAVER,
}

/** A place where backed-up items are removed from the phone, by tools that only remove what's backed up. */
enum class CleanupShortcut {
    /** The app's own *Free up space*. */
    APP_FREE_UP_SPACE,

    /** The phone's automatic cleanup, such as a Pixel's *Smart Storage*. */
    SYSTEM_SMART_STORAGE,
}

/** A connector wrapped around [inner] to add something every connector needs, such as retries. */
interface DecoratedConnector : BackupConnector {
    val inner: BackupConnector
}

/** The connector inside any decorators. */
fun BackupConnector.undecorated(): BackupConnector =
    generateSequence(this) { (it as? DecoratedConnector)?.inner }.last()

/** The [DeviceAppConnector] inside any decorators, or `null` for an uploading connector. */
fun BackupConnector.deviceApp(): DeviceAppConnector? = undecorated() as? DeviceAppConnector

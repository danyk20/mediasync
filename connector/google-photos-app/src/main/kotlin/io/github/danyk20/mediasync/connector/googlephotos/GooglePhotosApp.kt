/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector.googlephotos

import io.github.danyk20.mediasync.connector.AuthState
import io.github.danyk20.mediasync.connector.BackupItem
import io.github.danyk20.mediasync.connector.BackupReceipt
import io.github.danyk20.mediasync.connector.Capability
import io.github.danyk20.mediasync.connector.CleanupShortcut
import io.github.danyk20.mediasync.connector.ConnectorAccount
import io.github.danyk20.mediasync.connector.ConnectorDescriptor
import io.github.danyk20.mediasync.connector.ConnectorId
import io.github.danyk20.mediasync.connector.ConnectorLimits
import io.github.danyk20.mediasync.connector.ContentId
import io.github.danyk20.mediasync.connector.DeviceAppConnector
import io.github.danyk20.mediasync.connector.DeviceAppConnectorFactory
import io.github.danyk20.mediasync.connector.DeviceAppProfile
import io.github.danyk20.mediasync.connector.FolderLayout
import io.github.danyk20.mediasync.connector.ProgressSink
import io.github.danyk20.mediasync.connector.SetupStep
import io.github.danyk20.mediasync.connector.SkipReason
import io.github.danyk20.mediasync.connector.UploadResult
import io.github.danyk20.mediasync.connector.VerifyResult
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/**
 * Backup through the Google Photos app on the receiving phone (docs/PLAN.md §9.3.1), the default on Pixel 2 to 5:
 * received files go to `DCIM/MediaSync/`, which the app backs up in *Storage saver* quality for free (§1.5).
 * MediaSync uploads nothing and deletes nothing: it checks that each file is where the app backs it up, and notices
 * when the app's *Free up space*, or the phone's *Smart Storage*, removes it.
 */
class GooglePhotosAppConnectorFactory @Inject internal constructor(private val folder: PhoneFolder) :
    DeviceAppConnectorFactory {

    override val descriptor: ConnectorDescriptor get() = DESCRIPTOR

    override val profile: DeviceAppProfile get() = PROFILE

    override fun create(account: ConnectorAccount): GooglePhotosAppConnector = GooglePhotosAppConnector(folder)

    companion object {
        val ID = ConnectorId("google-photos-app")

        /** The folder the receiving phone stores into, as MediaStore names it. */
        const val FOLDER = "DCIM/MediaSync"

        val DESCRIPTOR = ConnectorDescriptor(
            id = ID,
            displayName = "Google Photos",
            capabilities = setOf(
                Capability.VIDEO,
                // The app reads dates and places from the stored file: those without get one first (§6.2).
                Capability.METADATA_FROM_FILE_ONLY,
                Capability.DELEGATED_TO_DEVICE_APP,
            ),
            // What the app backs up, per Google's help: larger files stay on the phone only.
            limits = ConnectorLimits(maxPhotoBytes = 200 * MEGABYTE, maxVideoBytes = 10 * GIGABYTE),
        )

        val PROFILE = DeviceAppProfile(
            packageName = "com.google.android.apps.photos",
            appName = "Google Photos",
            folder = FOLDER,
            layout = FolderLayout.SINGLE_FOLDER,
            setup = listOf(SetupStep.BACK_UP_FOLDER, SetupStep.STORAGE_SAVER),
            cleanup = listOf(CleanupShortcut.APP_FREE_UP_SPACE, CleanupShortcut.SYSTEM_SMART_STORAGE),
        )

        private const val MEGABYTE = 1_000_000L
        private const val GIGABYTE = 1_000_000_000L
    }
}

/** One account's backups through the Google Photos app: there's only ever the one, the app's own. */
class GooglePhotosAppConnector internal constructor(private val folder: PhoneFolder) : DeviceAppConnector {

    override val descriptor: ConnectorDescriptor get() = GooglePhotosAppConnectorFactory.DESCRIPTOR

    override suspend fun authState(): AuthState = AuthState.NotNeeded

    /** The item is handed over once it's in the backed-up folder: the app picks it up on its own (§9.6). */
    override suspend fun upload(item: BackupItem, progress: ProgressSink): UploadResult =
        when (val file = folder.find(item.location)) {
            PhoneFile.Gone, PhoneFile.Unreadable -> UploadResult.Skipped(SkipReason.NOT_ON_PHONE)

            is PhoneFile.Found -> when {
                file.trashed -> UploadResult.Skipped(SkipReason.NOT_ON_PHONE)

                !file.inFolder(GooglePhotosAppConnectorFactory.FOLDER) ->
                    UploadResult.Skipped(SkipReason.OUTSIDE_BACKUP_FOLDER)

                else -> UploadResult.HandedOver(item.location)
            }
        }

    /** The app does the backup, so MediaSync can't check it. */
    override suspend fun verify(receipts: List<BackupReceipt>): Map<ContentId, VerifyResult> =
        receipts.associate { it.contentId to VerifyResult.UNSUPPORTED }

    /**
     * Files gone from the phone, and not in its trash: Google's cleanup tools only remove what the app backed up
     * (§9.3.1). A trashed file may have been deleted by hand, and one MediaSync can't read tells nothing.
     */
    override suspend fun removedFromPhone(receipts: List<BackupReceipt>): Set<ContentId> =
        receipts.filter { folder.find(it.remoteId) == PhoneFile.Gone }.map { it.contentId }.toSet()

    override fun folderChanges(): Flow<Unit> = folder.changes()
}

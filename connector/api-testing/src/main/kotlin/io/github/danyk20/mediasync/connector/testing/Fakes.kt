/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector.testing

import io.github.danyk20.mediasync.connector.AccountId
import io.github.danyk20.mediasync.connector.AuthState
import io.github.danyk20.mediasync.connector.BackupConnector
import io.github.danyk20.mediasync.connector.BackupConnectorFactory
import io.github.danyk20.mediasync.connector.BackupItem
import io.github.danyk20.mediasync.connector.BackupLedger
import io.github.danyk20.mediasync.connector.BackupReceipt
import io.github.danyk20.mediasync.connector.BackupState
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
import java.io.ByteArrayInputStream
import java.util.Collections
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** The content id numbered [n]: 64 hex digits. */
fun contentId(n: Int): ContentId = ContentId(n.toString(HEX).padStart(CONTENT_ID_LENGTH, '0'))

/** A received photo (or, with a `video/` [mimeType], video) numbered [n], stored at a MediaStore-like location. */
fun backupItem(
    n: Int,
    bytes: ByteArray = "photo $n".encodeToByteArray(),
    mimeType: String = "image/jpeg",
    location: String = "content://media/external_primary/images/media/$n",
): BackupItem = BackupItem(
    contentId = contentId(n),
    displayName = "IMG_$n.jpg",
    mimeType = mimeType,
    size = bytes.size.toLong(),
    location = location,
    content = { ByteArrayInputStream(bytes) },
)

fun descriptor(
    id: String = "test-cloud",
    capabilities: Set<Capability> = setOf(Capability.VIDEO, Capability.VERIFY),
    limits: ConnectorLimits = ConnectorLimits.None,
): ConnectorDescriptor = ConnectorDescriptor(ConnectorId(id), "Test cloud", capabilities, limits)

fun account(connector: String = "test-cloud", id: String = connector): ConnectorAccount =
    ConnectorAccount(AccountId(id), ConnectorId(connector), "Test account", createdAtMillis = 0)

/**
 * A connector whose upload answers come from [answer] (by default: uploaded), recording what it was asked. Thread
 * safe, so it also serves concurrent tests.
 */
class FakeConnector(
    override val descriptor: ConnectorDescriptor = descriptor(),
    var answer: suspend (BackupItem) -> UploadResult = { UploadResult.Uploaded("remote-${it.contentId}", it.size) },
) : BackupConnector {
    val uploads: MutableList<ContentId> = Collections.synchronizedList(mutableListOf())
    val verified: MutableList<List<BackupReceipt>> = Collections.synchronizedList(mutableListOf())
    var lookups = 0
        private set
    var verifyAnswer: (List<BackupReceipt>) -> Map<ContentId, VerifyResult> =
        { receipts -> receipts.associate { it.contentId to VerifyResult.PRESENT } }

    override suspend fun authState(): AuthState = AuthState.SignedIn

    override suspend fun upload(item: BackupItem, progress: ProgressSink): UploadResult {
        uploads += item.contentId
        return answer(item)
    }

    override suspend fun verify(receipts: List<BackupReceipt>): Map<ContentId, VerifyResult> {
        verified += receipts
        return verifyAnswer(receipts)
    }

    override suspend fun lookup(ids: List<ContentId>): Map<ContentId, String> {
        lookups++
        return ids.associateWith { "remote-$it" }
    }
}

class FakeConnectorFactory(override val descriptor: ConnectorDescriptor = descriptor()) : BackupConnectorFactory {
    val created = mutableListOf<ConnectorAccount>()

    override fun create(account: ConnectorAccount): FakeConnector {
        created += account
        return FakeConnector(descriptor)
    }
}

/** The profile of a made-up app that backs up `DCIM/MediaSync`. */
val TestAppProfile = DeviceAppProfile(
    packageName = "com.example.photos",
    appName = "Example Photos",
    folder = "DCIM/MediaSync",
    layout = FolderLayout.SINGLE_FOLDER,
    setup = listOf(SetupStep.BACK_UP_FOLDER),
    cleanup = listOf(CleanupShortcut.APP_FREE_UP_SPACE),
)

/**
 * Another app on a pretend phone that backs up the items [onPhone] holds (by location). Tests move items to the
 * [trash] or remove them, and tell [changes] about it.
 */
class FakeDeviceAppConnector(
    override val descriptor: ConnectorDescriptor = descriptor(
        id = "test-app",
        capabilities = setOf(Capability.VIDEO, Capability.DELEGATED_TO_DEVICE_APP),
    ),
) : DeviceAppConnector {
    val onPhone: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    val trash: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    var checks = 0
        private set

    override suspend fun authState(): AuthState = AuthState.NotNeeded

    override suspend fun upload(item: BackupItem, progress: ProgressSink): UploadResult =
        if (item.location in onPhone) {
            UploadResult.HandedOver(item.location)
        } else {
            UploadResult.Skipped(SkipReason.NOT_ON_PHONE)
        }

    override suspend fun verify(receipts: List<BackupReceipt>): Map<ContentId, VerifyResult> =
        receipts.associate { it.contentId to VerifyResult.UNSUPPORTED }

    override suspend fun removedFromPhone(receipts: List<BackupReceipt>): Set<ContentId> {
        checks++
        return receipts.filter { it.remoteId !in onPhone && it.remoteId !in trash }.map { it.contentId }.toSet()
    }

    override fun folderChanges(): Flow<Unit> = changes
}

class FakeDeviceAppFactory(
    val connector: FakeDeviceAppConnector = FakeDeviceAppConnector(),
    override val profile: DeviceAppProfile = TestAppProfile,
) : DeviceAppConnectorFactory {
    override val descriptor: ConnectorDescriptor get() = connector.descriptor

    override fun create(account: ConnectorAccount): DeviceAppConnector = connector
}

/** The backup ledger in memory: [received] holds what arrived, oldest first. */
class InMemoryBackupLedger : BackupLedger {
    val received: MutableList<BackupItem> = Collections.synchronizedList(mutableListOf())
    private val receipts = LinkedHashMap<Pair<ContentId, AccountId>, BackupReceipt>()

    /** The receipt [account] has for [id], if any. */
    fun receipt(id: ContentId, account: AccountId): BackupReceipt? = synchronized(this) { receipts[id to account] }

    override suspend fun pending(account: AccountId, limit: Int): List<BackupItem> = synchronized(this) {
        received.filter { (it.contentId to account) !in receipts }.take(limit)
    }

    override suspend fun record(receipts: List<BackupReceipt>) = synchronized(this) {
        receipts.forEach { this.receipts[it.contentId to it.accountId] = it }
    }

    override suspend fun receipts(account: AccountId, state: BackupState): List<BackupReceipt> = synchronized(this) {
        receipts.values.filter { it.accountId == account && it.state == state }
    }
}

private const val HEX = 16
private const val CONTENT_ID_LENGTH = 64

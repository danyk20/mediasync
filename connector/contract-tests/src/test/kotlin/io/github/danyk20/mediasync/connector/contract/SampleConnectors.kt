/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector.contract

import io.github.danyk20.mediasync.connector.AuthKind
import io.github.danyk20.mediasync.connector.AuthState
import io.github.danyk20.mediasync.connector.BackupConnector
import io.github.danyk20.mediasync.connector.BackupConnectorFactory
import io.github.danyk20.mediasync.connector.BackupItem
import io.github.danyk20.mediasync.connector.BackupReceipt
import io.github.danyk20.mediasync.connector.Capability
import io.github.danyk20.mediasync.connector.ConnectorAccount
import io.github.danyk20.mediasync.connector.ConnectorAuthenticator
import io.github.danyk20.mediasync.connector.ConnectorDescriptor
import io.github.danyk20.mediasync.connector.ConnectorError
import io.github.danyk20.mediasync.connector.ConnectorId
import io.github.danyk20.mediasync.connector.ContentId
import io.github.danyk20.mediasync.connector.DeviceAppConnector
import io.github.danyk20.mediasync.connector.DeviceAppConnectorFactory
import io.github.danyk20.mediasync.connector.DeviceAppProfile
import io.github.danyk20.mediasync.connector.ProgressSink
import io.github.danyk20.mediasync.connector.SkipReason
import io.github.danyk20.mediasync.connector.UploadResult
import io.github.danyk20.mediasync.connector.VerifyResult
import io.github.danyk20.mediasync.connector.testing.TestAppProfile
import java.io.IOException

// Sample connectors for the kit's own tests: well-behaved ones, which pass it, and each way of misbehaving it
// must catch. They also show what a connector and its contract test look like.

val CloudDescriptor = ConnectorDescriptor(
    ConnectorId("sample-cloud"),
    "Sample cloud",
    setOf(Capability.VIDEO, Capability.VERIFY),
    authKind = AuthKind.API_KEY,
)

val AppDescriptor = ConnectorDescriptor(
    ConnectorId("sample-app"),
    "Sample app",
    setOf(Capability.VIDEO, Capability.DELEGATED_TO_DEVICE_APP),
)

/** A photo service in memory: each copy under its remote id, with the content id it was uploaded as. */
class SampleCloud {
    val copies = LinkedHashMap<String, Pair<ContentId, ByteArray>>()
    var nextFailure: ServiceFailure? = null
    var signedIn = true
}

/** Backs up to a [SampleCloud]; subclasses misbehave. */
open class SampleCloudConnector(
    protected val cloud: SampleCloud,
    override val descriptor: ConnectorDescriptor = CloudDescriptor,
) : BackupConnector {
    override suspend fun authState(): AuthState = if (cloud.signedIn) AuthState.SignedIn else AuthState.SignInNeeded

    override suspend fun upload(item: BackupItem, progress: ProgressSink): UploadResult {
        cloud.nextFailure?.let { failure ->
            cloud.nextFailure = null
            return UploadResult.Failed(errorFor(failure))
        }
        existing(item)?.let { return UploadResult.Uploaded(it, bytesSent = 0) }
        val bytes = item.content.open().use { it.readBytes() }
        progress.onProgress(bytes.size / 2L, item.size)
        progress.onProgress(bytes.size.toLong(), item.size)
        val remoteId = "remote-${cloud.copies.size}"
        cloud.copies[remoteId] = item.contentId to stored(bytes)
        return UploadResult.Uploaded(remoteId, bytes.size.toLong())
    }

    override suspend fun verify(receipts: List<BackupReceipt>): Map<ContentId, VerifyResult> =
        receipts.associate { receipt ->
            receipt.contentId to if (receipt.remoteId in cloud.copies) VerifyResult.PRESENT else VerifyResult.MISSING
        }

    protected open fun existing(item: BackupItem): String? =
        cloud.copies.entries.firstOrNull { it.value.first == item.contentId }?.key

    protected open fun stored(bytes: ByteArray): ByteArray = bytes

    protected open fun errorFor(failure: ServiceFailure): ConnectorError = when (failure) {
        ServiceFailure.RATE_LIMITED -> ConnectorError.RateLimited(retryAfterMillis = 1_000)
        ServiceFailure.SERVER_ERROR -> ConnectorError.Transient("HTTP 503")
        ServiceFailure.QUOTA_EXCEEDED -> ConnectorError.QuotaExceeded()
        ServiceFailure.SIGN_IN_EXPIRED -> ConnectorError.SignInNeeded.also { cloud.signedIn = false }
    }
}

/** A phone's folder that another app backs up, by location; trashed files stay, marked. */
class SampleFolder {
    val files = LinkedHashMap<String, ByteArray>()
    val trash = mutableSetOf<String>()
}

open class SampleAppConnector(protected val folder: SampleFolder) : DeviceAppConnector {
    override val descriptor: ConnectorDescriptor get() = AppDescriptor

    override suspend fun authState(): AuthState = AuthState.NotNeeded

    override suspend fun upload(item: BackupItem, progress: ProgressSink): UploadResult =
        if (item.location in folder.files && item.location !in folder.trash) {
            UploadResult.HandedOver(item.location)
        } else {
            UploadResult.Skipped(SkipReason.NOT_ON_PHONE)
        }

    override suspend fun verify(receipts: List<BackupReceipt>): Map<ContentId, VerifyResult> =
        receipts.associate { it.contentId to VerifyResult.UNSUPPORTED }

    override suspend fun removedFromPhone(receipts: List<BackupReceipt>): Set<ContentId> =
        receipts.filter { it.remoteId !in folder.files }.map { it.contentId }.toSet()
}

fun cloudFactory(descriptor: ConnectorDescriptor = CloudDescriptor, create: () -> BackupConnector) =
    object : BackupConnectorFactory {
        override val descriptor: ConnectorDescriptor = descriptor

        override fun authenticator(): ConnectorAuthenticator = ApiKeyAuthenticator

        override fun create(account: ConnectorAccount): BackupConnector = create()
    }

/** Signs in with a key the user pasted. */
object ApiKeyAuthenticator : ConnectorAuthenticator {
    override val kind: AuthKind get() = AuthKind.API_KEY

    override suspend fun state(account: ConnectorAccount): AuthState = AuthState.SignedIn

    override suspend fun signOut(account: ConnectorAccount) = Unit
}

fun appFactory(create: () -> DeviceAppConnector) = object : DeviceAppConnectorFactory {
    override val descriptor: ConnectorDescriptor = AppDescriptor
    override val profile: DeviceAppProfile = TestAppProfile

    override fun create(account: ConnectorAccount): DeviceAppConnector = create()
}

/** The kit against a well-behaved uploading connector; subclasses swap in misbehaving ones. */
open class SampleCloudContract : BackupConnectorContractTest() {
    protected val cloud = SampleCloud()

    protected open fun newConnector(): BackupConnector = SampleCloudConnector(cloud)

    override val factory: BackupConnectorFactory by lazy { cloudFactory { newConnector() } }

    override fun store(item: BackupItem) = Unit

    override fun copiesAtDestination(item: BackupItem): List<ByteArray> =
        cloud.copies.values.filter { it.first == item.contentId }.map { it.second }

    override fun deleteAtDestination(receipt: BackupReceipt): Boolean = cloud.copies.remove(receipt.remoteId) != null

    override fun failNextRequest(failure: ServiceFailure): Boolean {
        cloud.nextFailure = failure
        return true
    }
}

/** The kit against a well-behaved connector for another app; subclasses swap in misbehaving ones. */
open class SampleAppContract : BackupConnectorContractTest() {
    protected val folder = SampleFolder()

    protected open fun newConnector(): DeviceAppConnector = SampleAppConnector(folder)

    override val factory: BackupConnectorFactory by lazy { appFactory { newConnector() } }

    override fun store(item: BackupItem) {
        folder.files[item.location] = item.content.open().use { it.readBytes() }
    }

    override fun copiesAtDestination(item: BackupItem): List<ByteArray> = listOfNotNull(folder.files[item.location])

    override fun removeFromPhone(item: BackupItem): Boolean = folder.files.remove(item.location) != null

    override fun trashOnPhone(item: BackupItem): Boolean = folder.trash.add(item.location)
}

/** Stores a new copy for every upload. */
class DuplicatingCloudContract : SampleCloudContract() {
    override fun newConnector(): BackupConnector = object : SampleCloudConnector(cloud) {
        override fun existing(item: BackupItem): String? = null
    }
}

/** Changes a byte on the way, as re-encoding or stripping details would. */
class AlteringCloudContract : SampleCloudContract() {
    override fun newConnector(): BackupConnector = object : SampleCloudConnector(cloud) {
        override fun stored(bytes: ByteArray): ByteArray =
            bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
    }
}

/** Throws when the service says "too many requests", instead of reporting it. */
class ThrowingCloudContract : SampleCloudContract() {
    override fun newConnector(): BackupConnector = object : SampleCloudConnector(cloud) {
        override fun errorFor(failure: ServiceFailure): ConnectorError =
            if (failure == ServiceFailure.RATE_LIMITED) throw IOException("HTTP 429") else super.errorFor(failure)
    }
}

/** Says every copy is there, without asking. */
class OverclaimingCloudContract : SampleCloudContract() {
    override fun newConnector(): BackupConnector = object : SampleCloudConnector(cloud) {
        override suspend fun verify(receipts: List<BackupReceipt>) =
            receipts.associate { it.contentId to VerifyResult.PRESENT }
    }
}

/** Its connectors describe themselves differently from their factory. */
class MismatchedCloudContract : SampleCloudContract() {
    override fun newConnector(): BackupConnector =
        SampleCloudConnector(cloud, CloudDescriptor.copy(displayName = "Other"))
}

/** Hands over what isn't on the phone. */
class CarelessAppContract : SampleAppContract() {
    override fun newConnector(): DeviceAppConnector = object : SampleAppConnector(folder) {
        override suspend fun upload(item: BackupItem, progress: ProgressSink) = UploadResult.HandedOver(item.location)
    }
}

/** Claims the other app's copies are there, which it can't know. */
class ClaimingAppContract : SampleAppContract() {
    override fun newConnector(): DeviceAppConnector = object : SampleAppConnector(folder) {
        override suspend fun verify(receipts: List<BackupReceipt>) =
            receipts.associate { it.contentId to VerifyResult.PRESENT }
    }
}

/** Counts trashed items as backed up and removed. */
class TrashCountingAppContract : SampleAppContract() {
    override fun newConnector(): DeviceAppConnector = object : SampleAppConnector(folder) {
        override suspend fun removedFromPhone(receipts: List<BackupReceipt>): Set<ContentId> =
            receipts.filter { it.remoteId !in folder.files || it.remoteId in folder.trash }
                .map { it.contentId }
                .toSet()
    }
}

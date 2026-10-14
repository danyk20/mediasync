/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector.contract

import io.github.danyk20.mediasync.connector.AccountId
import io.github.danyk20.mediasync.connector.AuthKind
import io.github.danyk20.mediasync.connector.AuthState
import io.github.danyk20.mediasync.connector.BackupConnector
import io.github.danyk20.mediasync.connector.BackupConnectorFactory
import io.github.danyk20.mediasync.connector.BackupItem
import io.github.danyk20.mediasync.connector.BackupReceipt
import io.github.danyk20.mediasync.connector.BackupState
import io.github.danyk20.mediasync.connector.Capability
import io.github.danyk20.mediasync.connector.ConnectorAccount
import io.github.danyk20.mediasync.connector.ConnectorError
import io.github.danyk20.mediasync.connector.ContentId
import io.github.danyk20.mediasync.connector.DeviceAppConnector
import io.github.danyk20.mediasync.connector.DeviceAppConnectorFactory
import io.github.danyk20.mediasync.connector.ProgressSink
import io.github.danyk20.mediasync.connector.UploadResult
import io.github.danyk20.mediasync.connector.VerifyResult
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Test

/** A failure a connector's fake service can be told to answer its next request with. */
enum class ServiceFailure {
    /** Too many requests (HTTP 429). */
    RATE_LIMITED,

    /** A server error (HTTP 5xx), or the network dropping. */
    SERVER_ERROR,

    /** The daily allowance is used up. */
    QUOTA_EXCEEDED,

    /** The sign-in expired or was revoked. */
    SIGN_IN_EXPIRED,
}

/**
 * What every connector must do (docs/PLAN.md §9.5, §13). A connector's tests subclass this with a fake service, or
 * a fake phone for another app's connector, and every check runs against it. A check that doesn't apply, such as
 * one for uploading connectors, or a failure the fake can't produce, is skipped rather than passed.
 *
 * The checks: the descriptors agree; what reaches the destination is the item byte for byte, so its date and place
 * survive (photos and videos); uploading twice keeps one copy; progress stays within the item; failures are
 * reported as results that say whether to retry; checks of the service report missing copies (and another app's
 * connector never claims to check); another app's connector counts only what that app removed.
 */
@Suppress("TooManyFunctions") // One function per check, and one hook per thing a fake may support.
abstract class BackupConnectorContractTest {

    /** Creates connectors wired to this test's fake. A fresh one for each check. */
    protected abstract val factory: BackupConnectorFactory

    /** Makes [item] ready to back up, as the receiving phone would have stored it. */
    protected abstract fun store(item: BackupItem)

    /**
     * Every copy of [item] at the destination: on the service for an uploading connector, or in the folder the other
     * app backs up for another app's connector. Each entry is one copy's bytes.
     */
    protected abstract fun copiesAtDestination(item: BackupItem): List<ByteArray>

    /** Deletes the service's copy behind [receipt], as the user could on its website; `false` if the fake can't. */
    protected open fun deleteAtDestination(receipt: BackupReceipt): Boolean = false

    /** Removes [item] from the phone as the other app's cleanup would; `false` if the fake can't. */
    protected open fun removeFromPhone(item: BackupItem): Boolean = false

    /** Moves [item] to the phone's trash, as deleting it by hand would; `false` if the fake can't. */
    protected open fun trashOnPhone(item: BackupItem): Boolean = false

    /** Makes the service answer its next request with [failure]; `false` if the fake can't. */
    protected open fun failNextRequest(failure: ServiceFailure): Boolean = false

    protected val account: ConnectorAccount by lazy {
        ConnectorAccount(AccountId("contract-test"), factory.descriptor.id, "Contract test", createdAtMillis = 0)
    }

    protected val connector: BackupConnector by lazy { factory.create(account) }

    private val delegated: Boolean get() = factory.descriptor.delegated

    @Test
    fun theDescriptorsAgree() = runTest {
        connector.descriptor shouldBe factory.descriptor
        factory.authenticator().kind shouldBe factory.descriptor.authKind
        withClue("Another app's connector has a DeviceAppConnectorFactory and a DeviceAppConnector, and only it") {
            (factory is DeviceAppConnectorFactory) shouldBe delegated
            (connector is DeviceAppConnector) shouldBe delegated
        }
        if (factory.descriptor.authKind == AuthKind.NONE) connector.authState() shouldBe AuthState.NotNeeded
    }

    @Test
    fun aPhotoReachesTheDestinationByteForByte() = runTest {
        val photo = photo()
        store(photo)

        backUp(photo)

        withClue("The copy must keep every byte, and so the photo's date and place") {
            copiesAtDestination(photo).map { it.toList() } shouldBe listOf(photo.bytes().toList())
        }
    }

    @Test
    fun aVideoReachesTheDestinationByteForByte() = runTest {
        assumeTrue("The connector takes no videos", Capability.VIDEO in factory.descriptor)
        val video = video()
        store(video)

        backUp(video)

        copiesAtDestination(video).map { it.toList() } shouldBe listOf(video.bytes().toList())
    }

    @Test
    fun backingUpTwiceKeepsOneCopy() = runTest {
        val photo = photo()
        store(photo)

        val first = backUp(photo)
        val second = backUp(photo)

        copiesAtDestination(photo) shouldHaveSize 1
        remoteIdOf(second) shouldBe remoteIdOf(first)
    }

    @Test
    fun progressStaysWithinTheItem() = runTest {
        val photo = photo()
        store(photo)
        val reports = mutableListOf<Pair<Long, Long>>()

        connector.upload(photo, ProgressSink { done, total -> reports += done to total }).shouldBeSuccessful()

        reports.forEach { (done, total) ->
            total shouldBe photo.size
            done shouldBeLessThanOrEqual total
        }
        reports.map { it.first } shouldBe reports.map { it.first }.sorted()
    }

    @Test
    fun checksFindCopiesThatWentMissing() = runTest {
        val kept = photo(1)
        val deleted = photo(2)
        listOf(kept, deleted).forEach(::store)
        val receipts = listOf(kept, deleted).map { receiptFor(it, backUp(it)) }

        if (Capability.VERIFY !in factory.descriptor) {
            withClue("A connector that can't check must never say a copy is there") {
                connector.verify(receipts) shouldBe receipts.associate { it.contentId to VerifyResult.UNSUPPORTED }
            }
            return@runTest
        }
        assumeTrue("The fake can't delete copies", deleteAtDestination(receipts[1]))

        connector.verify(receipts) shouldBe mapOf(
            kept.contentId to VerifyResult.PRESENT,
            deleted.contentId to VerifyResult.MISSING,
        )
    }

    @Test
    fun aRateLimitIsReportedForRetrying() = failureIsReported(ServiceFailure.RATE_LIMITED) { error ->
        error.shouldBeInstanceOf<ConnectorError.RateLimited>()
    }

    @Test
    fun aServerErrorIsReportedForRetrying() = failureIsReported(ServiceFailure.SERVER_ERROR) { error ->
        error.shouldBeInstanceOf<ConnectorError.Transient>()
    }

    @Test
    fun aUsedUpAllowanceIsReported() = failureIsReported(ServiceFailure.QUOTA_EXCEEDED) { error ->
        error.shouldBeInstanceOf<ConnectorError.QuotaExceeded>()
    }

    @Test
    fun anExpiredSignInIsReported() = failureIsReported(ServiceFailure.SIGN_IN_EXPIRED) { error ->
        error shouldBe ConnectorError.SignInNeeded
        connector.authState() shouldBe AuthState.SignInNeeded
    }

    @Test
    fun anotherAppsConnectorSkipsWhatIsntOnThePhone() = runTest {
        assumeTrue("Only for another app's connector", delegated)

        connector.upload(photo()).shouldBeInstanceOf<UploadResult.Skipped>()
    }

    @Test
    fun anotherAppsConnectorCountsOnlyWhatTheAppRemoved() = runTest {
        assumeTrue("Only for another app's connector", delegated)
        val app = connector as DeviceAppConnector
        val kept = photo(KEPT)
        val removed = photo(REMOVED)
        val trashed = photo(TRASHED)
        listOf(kept, removed, trashed).forEach(::store)
        val receipts = listOf(kept, removed, trashed).map { receiptFor(it, backUp(it)) }
        assumeTrue("The fake can't remove items", removeFromPhone(removed))
        trashOnPhone(trashed) // Where the fake has a trash.

        withClue("Only what the app removed counts as backed up; trashed items may have been deleted by hand") {
            app.removedFromPhone(receipts) shouldBe setOf(removed.contentId)
        }
    }

    /** A sample photo with its date and place inside; [n] makes its bytes, and so its content id, unique. */
    protected fun photo(n: Int = 1): BackupItem = sample(n, "golden/pixel.jpg", "image/jpeg", "PXL_$n.jpg")

    /** A sample video with its date and place inside. */
    protected fun video(n: Int = 1): BackupItem = sample(n, "golden/video.mp4", "video/mp4", "PXL_$n.mp4")

    private fun failureIsReported(failure: ServiceFailure, check: suspend (ConnectorError) -> Unit) = runTest {
        assumeTrue("Only for uploading connectors", !delegated)
        val photo = photo()
        store(photo)
        assumeTrue("The fake can't answer with $failure", failNextRequest(failure))

        val error = connector.upload(photo).shouldBeInstanceOf<UploadResult.Failed>().error
        check(error)

        if (failure != ServiceFailure.QUOTA_EXCEEDED && failure != ServiceFailure.SIGN_IN_EXPIRED) {
            withClue("Once the service is fine again, the same item goes through") { backUp(photo) }
        }
    }

    private suspend fun backUp(item: BackupItem): UploadResult = connector.upload(item).shouldBeSuccessful()

    private fun UploadResult.shouldBeSuccessful(): UploadResult = apply {
        withClue("Expected the item to be uploaded or handed over, got $this") {
            (this is UploadResult.Uploaded || this is UploadResult.HandedOver).shouldBeTrue()
        }
        if (delegated) shouldBeInstanceOf<UploadResult.HandedOver>()
    }

    private fun remoteIdOf(result: UploadResult): String = when (result) {
        is UploadResult.Uploaded -> result.remoteId
        is UploadResult.HandedOver -> result.location
        else -> error("Not backed up: $result")
    }

    private fun receiptFor(item: BackupItem, result: UploadResult) = BackupReceipt(
        item.contentId,
        account.id,
        remoteIdOf(result),
        if (delegated) BackupState.HANDED_OVER else BackupState.BACKED_UP,
        backedUpAtMillis = 0,
    )

    private fun sample(n: Int, resource: String, mimeType: String, name: String): BackupItem {
        val original = javaClass.classLoader.getResourceAsStream(resource)?.use { it.readBytes() }
            ?: error("Missing $resource")
        // Bytes after the end of a JPEG or MP4 are ignored by readers: they only make each sample unique.
        val bytes = original + "MediaSync sample $n".encodeToByteArray()
        return BackupItem(
            contentId = ContentId(sha256(bytes)),
            displayName = name,
            mimeType = mimeType,
            size = bytes.size.toLong(),
            location = "content://media/external_primary/${if (mimeType.startsWith("video/")) "video" else "images"}" +
                "/media/${SAMPLE_ID_BASE + n}",
            capturedAtMillis = SAMPLE_CAPTURED_AT,
            content = { ByteArrayInputStream(bytes) },
        )
    }

    private fun BackupItem.bytes(): ByteArray = content.open().use { it.readBytes() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val KEPT = 1
        const val REMOVED = 2
        const val TRASHED = 3
        const val SAMPLE_ID_BASE = 1_000
        const val SAMPLE_CAPTURED_AT = 1_747_046_400_000L
    }
}

/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector.googlephotos

import io.github.danyk20.mediasync.connector.AccountId
import io.github.danyk20.mediasync.connector.BackupItem
import io.github.danyk20.mediasync.connector.BackupReceipt
import io.github.danyk20.mediasync.connector.BackupState
import io.github.danyk20.mediasync.connector.ConnectorAccount
import io.github.danyk20.mediasync.connector.ContentId
import io.github.danyk20.mediasync.connector.SkipReason
import io.github.danyk20.mediasync.connector.UploadResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** What the kit doesn't cover: files outside the folder, files MediaSync can't read, and the profile. */
class GooglePhotosAppConnectorTest {
    private val phone = FakePhone()
    private val factory = GooglePhotosAppConnectorFactory(phone)
    private val connector = factory.create(ConnectorAccount(ACCOUNT, GooglePhotosAppConnectorFactory.ID, "", 0))

    @Test
    fun aFileOutsideTheBackedUpFolderIsntHandedOver() = runTest {
        phone.files[LOCATION] = FakePhone.File(ByteArray(1), "Pictures/MediaSync/")

        connector.upload(item()) shouldBe UploadResult.Skipped(SkipReason.OUTSIDE_BACKUP_FOLDER)
    }

    @Test
    fun theFolderMatchesWithOrWithoutItsTrailingSlash() = runTest {
        phone.files[LOCATION] = FakePhone.File(ByteArray(1), "DCIM/MediaSync")

        connector.upload(item()) shouldBe UploadResult.HandedOver(LOCATION)
    }

    @Test
    fun aTrashedFileIsntHandedOver() = runTest {
        phone.files[LOCATION] = FakePhone.File(ByteArray(1), "DCIM/MediaSync/", trashed = true)

        connector.upload(item()) shouldBe UploadResult.Skipped(SkipReason.NOT_ON_PHONE)
    }

    @Test
    fun filesMediaSyncCantReadArentCountedAsRemoved() = runTest {
        phone.readable = false

        connector.upload(item()) shouldBe UploadResult.Skipped(SkipReason.NOT_ON_PHONE)
        connector.removedFromPhone(listOf(receipt())).shouldBeEmpty()
    }

    @Test
    fun theProfileGuidesSetUpOfTheFolderTheReceivingPhoneStoresInto() {
        val profile = factory.profile

        profile.packageName shouldBe "com.google.android.apps.photos"
        profile.folder shouldBe "DCIM/MediaSync"
        factory.descriptor.limits.allows(200_000_000, video = false) shouldBe true
        factory.descriptor.limits.allows(200_000_001, video = false) shouldBe false
        factory.descriptor.limits.allows(10_000_000_000, video = true) shouldBe true
    }

    private fun item() = BackupItem(
        contentId = CONTENT,
        displayName = "IMG-20250512-WA0003.jpg",
        mimeType = "image/jpeg",
        size = 1,
        location = LOCATION,
        content = { ByteArrayInputStream(ByteArray(1)) },
    )

    private fun receipt() = BackupReceipt(CONTENT, ACCOUNT, LOCATION, BackupState.HANDED_OVER, backedUpAtMillis = 0)

    private companion object {
        val ACCOUNT = AccountId("google-photos-app")
        val CONTENT = ContentId("a".repeat(64))
        const val LOCATION = "content://media/external_primary/images/media/42"
    }
}

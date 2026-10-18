/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector.googlephotos

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.danyk20.mediasync.connector.AccountId
import io.github.danyk20.mediasync.connector.BackupItem
import io.github.danyk20.mediasync.connector.BackupReceipt
import io.github.danyk20.mediasync.connector.BackupState
import io.github.danyk20.mediasync.connector.ConnectorAccount
import io.github.danyk20.mediasync.connector.ContentId
import io.github.danyk20.mediasync.connector.SkipReason
import io.github.danyk20.mediasync.connector.UploadResult
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The connector against the real gallery on API 29 and 36 (CI): where files are, and when they're gone. */
@RunWith(AndroidJUnit4::class)
class MediaStoreFolderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val folder = MediaStoreFolder(context)
    private val connector = GooglePhotosAppConnectorFactory(folder)
        .create(ConnectorAccount(AccountId("google-photos-app"), GooglePhotosAppConnectorFactory.ID, "", 0))
    private val created = mutableListOf<Uri>()

    @After
    fun tearDown() {
        created.forEach(::deleteQuietly)
    }

    @Test
    fun aReceivedFileInTheMediaSyncFolderIsHandedToGooglePhotos() = runBlocking<Unit> {
        val uri = insert("DCIM/MediaSync/", "IMG-20250512-WA0003.jpg")

        connector.upload(item(uri)) shouldBe UploadResult.HandedOver(uri.toString())
    }

    @Test
    fun aFileElsewhereIsnt() = runBlocking<Unit> {
        val uri = insert("Pictures/MediaSyncTest/", "elsewhere.jpg")

        connector.upload(item(uri)) shouldBe UploadResult.Skipped(SkipReason.OUTSIDE_BACKUP_FOLDER)
    }

    @Test
    fun aFileRemovedFromThePhoneCountsAsBackedUpByTheApp() = runBlocking<Unit> {
        val kept = insert("DCIM/MediaSync/", "kept.jpg")
        val removed = insert("DCIM/MediaSync/", "removed.jpg")
        context.contentResolver.delete(removed, null, null) shouldBe 1
        created -= removed // Deleting it again would throw.

        folder.find(removed.toString()) shouldBe PhoneFile.Gone
        connector.removedFromPhone(listOf(receipt(kept, 1), receipt(removed, 2))) shouldBe setOf(contentId(2))
    }

    @Test
    fun aTrashedFileDoesnt() = runBlocking<Unit> {
        assumeTrue("The trash came with Android 11", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val trashed = insert("DCIM/MediaSync/", "trashed.jpg")
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }
        context.contentResolver.update(trashed, values, null, null) shouldBe 1

        // Found either way, so never counted as removed, even before the gallery reports the trash.
        connector.removedFromPhone(listOf(receipt(trashed, 3))) shouldBe emptySet()
        // Android may report the trash a moment after the update.
        var file = folder.find(trashed.toString())
        val end = System.currentTimeMillis() + TRASH_TIMEOUT_MILLIS
        while ((file as? PhoneFile.Found)?.trashed != true && System.currentTimeMillis() < end) {
            delay(POLL_MILLIS)
            file = folder.find(trashed.toString())
        }
        file shouldBe PhoneFile.Found("DCIM/MediaSync/", trashed = true)
        connector.upload(item(trashed)) shouldBe UploadResult.Skipped(SkipReason.NOT_ON_PHONE)
    }

    @Test
    fun changesInTheGalleryAreNoticed() = runBlocking<Unit> {
        val change = async { withTimeoutOrNull(CHANGE_TIMEOUT_MILLIS) { folder.changes().first() } }
        // A file added before the observer registers goes unnoticed, and on a busy device that can take a while:
        // keep adding one until it's noticed.
        var added = 0
        while (change.isActive) {
            delay(OBSERVER_START_MILLIS)
            insert("DCIM/MediaSync/", "new${added++}.jpg")
        }

        change.await() shouldBe Unit
    }

    /**
     * Writes a photo as MediaSync's receiving phone does: pending until it's complete. Written straight away, Android
     * still finished the file in the background, and a trash made in that moment was silently lost (7 in 40 on API 34,
     * 2 in 40 on API 36; the nightly's and #45's failures).
     */
    private fun insert(path: String, name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, path)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        created += uri
        context.contentResolver.openOutputStream(uri)!!.use { it.write(JPEG_START) }
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        context.contentResolver.update(uri, done, null, null) shouldBe 1
        return uri
    }

    /**
     * Tidying up, not what's tested. Android 16 sometimes refuses to delete a file a moment after it was trashed, as
     * if it weren't this app's (main, API 36): tries again for a while, then leaves it to the trash's own cleanup.
     */
    private fun deleteQuietly(uri: Uri) {
        val end = SystemClock.uptimeMillis() + DELETE_TIMEOUT_MILLIS
        while (runCatching { context.contentResolver.delete(uri, null, null) }.isFailure &&
            SystemClock.uptimeMillis() < end
        ) {
            SystemClock.sleep(POLL_MILLIS)
        }
    }

    private fun item(uri: Uri) = BackupItem(
        contentId = contentId(0),
        displayName = "photo.jpg",
        mimeType = "image/jpeg",
        size = JPEG_START.size.toLong(),
        location = uri.toString(),
        content = { ByteArrayInputStream(JPEG_START) },
    )

    private fun receipt(uri: Uri, n: Int) =
        BackupReceipt(contentId(n), AccountId("google-photos-app"), uri.toString(), BackupState.HANDED_OVER, 0)

    private fun contentId(n: Int) = ContentId(n.toString().padStart(64, '0'))

    private companion object {
        val JPEG_START = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        const val CHANGE_TIMEOUT_MILLIS = 10_000L
        const val OBSERVER_START_MILLIS = 500L
        const val TRASH_TIMEOUT_MILLIS = 5_000L
        const val DELETE_TIMEOUT_MILLIS = 3_000L
        const val POLL_MILLIS = 100L
    }
}

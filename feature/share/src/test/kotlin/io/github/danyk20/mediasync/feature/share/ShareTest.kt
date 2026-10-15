/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.share

import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.danyk20.mediasync.core.database.Peer
import io.github.danyk20.mediasync.core.database.PeerStore
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.media.SharedItems
import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.testing.FakeSettingsRepository
import io.github.danyk20.mediasync.core.testing.captureScreenshot
import io.github.danyk20.mediasync.sync.android.SyncScheduler
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ShareTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val shared = SharedItems(context)
    private val scheduler = mockk<SyncScheduler>(relaxed = true)
    private val queue = ShareQueue(context, shared, scheduler)

    @Test
    fun galleryItemsAreFoundBehindWhatGalleryAppsShare() {
        galleryUriOf(Uri.parse("content://media/external/images/media/42?requireOriginal=1")) shouldBe
            Uri.parse("content://media/external/images/media/42")
        // Google Photos wraps the gallery item's URI in its own.
        galleryUriOf(
            Uri.parse(
                "content://com.google.android.apps.photos.contentprovider/-1/1/" +
                    "content%3A%2F%2Fmedia%2Fexternal%2Fimages%2Fmedia%2F42/ORIGINAL/NONE/image%2Fjpeg/1234",
            ),
        ) shouldBe Uri.parse("content://media/external/images/media/42")
        galleryUriOf(Uri.parse("content://com.whatsapp.provider.media/item/abc")) shouldBe null
    }

    @Test
    fun theSharedItemsAreReadFromEitherKindOfShareWithoutDuplicates() {
        val a = Uri.parse("content://media/external/images/media/1")
        val b = Uri.parse("content://media/external/video/media/2")
        val one = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, a)
        val many = Intent(Intent.ACTION_SEND_MULTIPLE)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(a, b))
            .apply { clipData = ClipData.newRawUri("", b) }

        one.sharedUris() shouldBe listOf(a)
        many.sharedUris() shouldBe listOf(a, b)
        Intent(Intent.ACTION_VIEW, a).sharedUris().shouldBeEmpty()
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, Uri.fromFile(File("/x.jpg"))).sharedUris()
            .shouldBeEmpty()
    }

    @Test
    fun aFileThatIsntInTheGalleryIsCopiedUnderItsNameAndSentNow() {
        Robolectric.setupContentProvider(SharingApp::class.java, SharingApp.AUTHORITY)
        var progress = 0

        val queued = queue.queue(listOf(SharingApp.uri("IMG-20250512-WA0003.jpg"))) { progress = it }

        queued shouldBe Queued(items = 1)
        progress shouldBe 1
        val copy = File(Uri.parse(shared.all().single()).path!!)
        copy.name shouldBe "IMG-20250512-WA0003.jpg"
        copy.readBytes().toList() shouldBe SharingApp.BYTES.toList()
        copy.parentFile!!.parentFile shouldBe shared.copies
        verify(exactly = 1) { scheduler.syncNow() }
    }

    @Test
    fun whatCantBeReadIsCountedAndNothingStartsWithoutAnything() {
        Robolectric.setupContentProvider(SharingApp::class.java, SharingApp.AUTHORITY)

        val queued = queue.queue(listOf(SharingApp.uri(SharingApp.GONE)))

        queued shouldBe Queued(items = 0, unreadable = 1)
        shared.all().shouldBeEmpty()
        shared.copies.listFiles().orEmpty().toList().shouldBeEmpty()
        verify(exactly = 0) { scheduler.syncNow() }
    }

    @Test
    fun sharingIsOfferedOnlyOnAPairedSendingPhone() = runBlocking<Unit> {
        val settings = FakeSettingsRepository()
        val peers = MutableStateFlow(emptyList<Peer>())
        val target = ShareTarget(context, settings, mockk<PeerStore> { every { observeAll() } returns peers })
        val consumer = Peer(DeviceId.of(ByteArray(32) { 2 }), "Pixel 2 XL", DeviceRole.CONSUMER, 0, null, null)

        target.available.first() shouldBe false
        settings.setRole(DeviceRole.PRODUCER)
        target.available.first() shouldBe false
        peers.value = listOf(consumer)
        target.available.first() shouldBe true
        settings.setRole(DeviceRole.CONSUMER)
        target.available.first() shouldBe false
    }

    @Test
    fun theDialogSaysWhereTheyGoAndClosesWithOk() {
        var done = 0
        show(ShareState.Sending(Queued(items = 5, unreadable = 1), "Pixel 2 XL")) { done++ }

        composeRule.onNodeWithText("Sending 5 photos and videos to Pixel 2 XL now.").assertExists()
        composeRule.onNodeWithText("1 couldn't be read, so it isn't sent.").assertExists()
        composeRule.onNodeWithText("OK").performClick()

        done shouldBe 1
    }

    @Test
    fun screenshot_preparing_light() {
        show(ShareState.Preparing(done = 2, total = 5))
        composeRule.onRoot().captureScreenshot("share_preparing_light")
    }

    @Test
    fun screenshot_sending_dark() {
        show(ShareState.Sending(Queued(items = 5), "Pixel 2 XL"), darkTheme = true)
        composeRule.onRoot().captureScreenshot("share_sending_dark")
    }

    private fun show(state: ShareState, darkTheme: Boolean = false, onDone: () -> Unit = {}) {
        composeRule.setContent {
            MediaSyncTheme(darkTheme = darkTheme, dynamicColor = false) { ShareDialog(state, onDone) }
        }
    }

    /** Another app that shares a file of its own, as WhatsApp does: not a gallery item. */
    class SharingApp : ContentProvider() {
        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor =
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(uri.lastPathSegment)) }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (uri.lastPathSegment == GONE) throw java.io.FileNotFoundException("Deleted meanwhile")
            val file = File.createTempFile("shared", ".jpg").apply { writeBytes(BYTES) }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun getType(uri: Uri) = "image/jpeg"

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0

        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0

        companion object {
            const val AUTHORITY = "com.example.sharing"
            const val GONE = "gone.jpg"
            val BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())

            fun uri(name: String): Uri = Uri.parse("content://$AUTHORITY/files/$name")
        }
    }
}

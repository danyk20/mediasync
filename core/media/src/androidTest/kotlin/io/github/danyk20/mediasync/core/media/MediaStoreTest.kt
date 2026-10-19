/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.media

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import io.github.danyk20.mediasync.core.database.MediaSyncDatabase
import io.github.danyk20.mediasync.core.metadata.ByteReader
import io.github.danyk20.mediasync.core.metadata.PhotoDetailsRemover
import io.github.danyk20.mediasync.core.metadata.Redaction
import io.github.danyk20.mediasync.core.metadata.VideoDates
import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.core.model.PhotoDetails
import io.github.danyk20.mediasync.core.model.SendRange
import io.github.danyk20.mediasync.core.model.SourcePreset
import io.github.danyk20.mediasync.core.model.SourceSelection
import io.github.danyk20.mediasync.sync.engine.Fingerprints
import io.github.danyk20.mediasync.sync.engine.MediaDescriptor
import io.github.danyk20.mediasync.sync.engine.MediaMeta
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okio.buffer
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real MediaStore on API 29 and 36 (CI): pending items, resume, gallery details, original reads. */
@RunWith(AndroidJUnit4::class)
class MediaStoreTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.ACCESS_MEDIA_LOCATION)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database = Room.inMemoryDatabaseBuilder(context, MediaSyncDatabase::class.java).build()
    private val sink = MediaStoreSink(context, database.partialTransferDao(), reserveBytes = 0)
    private val producer = DeviceId.of(ByteArray(32) { 7 })
    private val created = mutableListOf<Uri>()

    @After
    fun tearDown() {
        created.forEach { context.contentResolver.delete(it, null, null) }
        database.close()
    }

    @Test
    fun receivedFileAppearsInTheMediaSyncFolderWithItsCaptureDate() {
        val photo = jpeg(Color.BLUE)
        val descriptor = descriptorOf(photo, "IMG-20250512-WA0003.jpg", capturedAt = 1_747_000_000_000)

        val location = sink.open(producer, descriptor, 0).run {
            write(photo, 0, photo.size)
            commit()
        }
        val uri = Uri.parse(location).also { created += it }

        location shouldStartWith "content://media/"
        val stored = readBytes(uri)
        // The image itself is untouched; only an EXIF block with the capture date was added.
        BitmapFactory.decodeByteArray(stored, 0, stored.size).sameAs(
            BitmapFactory.decodeByteArray(photo, 0, photo.size),
        ) shouldBe true
        ExifInterface(stored.inputStream()).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL).shouldNotBeNull()
        context.contentResolver.query(
            uri,
            arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.IS_PENDING,
                MediaStore.MediaColumns.DATE_TAKEN,
            ),
            null,
            null,
            null,
        )!!.use { row ->
            row.moveToFirst() shouldBe true
            row.getString(0) shouldBe "IMG-20250512-WA0003.jpg"
            row.getString(1) shouldBe "DCIM/MediaSync/"
            row.getInt(2) shouldBe 0
            // The JPEG had no EXIF date, so the gallery date comes from the producer's (PLAN.md §6.2).
            row.getLong(3) shouldBe 1_747_000_000_000
        }
    }

    @Test
    fun aNameWithMoreThanOneDotKeepsOnlyTheLastSoGooglePhotosShowsIt() {
        val names = ReceivedNames(context) { true }
        val simple = MediaStoreSink(context, database.partialTransferDao(), reserveBytes = 0, names::of)
        val photo = jpeg(Color.MAGENTA)

        val location = simple.open(producer, descriptorOf(photo, "20251018_101530.LS.jpg"), 0).run {
            write(photo, 0, photo.size)
            commit()
        }
        val uri = Uri.parse(location).also { created += it }

        displayName(uri) shouldBe "20251018_101530_LS.jpg"
    }

    @Test
    fun filesReceivedBeforeGetTheSimpleNameAndOthersKeepTheirs() = runBlocking<Unit> {
        val received = insertInto("${MediaStoreSink.FOLDER}/", jpeg(Color.CYAN), "20251018_101531.LS.jpg")
        val camera = insertIntoCamera(jpeg(Color.YELLOW), "20251018_101532.LS.jpg")

        ReceivedNames(context) { false }.simplifyStored() shouldBe 0
        displayName(received) shouldBe "20251018_101531.LS.jpg"

        (ReceivedNames(context) { true }.simplifyStored() >= 1) shouldBe true
        displayName(received) shouldBe "20251018_101531_LS.jpg"
        displayName(camera) shouldBe "20251018_101532.LS.jpg" // Not MediaSync's folder.
    }

    @Test
    fun aPhotoThatHasACaptureDateIsStoredByteForByte() {
        val photo = jpeg(Color.MAGENTA, exifDate = "2024:06:01 10:00:00")
        val descriptor = descriptorOf(photo, "has-date.jpg", capturedAt = 1_747_000_000_000)

        val location = sink.open(producer, descriptor, 0).run {
            write(photo, 0, photo.size)
            commit()
        }
        created += Uri.parse(location)

        readBytes(Uri.parse(location)).toList() shouldBe photo.toList()
    }

    @Test
    fun aRescanKeepsTheCaptureDateOfAPhotoWithoutTimeZone() {
        // Taken at 10:00 local time in UTC+2 and saved 3 s later; the EXIF has no zone, like many cameras'.
        val photo = jpeg(Color.CYAN, exifDate = "2024:06:01 10:00:00")
        val takenAt = Instant.parse("2024-06-01T08:00:00Z").toEpochMilli()
        val descriptor = descriptorOf(photo, "no-zone.jpg", capturedAt = takenAt, modifiedAt = takenAt + 3_000)

        val uri = Uri.parse(
            sink.open(producer, descriptor, 0).run {
                write(photo, 0, photo.size)
                commit()
            },
        ).also { created += it }
        rescan(uri)

        dateTaken(uri) shouldBe takenAt
        val stored = readBytes(uri)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // The file keeps the producer's modification time, so it stays byte for byte.
            stored.toList() shouldBe photo.toList()
        } else {
            // Android 10 can't set file times: the zone is written into the EXIF, the image is untouched.
            ExifInterface(stored.inputStream()).getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL) shouldBe "+02:00"
            BitmapFactory.decodeByteArray(stored, 0, stored.size).sameAs(
                BitmapFactory.decodeByteArray(photo, 0, photo.size),
            ) shouldBe true
        }
    }

    @Test
    fun aPhotoWithoutAnyDateTakesTheDayInItsName() {
        // A WhatsApp image: no EXIF date, and no gallery date from the sending phone either.
        val photo = jpeg(Color.YELLOW)
        val noon = LocalDate.of(2025, 5, 12).atTime(LocalTime.NOON).atZone(ZoneId.systemDefault())

        val uri = Uri.parse(
            sink.open(producer, descriptorOf(photo, "IMG-20250512-WA0004.jpg"), 0).run {
                write(photo, 0, photo.size)
                commit()
            },
        ).also { created += it }

        dateTaken(uri) shouldBe noon.toInstant().toEpochMilli()
        rescan(uri)
        dateTaken(uri) shouldBe noon.toInstant().toEpochMilli()
    }

    @Test
    fun aVideoWithoutACaptureTimeGetsOneThatARescanKeeps() {
        val video = undatedVideo()
        val takenAt = Instant.parse("2025-05-12T17:45:10Z").toEpochMilli()
        val descriptor = descriptorOf(video, "VID-20250512-WA0001.mp4", capturedAt = takenAt, mimeType = "video/mp4")

        val uri = Uri.parse(
            sink.open(producer, descriptor, 0).run {
                write(video, 0, video.size)
                commit()
            },
        ).also { created += it }
        rescan(uri)

        dateTaken(uri) shouldBe takenAt
        val stored = readBytes(uri)
        stored.size shouldBe video.size // Written in place: the samples keep their offsets.
        VideoDates.capturedAtMillis(
            stored.size.toLong(),
            ByteReader { at, count ->
                stored.copyOfRange(at.toInt(), at.toInt() + count)
            },
        ) shouldBe takenAt
    }

    @Test
    fun anInterruptedFileResumesAtItsOffset() {
        val photo = jpeg(Color.RED)
        val descriptor = descriptorOf(photo, "resume.jpg")
        val half = photo.size / 2

        sink.open(producer, descriptor, 0).use { it.write(photo, 0, half) }
        sink.partialSize(producer, descriptor.fingerprint) shouldBe half.toLong()

        val location = sink.open(producer, descriptor, half.toLong()).run {
            existingBytes().buffer().use { it.readByteArray().size } shouldBe half
            write(photo, half, photo.size - half)
            commit()
        }
        created += Uri.parse(location)

        readBytes(Uri.parse(location)).toList() shouldBe photo.toList()
        sink.partialSize(producer, descriptor.fingerprint) shouldBe 0
    }

    @Test
    fun discardedFilesLeaveNothingBehind() {
        val photo = jpeg(Color.GREEN)
        val descriptor = descriptorOf(photo, "discard.jpg")

        sink.open(producer, descriptor, 0).run {
            write(photo, 0, 10)
            discard()
        }

        sink.partialSize(producer, descriptor.fingerprint) shouldBe 0
    }

    @Test
    fun cameraPhotosAreListedWithFingerprintsOfTheirOriginalBytes() {
        val photo = jpeg(Color.YELLOW)
        val uri = insertIntoCamera(photo, "PXL_20260101_120000000.jpg")
        val source = MediaStoreSource(context, database.mediaFingerprintDao()) { SourceSelection.Default }

        val item = source.items().single { it.descriptor.displayName == "PXL_20260101_120000000.jpg" }

        source.canReadLocation() shouldBe true
        MediaPermissions.canReadLocation(context) shouldBe true
        MediaPermissions.required.contains(Manifest.permission.ACCESS_MEDIA_LOCATION) shouldBe true
        item.key shouldBe MediaStore.setRequireOriginal(uri).toString()
        item.descriptor.size shouldBe photo.size.toLong()
        item.descriptor.fingerprint shouldBe Fingerprints.of(photo)
        item.descriptor.meta.album shouldBe "Camera"
        source.open(item).buffer().use { it.readByteArray() }.toList() shouldBe photo.toList()
        database.mediaFingerprintDao().get(item.key)?.fingerprint shouldBe Fingerprints.of(photo).hex()
        // Listing again finds the same file with the same fingerprint (MediaStore may have finished
        // scanning in between, so details like the modification time can legitimately change).
        source.items().single { it.key == item.key }.descriptor.fingerprint shouldBe item.descriptor.fingerprint
    }

    @Test
    fun theLocationIsReadFromTheOriginalAndRemovedBeforeSending() {
        val photo = jpeg(Color.CYAN, exifDate = "2026:10:14 09:30:00", location = true)
        insertIntoCamera(photo, "PXL_20261014_093000000.jpg")
        val source = MediaStoreSource(context, database.mediaFingerprintDao()) { SourceSelection.Default }
        val item = source.items().single { it.descriptor.displayName == "PXL_20261014_093000000.jpg" }

        val plan = source.openRanges(item).use { ranges ->
            ranges.read(0, photo.size).toList() shouldBe photo.toList() // The original, location included.
            PhotoDetailsRemover.plan(item.descriptor.size, ByteReader(ranges::read), PhotoDetails.REMOVE_LOCATION)
        }
        val sent = source.open(item).buffer().inputStream().use {
            plan.shouldBeInstanceOf<Redaction.Edited>().open(it).readBytes()
        }

        ExifInterface(photo.inputStream()).latLong.shouldNotBeNull()
        ExifInterface(sent.inputStream()).latLong.shouldBeNull()
        ExifInterface(sent.inputStream()).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) shouldBe
            "2026:10:14 09:30:00"
    }

    @Test
    fun onlyTheChosenFoldersAreSent() {
        insertIntoCamera(jpeg(Color.DKGRAY), "camera-choice.jpg")
        insertInto(TEST_FOLDER, jpeg(Color.LTGRAY), "other-choice.jpg")
        fun sentWith(selection: SourceSelection) =
            MediaStoreSource(context, database.mediaFingerprintDao()) { selection }.items()
                .map { it.descriptor.displayName }
                .filter { it.endsWith("-choice.jpg") }
                .sorted()

        sentWith(SourceSelection.Default) shouldBe listOf("camera-choice.jpg")
        sentWith(SourceSelection(SourcePreset.EVERYTHING)) shouldBe listOf("camera-choice.jpg", "other-choice.jpg")
        sentWith(SourceSelection.of(setOf(TEST_FOLDER))) shouldBe listOf("other-choice.jpg")
        sentWith(SourceSelection.of(emptySet())) shouldBe emptyList()
    }

    @Test
    fun onlyWhatWasTakenWithinTheChosenDaysIsSentBothDaysIncluded() {
        // Taken on a fixed day, with its time zone as a camera records it: without one, Android keeps no date taken
        // for a photo whose file is much newer. Neither the test's clock nor its time zone decides what "today" is.
        insertInto(TEST_FOLDER, jpeg(Color.CYAN, "2025:03:15 12:00:00", exifOffset = "+01:00"), "march-range.jpg")
        val day = LocalDate.of(2025, 3, 15)
        fun sentWithin(range: SendRange) =
            MediaStoreSource(context, database.mediaFingerprintDao(), range = { range }) {
                SourceSelection.of(setOf(TEST_FOLDER))
            }.items()
                .map { it.descriptor.displayName }
                .filter { it.endsWith("-range.jpg") }

        sentWithin(SendRange.Everything) shouldBe listOf("march-range.jpg")
        sentWithin(SendRange(from = day)) shouldBe listOf("march-range.jpg")
        sentWithin(SendRange(until = day)) shouldBe listOf("march-range.jpg")
        sentWithin(SendRange(day, day)) shouldBe listOf("march-range.jpg")
        sentWithin(SendRange(from = day.plusDays(1))) shouldBe emptyList()
        sentWithin(SendRange(until = day.minusDays(1))) shouldBe emptyList()
    }

    @Test
    fun sharedItemsAreSentWhateverTheFoldersAndDroppedOnceConfirmed() {
        val shared = SharedItems(context)
        val photo = insertInto(TEST_FOLDER, jpeg(Color.GREEN), "shared-photo.jpg")
        val copy = File(shared.copies, "test/IMG-20250512-WA0009.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(jpeg(Color.RED))
        }
        shared.add(listOf(photo.toString(), Uri.fromFile(copy).toString()))
        // Folders chosen that hold neither.
        val source =
            MediaStoreSource(context, database.mediaFingerprintDao(), shared) { SourceSelection.of(emptySet()) }

        val items = source.items().filter { it.shared }

        items.map { it.descriptor.displayName }.sorted() shouldBe listOf("IMG-20250512-WA0009.jpg", "shared-photo.jpg")
        items.single { it.descriptor.displayName == "IMG-20250512-WA0009.jpg" }.descriptor.mimeType shouldBe
            "image/jpeg"
        items.forEach(source::confirmed)
        source.items().filter { it.shared }.shouldBeEmpty()
        copy.exists() shouldBe false
    }

    @Test
    fun foldersAreListedWithTheirSizeAndNewestItem() {
        val first = jpeg(Color.BLACK)
        val second = jpeg(Color.WHITE)
        // A folder of its own: other tests' photos in the shared one may still be listed while they're deleted.
        val name = "MediaSyncFolders${System.nanoTime()}"
        val path = "Pictures/$name/"
        val older = insertInto(path, first, "older.jpg")
        Thread.sleep(MODIFIED_TIME_RESOLUTION_MILLIS) // MediaStore keeps modification times in seconds.
        val newest = insertInto(path, second, "newer.jpg")
        // MediaStore fills in sizes when it gets round to indexing new files (on Android 10, not reliably soon).
        rescan(older)
        rescan(newest)

        val folder = MediaStoreLibrary(context).let { runBlocking { it.folders() } }.single { it.path == path }

        folder shouldBe MediaFolder(path, 2, (first.size + second.size).toLong(), newest.toString())
        folder.name shouldBe name
    }

    @Test
    fun newPhotosAreNoticed() = runBlocking<Unit> {
        val library = MediaStoreLibrary(context)

        val noticed = async { withTimeoutOrNull(CHANGE_TIMEOUT_MILLIS) { library.changes().first() } }
        // A photo added before the observer registers goes unnoticed, and on a busy device that can take a while:
        // keep adding one until it's noticed.
        var added = 0
        while (noticed.isActive) {
            delay(OBSERVER_START_MILLIS)
            insertInto(TEST_FOLDER, jpeg(Color.GRAY), "noticed${added++}.jpg")
        }

        noticed.await() shouldBe Unit
    }

    private fun insertIntoCamera(bytes: ByteArray, name: String): Uri = insertInto("DCIM/Camera/", bytes, name)

    /**
     * Adds a photo as a camera app does: pending until it's complete, when Android reads it at once. Written straight
     * away, Android read it in the background, and a test could look before its dates were there (nightly, API 36).
     */
    private fun insertInto(folder: String, bytes: ByteArray, name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        created += uri
        context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        context.contentResolver.update(uri, done, null, null) shouldBe 1
        return uri
    }

    /** Makes Android's media scanner read the file again, as it would on its own at any later time. */
    private fun rescan(uri: Uri) {
        @Suppress("DEPRECATION") // The test reads the path of a file it owns.
        val path = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)!!
            .use {
                it.moveToFirst()
                it.getString(0)
            }
        val scanned = CountDownLatch(1)
        MediaScannerConnection.scanFile(context, arrayOf(path), null) { _, _ -> scanned.countDown() }
        scanned.await(SCAN_TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe true
    }

    private fun dateTaken(uri: Uri): Long =
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATE_TAKEN), null, null, null)!!
            .use {
                it.moveToFirst()
                it.getLong(0)
            }

    private fun displayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)!!
            .use {
                it.moveToFirst()
                it.getString(0)
            }

    private fun readBytes(uri: Uri) = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

    private fun descriptorOf(
        bytes: ByteArray,
        name: String,
        capturedAt: Long? = null,
        modifiedAt: Long? = null,
        mimeType: String = "image/jpeg",
    ) = MediaDescriptor(
        fingerprint = Fingerprints.of(bytes),
        size = bytes.size.toLong(),
        mimeType = mimeType,
        displayName = name,
        meta = MediaMeta(capturedAtMillis = capturedAt, modifiedAtMillis = modifiedAt),
    )

    /**
     * The golden MP4 with every creation and modification time (movie, tracks, media) set to zero, as in many videos
     * saved by messaging apps.
     */
    private fun undatedVideo(): ByteArray {
        val video = javaClass.getResourceAsStream("/golden/video.mp4")!!.use { it.readBytes() }
        val text = String(video, Charsets.ISO_8859_1)
        listOf("mvhd", "tkhd", "mdhd").forEach { type ->
            var at = text.indexOf(type)
            while (at >= 0) {
                val payload = at + 4
                val width = if (video[payload].toInt() == 0) 8 else 16
                ByteArray(width).copyInto(video, payload + 4)
                at = text.indexOf(type, at + 1)
            }
        }
        return video
    }

    /** A real JPEG; without [exifDate] it has no capture date, as WhatsApp images don't. */
    private fun jpeg(
        color: Int,
        exifDate: String? = null,
        location: Boolean = false,
        exifOffset: String? = null,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        if (exifDate == null && !location) return bytes
        val file = File.createTempFile("dated", ".jpg", context.cacheDir).apply { writeBytes(bytes) }
        ExifInterface(file).apply {
            exifDate?.let { setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, it) }
            exifOffset?.let { setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, it) }
            if (location) setLatLong(47.3769, 8.5417)
            saveAttributes()
        }
        return file.readBytes().also { file.delete() }
    }

    private companion object {
        const val SCAN_TIMEOUT_SECONDS = 10L
        const val TEST_FOLDER = "Pictures/MediaSyncTest/"
        const val MODIFIED_TIME_RESOLUTION_MILLIS = 1_100L
        const val CHANGE_TIMEOUT_MILLIS = 10_000L
        const val OBSERVER_START_MILLIS = 1_000L
    }
}

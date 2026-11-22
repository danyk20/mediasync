/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector.folder

import io.github.danyk20.mediasync.connector.AccountId
import io.github.danyk20.mediasync.connector.AuthState
import io.github.danyk20.mediasync.connector.BackupConnector
import io.github.danyk20.mediasync.connector.BackupItem
import io.github.danyk20.mediasync.connector.BackupReceipt
import io.github.danyk20.mediasync.connector.BackupState
import io.github.danyk20.mediasync.connector.ConnectorAccount
import io.github.danyk20.mediasync.connector.ConnectorError
import io.github.danyk20.mediasync.connector.ProgressSink
import io.github.danyk20.mediasync.connector.SettingProblem
import io.github.danyk20.mediasync.connector.UploadResult
import io.github.danyk20.mediasync.connector.VerifyResult
import io.github.danyk20.mediasync.connector.testing.backupItem
import io.github.danyk20.mediasync.connector.testing.contentId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.io.IOException
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FolderConnectorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val zone = ZoneId.of("UTC")
    private val may12 = ZonedDateTime.of(2025, 5, 12, 10, 30, 0, 0, zone).toInstant().toEpochMilli()

    private fun connector(folder: File? = tmp.root, layout: String? = null): BackupConnector {
        val settings = buildMap {
            folder?.let { put(FolderSettings.FOLDER, it.absolutePath) }
            layout?.let { put(FolderSettings.LAYOUT, it) }
        }
        return FolderConnectorFactory(zone).create(
            ConnectorAccount(AccountId("a"), FolderDescriptor.id, "Folder", 0, settings),
        )
    }

    private fun item(n: Int = 1, name: String = "IMG_$n.jpg", capturedAt: Long? = may12, bytes: String = "photo $n") =
        backupItem(n, bytes.encodeToByteArray()).copy(displayName = name, capturedAtMillis = capturedAt)

    private fun files(): List<String> = tmp.root.walkTopDown().filter { it.isFile }
        .map { it.relativeTo(tmp.root).invariantSeparatorsPath }.toList()

    private fun UploadResult.remoteId(): String = shouldBeInstanceOf<UploadResult.Uploaded>().remoteId

    @Test
    fun filesGoIntoYearAndMonthFoldersByCaptureDate() = runTest {
        val result = connector().upload(item())

        result.remoteId() shouldBe "2025/05/IMG_1.jpg"
        files() shouldBe listOf("2025/05/IMG_1.jpg")
    }

    @Test
    fun theMonthFollowsTheTimeZoneOfTheComputer() = runTest {
        val lateNight = ZonedDateTime.of(2025, 5, 31, 23, 30, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        val sydney = FolderConnectorFactory(ZoneId.of("Australia/Sydney")).create(
            ConnectorAccount(
                AccountId("a"),
                FolderDescriptor.id,
                "Folder",
                0,
                mapOf(FolderSettings.FOLDER to tmp.root.absolutePath),
            ),
        )

        sydney.upload(item(capturedAt = lateNight)).remoteId() shouldBe "2025/06/IMG_1.jpg"
    }

    @Test
    fun aFlatLayoutPutsEverythingInTheFolder() = runTest {
        connector(layout = FolderSettings.LAYOUT_FLAT).upload(item()).remoteId() shouldBe "IMG_1.jpg"
    }

    @Test
    fun anUnknownLayoutFallsBackToYearAndMonth() = runTest {
        connector(layout = "nonsense").upload(item()).remoteId() shouldBe "2025/05/IMG_1.jpg"
    }

    @Test
    fun itemsWithoutADateGoToUndated() = runTest {
        connector().upload(item(capturedAt = null)).remoteId() shouldBe "Undated/IMG_1.jpg"
    }

    @Test
    fun theFileKeepsTheCaptureDateAsItsModificationTime() = runTest {
        connector().upload(item())

        File(tmp.root, "2025/05/IMG_1.jpg").lastModified() shouldBe may12
    }

    @Test
    fun theCopySucceedsAndKeepsTheCaptureDateWhenTheCreationTimeIsSetToo() = runTest {
        val result = connector().upload(item(bytes = "dated photo"))

        result.remoteId() shouldBe "2025/05/IMG_1.jpg"
        File(tmp.root, "2025/05/IMG_1.jpg").let {
            it.readText() shouldBe "dated photo"
            it.lastModified() shouldBe may12
        }
    }

    @Test
    fun theBytesAreStoredAsTheyAre() = runTest {
        connector().upload(item(bytes = "exactly these bytes"))

        File(tmp.root, "2025/05/IMG_1.jpg").readText() shouldBe "exactly these bytes"
    }

    @Test
    fun sameNameAndSameBytesKeepsOneFile() = runTest {
        val first = connector().upload(item())
        val second = connector().upload(item())

        second.remoteId() shouldBe first.remoteId()
        second.shouldBeInstanceOf<UploadResult.Uploaded>().bytesSent shouldBe 0
        files() shouldBe listOf("2025/05/IMG_1.jpg")
    }

    @Test
    fun sameNameWithOtherBytesGetsANumber() = runTest {
        connector().upload(item(bytes = "one"))
        val second = connector().upload(item(bytes = "two"))
        val third = connector().upload(item(bytes = "three"))
        val again = connector().upload(item(bytes = "two"))

        second.remoteId() shouldBe "2025/05/IMG_1 (1).jpg"
        third.remoteId() shouldBe "2025/05/IMG_1 (2).jpg"
        again.remoteId() shouldBe second.remoteId()
        files().size shouldBe 3
    }

    @Test
    fun hostileNamesStayInsideTheFolder() = runTest {
        listOf("../../evil.jpg", "..\\..\\evil.jpg", "/etc/passwd", "a:b?.jpg", "CON.jpg", "..", "").forEachIndexed {
                i,
                name,
            ->
            connector(layout = FolderSettings.LAYOUT_FLAT).upload(item(n = i + 1, name = name, bytes = "b$i"))
        }

        files() shouldContainExactlyInAnyOrder listOf(
            "evil.jpg",
            "evil (1).jpg",
            "passwd",
            "a_b_.jpg",
            "_CON.jpg",
            "file",
            "file (1)",
        )
        tmp.root.parentFile.resolve("evil.jpg").exists() shouldBe false
    }

    @Test
    fun fileNamesAreSafeOnEverySystem() {
        safeFileName("IMG:1?.jpg") shouldBe "IMG_1_.jpg"
        safeFileName("name. ") shouldBe "name"
        safeFileName("nul") shouldBe "_nul"
        safeFileName("COM1.txt") shouldBe "_COM1.txt"
        safeFileName("tab\there.jpg") shouldBe "tab_here.jpg"
        safeFileName("dir/sub/photo.png") shouldBe "photo.png"
        safeFileName("   ") shouldBe "file"
        safeFileName("a".repeat(300) + ".jpg").length shouldBe 150
        safeFileName("a".repeat(300) + ".jpg").endsWith(".jpg") shouldBe true
    }

    @Test
    fun aMissingFolderIsNotCreated() = runTest {
        val gone = File(tmp.root, "mounted-share")

        val result = connector(folder = gone).upload(item())

        result.shouldBeInstanceOf<UploadResult.Failed>().error.shouldBeInstanceOf<ConnectorError.Transient>()
        gone.exists() shouldBe false
    }

    @Test
    fun noFolderSetFailsWithoutWriting() = runTest {
        connector(folder = null).upload(item()).shouldBeInstanceOf<UploadResult.Failed>()
        files().shouldBeEmpty()
    }

    @Test
    fun aFailingSourceLeavesNothingBehind() = runTest {
        val broken: BackupItem = item().copy(content = { throw IOException("gone") })

        val result = connector().upload(broken)

        result.shouldBeInstanceOf<UploadResult.Failed>().error.retryable shouldBe true
        tmp.root.walkTopDown().filter { it.isFile }.toList().shouldBeEmpty()
    }

    @Test
    fun errorMessagesNameNoFileOrPlace() = runTest {
        val gone = File(tmp.root, "secret-vacation-folder")

        val error = connector(folder = gone).upload(item(name = "private.jpg"))
            .shouldBeInstanceOf<UploadResult.Failed>().error.shouldBeInstanceOf<ConnectorError.Transient>()

        error.message shouldNotContain "secret"
        error.message shouldNotContain "private"
    }

    @Test
    fun progressEndsAtTheFullSize() = runTest {
        val big = item(bytes = "x".repeat(200_000))
        val reports = mutableListOf<Long>()

        connector().upload(
            big,
            ProgressSink { done, total ->
                total shouldBe big.size
                reports += done
            },
        )

        reports.last() shouldBe big.size
        reports shouldBe reports.sorted()
    }

    @Test
    fun checksTellPresentFromMissing() = runTest {
        val connector = connector()
        val kept = connector.upload(item(1)).remoteId()
        val deleted = connector.upload(item(2)).remoteId()
        File(tmp.root, deleted).delete()

        connector.verify(listOf(receipt(1, kept), receipt(2, deleted), receipt(3, "../outside.jpg"))) shouldBe mapOf(
            contentId(1) to VerifyResult.PRESENT,
            contentId(2) to VerifyResult.MISSING,
            contentId(3) to VerifyResult.MISSING,
        )
    }

    @Test
    fun anUnreachableFolderIsNotTakenForMissingCopies() = runTest {
        val gone = File(tmp.root, "offline")

        connector(folder = gone).verify(listOf(receipt(1, "2025/05/IMG_1.jpg"))) shouldBe
            mapOf(contentId(1) to VerifyResult.UNKNOWN)
    }

    @Test
    fun noSignInIsNeeded() = runTest {
        connector().authState() shouldBe AuthState.NotNeeded
    }

    @Test
    fun theSettingsNeedAFolder() {
        val schema = FolderConnectorFactory().settingsSchema

        schema.problems(emptyMap()) shouldBe mapOf(FolderSettings.FOLDER to SettingProblem.MISSING)
        schema.problems(mapOf(FolderSettings.FOLDER to "/data", FolderSettings.LAYOUT to "x")) shouldBe
            mapOf(FolderSettings.LAYOUT to SettingProblem.INVALID)
        schema.defaults() shouldBe mapOf(FolderSettings.LAYOUT to FolderSettings.LAYOUT_YEAR_MONTH)
    }

    @Test
    fun aRelativeOrBrokenFolderPathIsNotUsed() = runTest {
        listOf("relative/path", "bad\u0000path", "  ").forEach { path ->
            val connector = FolderConnectorFactory(zone).create(
                ConnectorAccount(
                    AccountId("a"),
                    FolderDescriptor.id,
                    "Folder",
                    0,
                    mapOf(FolderSettings.FOLDER to path),
                ),
            )

            connector.upload(item()).shouldBeInstanceOf<UploadResult.Failed>()
        }
    }

    private fun receipt(n: Int, remoteId: String) =
        BackupReceipt(contentId(n), AccountId("a"), remoteId, BackupState.BACKED_UP, backedUpAtMillis = 0)
}

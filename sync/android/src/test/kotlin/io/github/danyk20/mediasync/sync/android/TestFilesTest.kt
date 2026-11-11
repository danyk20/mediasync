/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.danyk20.mediasync.core.diagnostics.Diagnostics
import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.sync.engine.TestFile
import io.github.danyk20.mediasync.sync.engine.TestFileHeld
import io.github.danyk20.mediasync.sync.engine.TestFileOutcome
import io.github.danyk20.mediasync.sync.engine.TestFilePath
import io.github.danyk20.mediasync.sync.engine.TestFileStatus
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TestFilesTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val entries = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val outcome = TestFileOutcome(TestFileStatus.MATCHED, 25 * MB, 25 * MB, 2_000_000, TestFilePath.TURBO)

    init {
        Diagnostics.install { area, message -> entries += "${area.label} $message" }
    }

    @After
    fun tearDown() = Diagnostics.install { _, _ -> }

    @Test
    fun aTestFileWaitsUntilTheReceivingPhoneCheckedIt() {
        val testFiles = TestFiles(context)
        testFiles.state.value shouldBe TestFileState()

        testFiles.add(25 * MB)
        val waiting = testFiles.waiting().shouldNotBeNull()
        waiting.size shouldBe 25 * MB
        TestFiles(context).waiting() shouldBe waiting // Across restarts.

        testFiles.checked(waiting, outcome)

        testFiles.state.value.waiting.shouldBeNull()
        testFiles.state.value.last shouldBe outcome
        testFiles.state.value.lastAtMillis.shouldNotBeNull()
        TestFiles(context).state.value.last shouldBe outcome
        entries.last() shouldBe "send Test file checked by the receiving phone and deleted there: arrived exactly as " +
            "sent, 25.0 MB of 25.0 MB in 2.0 s (12.5 MB/s) over Turbo"
    }

    @Test
    fun whatKeepsItWaitingIsShownUntilItGoesOrIsRemoved() {
        val testFiles = TestFiles(context)
        testFiles.add(MB)
        val waiting = testFiles.waiting().shouldNotBeNull()

        testFiles.held(waiting, TestFileHeld.RECEIVER_OUTDATED)
        testFiles.state.value.problem shouldBe TestFileHeld.RECEIVER_OUTDATED
        testFiles.held(waiting, TestFileHeld.STOPPED) // Not a problem: it simply goes next time.
        testFiles.state.value.problem shouldBe TestFileHeld.RECEIVER_OUTDATED
        testFiles.held(waiting, TestFileHeld.NO_SPACE)
        testFiles.state.value.problem shouldBe TestFileHeld.NO_SPACE
        entries.last() shouldContain "free space"

        testFiles.remove()
        testFiles.state.value shouldBe TestFileState()
        testFiles.remove() // Nothing to remove.
        entries.count { it.contains("removed") } shouldBe 1
    }

    @Test
    fun aNewTestFileAddedMeanwhileStillWaits() {
        val testFiles = TestFiles(context)
        testFiles.add(MB)
        val sent = testFiles.waiting().shouldNotBeNull()
        testFiles.add(2 * MB)

        testFiles.checked(sent, outcome.copy(status = TestFileStatus.MISMATCHED))
        testFiles.held(sent, TestFileHeld.NO_SPACE)

        testFiles.waiting()?.size shouldBe 2 * MB
        testFiles.state.value.problem.shouldBeNull()
        testFiles.state.value.last?.status shouldBe TestFileStatus.MISMATCHED
    }

    @Test
    fun theReceivingPhoneWritesThemPrivatelyAndDeletesThem() {
        val folder = File(context.noBackupFilesDir, "test-files").apply { mkdirs() }
        File(folder, "test-left-from-a-crash.bin").writeText("left")
        val testFiles = TestFiles(context)

        val incoming = testFiles.open()
        folder.listFiles().orEmpty().size shouldBe 1 // What a crash left is gone.
        incoming.write(ByteArray(10) { 1 }, 0, 10)
        folder.listFiles().orEmpty().single().length() shouldBe 10
        incoming.delete()
        incoming.delete()
        folder.listFiles().orEmpty().toList().shouldBeEmpty()

        testFiles.checked(DeviceId.of(ByteArray(32)), outcome.copy(status = TestFileStatus.NOT_STORED))
        testFiles.state.value.last?.status shouldBe TestFileStatus.NOT_STORED
        testFiles.state.value.waiting.shouldBeNull()
        entries.last() shouldContain "receive Test file from 000000 checked and deleted: couldn't be written there"
    }

    @Test
    fun everyWayItWentIsDescribed() {
        TestFilePath.entries.map { outcome.copy(path = it).describe() }.map { it.substringAfter(" over ") } shouldBe
            listOf("Wi-Fi", "Turbo", "the internet", "the internet, through the relay")
        outcome.copy(status = TestFileStatus.MISMATCHED).describe() shouldContain "didn't arrive as sent"
        TestFile(1, 2).size shouldBe 1
    }

    private companion object {
        const val MB = 1024L * 1024
    }
}

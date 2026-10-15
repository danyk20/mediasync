/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.media

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The queue of photos and videos shared to MediaSync, to send now. */
@RunWith(RobolectricTestRunner::class)
class SharedItemsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var now = 1_000_000L
    private val shared = SharedItems(context) { now }

    @Test
    fun sharedItemsWaitInTheOrderTheyCame() {
        shared.add(listOf("content://media/external/images/media/2"))
        now += 1
        shared.add(listOf("content://media/external/images/media/1"))

        shared.all() shouldBe listOf(
            "content://media/external/images/media/2",
            "content://media/external/images/media/1",
        )
    }

    @Test
    fun removingOneDeletesItsCopyButNeverAFileElsewhere() {
        val copy = File(shared.copies, "a/IMG-20250512-WA0003.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        val elsewhere = File(context.filesDir, "keep.jpg").apply { writeBytes(byteArrayOf(2)) }
        shared.add(listOf(Uri.fromFile(copy).toString(), Uri.fromFile(elsewhere).toString()))

        shared.remove(listOf(Uri.fromFile(copy).toString(), Uri.fromFile(elsewhere).toString()))

        shared.all().shouldBeEmpty()
        copy.exists() shouldBe false
        elsewhere.exists() shouldBe true
    }

    @Test
    fun whatWaitedMoreThanAWeekIsDroppedWithItsCopy() {
        val copy = File(shared.copies, "b/clip.mp4").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(3))
        }
        shared.add(listOf(Uri.fromFile(copy).toString()))
        now += TimeUnit.DAYS.toMillis(6)
        shared.add(listOf("content://media/external/images/media/3"))

        now += TimeUnit.DAYS.toMillis(2)

        shared.all() shouldBe listOf("content://media/external/images/media/3")
        copy.exists() shouldBe false
    }
}

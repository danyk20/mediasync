/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The exported diagnostics reach the app the user shares them with, and only that file is shared. */
@RunWith(AndroidJUnit4::class)
class DiagnosticsShareTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theExportedLogCanBeReadThroughItsShareLink() {
        val file = File(context.cacheDir, "diagnostics").apply { mkdirs() }.resolve("mediasync-diagnostics.txt")
        file.writeText("MediaSync diagnostics")

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", file)

        assertTrue(uri.toString().startsWith("content://${context.packageName}.diagnostics/"))
        assertEquals(
            "MediaSync diagnostics",
            context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() },
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun nothingOutsideTheExportFolderCanBeShared() {
        val log = File(context.filesDir, "diagnostics/diagnostics.log")
        FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", log)
    }
}

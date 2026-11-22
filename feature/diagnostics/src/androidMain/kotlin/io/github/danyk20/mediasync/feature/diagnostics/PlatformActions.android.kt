/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.diagnostics

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import io.github.danyk20.mediasync.core.designsystem.component.resetMediaSync
import io.github.danyk20.mediasync.core.diagnostics.Area
import io.github.danyk20.mediasync.core.diagnostics.Diagnostics
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.Res
import io.github.danyk20.mediasync.feature.diagnostics.generated.resources.diagnostics_share_title
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString

@Composable
internal actual fun rememberLogSharer(): suspend (text: String) -> Unit {
    val context = LocalContext.current
    return { text -> share(context, text) }
}

@Composable
internal actual fun rememberLogSaver(text: suspend () -> String): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(text)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch { save(context, uri, current()) }
    }
    return { launcher.launch(FILE_NAME) }
}

@Composable
internal actual fun rememberResetMediaSync(): () -> Unit {
    val context = LocalContext.current
    return { context.resetMediaSync() }
}

/** Shares [text] as a file through the app picker; the file sits in the cache, which only MediaSync can read. */
private suspend fun share(context: Context, text: String) {
    val file = withContext(Dispatchers.IO) {
        File(context.cacheDir, "diagnostics").apply { mkdirs() }.resolve(FILE_NAME).apply { writeText(text) }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", file)
    val title = getString(Res.string.diagnostics_share_title)
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, title)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, title))
    Diagnostics.log(Area.APP, "Diagnostics shared")
}

/** Writes [text] where the user chose to save it. */
private suspend fun save(context: Context, uri: Uri, text: String) {
    try {
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        }
        Diagnostics.log(Area.APP, "Diagnostics saved")
    } catch (e: IOException) {
        Diagnostics.log(Area.APP, "Couldn't save the diagnostics", e)
    }
}

private const val FILE_NAME = "mediasync-diagnostics.txt"

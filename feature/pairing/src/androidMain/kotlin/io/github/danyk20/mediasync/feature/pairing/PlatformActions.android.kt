/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.pairing

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import io.github.danyk20.mediasync.feature.pairing.generated.resources.Res
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_share_chooser
import org.jetbrains.compose.resources.stringResource

@Composable
internal actual fun rememberLinkSharer(): (String) -> Unit {
    val context = LocalContext.current
    val chooserTitle = stringResource(Res.string.pairing_share_chooser)
    return { link ->
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
        context.startActivity(Intent.createChooser(send, chooserTitle))
    }
}

@Composable
internal actual fun rememberClipboardReader(): suspend () -> String {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    return {
        val data = clipboard.getClipEntry()?.clipData
        data?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
    }
}

@Composable
internal actual fun rememberQrScanner(active: Boolean, onCode: (String) -> Unit): (@Composable () -> Unit)? {
    val context = LocalContext.current
    var hasCamera by remember {
        mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val requestCamera =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
    LaunchedEffect(active) { if (active && !hasCamera) requestCamera.launch(Manifest.permission.CAMERA) }
    return if (hasCamera) {
        { QrScanner(onCode = onCode, modifier = Modifier.fillMaxSize()) }
    } else {
        null
    }
}

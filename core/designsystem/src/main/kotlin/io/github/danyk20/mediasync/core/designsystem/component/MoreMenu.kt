/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.designsystem.component

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.danyk20.mediasync.core.designsystem.R
import io.github.danyk20.mediasync.core.designsystem.icon.MediaSyncIcons

/**
 * The ⋮ menu of a home screen's top bar: what's used rarely, such as *Diagnostics*, and *Reset MediaSync*, which asks
 * first. [onReset] defaults to clearing the app's data, as Android's *Clear storage* would.
 */
@Composable
fun MoreMenu(onOpenDiagnostics: () -> Unit, onReset: (() -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(MediaSyncIcons.More), contentDescription = stringResource(R.string.more_options))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_diagnostics)) },
                onClick = {
                    open = false
                    onOpenDiagnostics()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_reset)) },
                onClick = {
                    open = false
                    confirmReset = true
                },
            )
        }
    }
    if (confirmReset) {
        ResetDialog(
            onReset = {
                confirmReset = false
                (onReset ?: { context.resetMediaSync() })()
            },
            onCancel = { confirmReset = false },
        )
    }
}

/** Asks before *Reset MediaSync*, and says what it does and doesn't remove. */
@Composable
fun ResetDialog(onReset: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.reset_title)) },
        text = { Text(stringResource(R.string.reset_body)) },
        confirmButton = {
            TextButton(onClick = onReset) {
                Text(stringResource(R.string.reset_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.reset_cancel)) } },
    )
}

/**
 * Back to how MediaSync was installed: its role, paired phones, settings and history are gone, and the app closes.
 * Photos and videos stay where they are; they aren't the app's data.
 */
fun Context.resetMediaSync() {
    getSystemService(ActivityManager::class.java)?.clearApplicationUserData()
}

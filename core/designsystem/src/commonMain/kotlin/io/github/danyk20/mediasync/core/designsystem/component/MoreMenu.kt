/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.designsystem.component

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
import io.github.danyk20.mediasync.core.designsystem.generated.resources.Res
import io.github.danyk20.mediasync.core.designsystem.generated.resources.menu_diagnostics
import io.github.danyk20.mediasync.core.designsystem.generated.resources.menu_settings
import io.github.danyk20.mediasync.core.designsystem.generated.resources.more_options
import io.github.danyk20.mediasync.core.designsystem.generated.resources.reset_body
import io.github.danyk20.mediasync.core.designsystem.generated.resources.reset_body_computer
import io.github.danyk20.mediasync.core.designsystem.generated.resources.reset_cancel
import io.github.danyk20.mediasync.core.designsystem.generated.resources.reset_confirm
import io.github.danyk20.mediasync.core.designsystem.generated.resources.reset_title
import io.github.danyk20.mediasync.core.designsystem.icon.MediaSyncIcons
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** The ⋮ menu of a home screen's top bar: what's used rarely, *Settings* and *Diagnostics*. */
@Composable
fun MoreMenu(onOpenSettings: () -> Unit, onOpenDiagnostics: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(MediaSyncIcons.More), contentDescription = stringResource(Res.string.more_options))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.menu_settings)) },
                onClick = {
                    open = false
                    onOpenSettings()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.menu_diagnostics)) },
                onClick = {
                    open = false
                    onOpenDiagnostics()
                },
            )
        }
    }
}

/** Asks before *Reset MediaSync*, and says what it does and doesn't remove. */
@Composable
fun ResetDialog(onReset: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(Res.string.reset_title)) },
        text = { Text(stringResource(deviceText(Res.string.reset_body, Res.string.reset_body_computer))) },
        confirmButton = {
            TextButton(onClick = onReset) {
                Text(stringResource(Res.string.reset_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(Res.string.reset_cancel)) } },
    )
}

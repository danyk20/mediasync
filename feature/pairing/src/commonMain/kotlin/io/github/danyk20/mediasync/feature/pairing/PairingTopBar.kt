/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.pairing

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import io.github.danyk20.mediasync.feature.pairing.generated.resources.Res
import io.github.danyk20.mediasync.feature.pairing.generated.resources.pairing_close
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PairingTopBar(title: String, onClose: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        actions = { TextButton(onClick = onClose) { Text(stringResource(Res.string.pairing_close)) } },
    )
}

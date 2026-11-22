/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import io.github.danyk20.mediasync.MainUiState
import io.github.danyk20.mediasync.navigation.MediaSyncNavigation

/**
 * @param openedLink a pairing link opened from outside the app. A producer shows it for confirmation, a
 *   consumer ignores it, and before a role is chosen it waits.
 */
@Composable
fun MediaSyncApp(
    uiState: MainUiState,
    modifier: Modifier = Modifier,
    openedLink: String? = null,
    onLinkHandled: () -> Unit = {},
) {
    when (uiState) {
        MainUiState.Loading -> Box(modifier.fillMaxSize())

        // A role change (first choice now, "change role" later) starts a fresh back stack for that role.
        is MainUiState.Ready -> key(uiState.role) {
            MediaSyncNavigation(uiState.role, openedLink, onLinkHandled, modifier)
        }
    }
}

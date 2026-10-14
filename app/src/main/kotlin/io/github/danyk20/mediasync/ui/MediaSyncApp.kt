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
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.github.danyk20.mediasync.MainUiState
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.feature.consumer.ConsumerHomeScreen
import io.github.danyk20.mediasync.feature.onboarding.RoleChoiceRoute
import io.github.danyk20.mediasync.feature.producer.ProducerHomeScreen
import io.github.danyk20.mediasync.navigation.Destination
import io.github.danyk20.mediasync.navigation.startDestinationFor

@Composable
fun MediaSyncApp(uiState: MainUiState, modifier: Modifier = Modifier) {
    when (uiState) {
        MainUiState.Loading -> Box(modifier.fillMaxSize())

        // A role change (first choice now, "change role" later) starts a fresh back stack for that role.
        is MainUiState.Ready -> key(uiState.role) {
            MediaSyncNavDisplay(role = uiState.role, modifier = modifier)
        }
    }
}

@Composable
private fun MediaSyncNavDisplay(role: DeviceRole?, modifier: Modifier = Modifier) {
    val backStack = rememberNavBackStack(startDestinationFor(role))
    NavDisplay(
        backStack = backStack,
        modifier = modifier,
        onBack = { backStack.removeLastOrNull() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<Destination.RoleChoice> { RoleChoiceRoute() }
            entry<Destination.ProducerHome> { ProducerHomeScreen() }
            entry<Destination.ConsumerHome> { ConsumerHomeScreen() }
        },
    )
}

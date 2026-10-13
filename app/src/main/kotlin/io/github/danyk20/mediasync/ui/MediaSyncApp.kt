/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.github.danyk20.mediasync.MainUiState
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.feature.consumer.ConsumerHomeRoute
import io.github.danyk20.mediasync.feature.onboarding.RoleChoiceRoute
import io.github.danyk20.mediasync.feature.pairing.ConsumerPairingRoute
import io.github.danyk20.mediasync.feature.pairing.ProducerPairingRoute
import io.github.danyk20.mediasync.feature.producer.ProducerHomeRoute
import io.github.danyk20.mediasync.navigation.Destination
import io.github.danyk20.mediasync.navigation.startDestinationFor

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
            MediaSyncNavDisplay(uiState.role, openedLink, onLinkHandled, modifier)
        }
    }
}

@Composable
private fun MediaSyncNavDisplay(
    role: DeviceRole?,
    openedLink: String?,
    onLinkHandled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backStack = rememberNavBackStack(startDestinationFor(role))
    LaunchedEffect(role, openedLink) {
        if (openedLink == null || role == null) return@LaunchedEffect
        if (role == DeviceRole.PRODUCER) backStack.add(Destination.ProducerPairing(openedLink))
        onLinkHandled()
    }
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
            entry<Destination.ProducerHome> {
                ProducerHomeRoute(onPair = { backStack.add(Destination.ProducerPairing()) })
            }
            entry<Destination.ProducerPairing> { destination ->
                ProducerPairingRoute(onDone = { backStack.removeLastOrNull() }, openedLink = destination.openedLink)
            }
            entry<Destination.ConsumerHome> {
                ConsumerHomeRoute(onAddProducer = { backStack.add(Destination.ConsumerPairing) })
            }
            entry<Destination.ConsumerPairing> { ConsumerPairingRoute(onDone = { backStack.removeLastOrNull() }) }
        },
    )
}

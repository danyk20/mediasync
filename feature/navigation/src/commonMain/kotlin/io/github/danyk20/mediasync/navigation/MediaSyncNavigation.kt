/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.feature.backup.BackupRoute
import io.github.danyk20.mediasync.feature.consumer.ConsumerHomeRoute
import io.github.danyk20.mediasync.feature.consumer.InternetSyncRoute
import io.github.danyk20.mediasync.feature.consumer.SyncScheduleRoute
import io.github.danyk20.mediasync.feature.diagnostics.DiagnosticsRoute
import io.github.danyk20.mediasync.feature.diagnostics.SettingsRoute
import io.github.danyk20.mediasync.feature.onboarding.RoleChoiceRoute
import io.github.danyk20.mediasync.feature.pairing.ConsumerPairingRoute
import io.github.danyk20.mediasync.feature.pairing.ProducerPairingRoute
import io.github.danyk20.mediasync.feature.producer.PhotoDetailsRoute
import io.github.danyk20.mediasync.feature.producer.ProducerHomeRoute
import io.github.danyk20.mediasync.feature.producer.SendingOrderRoute
import io.github.danyk20.mediasync.feature.producer.SourceFoldersRoute
import io.github.danyk20.mediasync.feature.producer.WaitingRoute
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * The saved back stack needs to know every [Destination] up front: Compose Multiplatform has no reflection to find
 * them (Android would).
 */
internal val NavigationConfiguration = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(Destination.RoleChoice::class)
            subclass(Destination.ProducerHome::class)
            subclass(Destination.ConsumerHome::class)
            subclass(Destination.ConsumerPairing::class)
            subclass(Destination.ProducerPairing::class)
            subclass(Destination.ProducerFolders::class)
            subclass(Destination.Settings::class)
            subclass(Destination.Diagnostics::class)
            subclass(Destination.ProducerPhotoDetails::class)
            subclass(Destination.ProducerSendingOrder::class)
            subclass(Destination.ProducerWaiting::class)
            subclass(Destination.ConsumerSchedule::class)
            subclass(Destination.ConsumerInternet::class)
            subclass(Destination.ConsumerBackup::class)
        }
    }
}

/** MediaSync's screens and the way between them, for [role]; [openedLink] is a pairing link to confirm. */
@Composable
fun MediaSyncNavigation(
    role: DeviceRole?,
    openedLink: String?,
    onLinkHandled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backStack = rememberNavBackStack(NavigationConfiguration, startDestinationFor(role))
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
                ProducerHomeRoute(
                    onPair = { backStack.add(Destination.ProducerPairing()) },
                    onChooseFolders = { backStack.add(Destination.ProducerFolders) },
                    onChooseDetails = { backStack.add(Destination.ProducerPhotoDetails) },
                    onChooseOrder = { backStack.add(Destination.ProducerSendingOrder) },
                    onOpenMediaSyncSettings = { backStack.add(Destination.Settings) },
                    onOpenDiagnostics = { backStack.add(Destination.Diagnostics) },
                    onSeeWaiting = { leftOut -> backStack.add(Destination.ProducerWaiting(leftOut)) },
                )
            }
            entry<Destination.ProducerWaiting> { destination ->
                WaitingRoute(onDone = { backStack.removeLastOrNull() }, leftOutFirst = destination.leftOut)
            }
            entry<Destination.ProducerFolders> { SourceFoldersRoute(onDone = { backStack.removeLastOrNull() }) }
            entry<Destination.ProducerPhotoDetails> { PhotoDetailsRoute(onDone = { backStack.removeLastOrNull() }) }
            entry<Destination.ProducerSendingOrder> { SendingOrderRoute(onDone = { backStack.removeLastOrNull() }) }
            entry<Destination.Settings> { SettingsRoute(onDone = { backStack.removeLastOrNull() }) }
            entry<Destination.Diagnostics> { DiagnosticsRoute(onDone = { backStack.removeLastOrNull() }) }
            entry<Destination.ProducerPairing> { destination ->
                ProducerPairingRoute(onDone = { backStack.removeLastOrNull() }, openedLink = destination.openedLink)
            }
            entry<Destination.ConsumerHome> {
                ConsumerHomeRoute(
                    onAddProducer = { backStack.add(Destination.ConsumerPairing) },
                    onChangeSchedule = { backStack.add(Destination.ConsumerSchedule) },
                    onSetUpInternet = { backStack.add(Destination.ConsumerInternet) },
                    onOpenBackup = { backStack.add(Destination.ConsumerBackup) },
                    onOpenMediaSyncSettings = { backStack.add(Destination.Settings) },
                    onOpenDiagnostics = { backStack.add(Destination.Diagnostics) },
                )
            }
            entry<Destination.ConsumerSchedule> { SyncScheduleRoute(onDone = { backStack.removeLastOrNull() }) }
            entry<Destination.ConsumerInternet> { InternetSyncRoute(onDone = { backStack.removeLastOrNull() }) }
            entry<Destination.ConsumerBackup> { BackupRoute(onDone = { backStack.removeLastOrNull() }) }
            entry<Destination.ConsumerPairing> { ConsumerPairingRoute(onDone = { backStack.removeLastOrNull() }) }
        },
    )
}

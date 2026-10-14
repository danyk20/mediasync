/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.navigation

import androidx.navigation3.runtime.NavKey
import io.github.danyk20.mediasync.core.model.DeviceRole
import kotlinx.serialization.Serializable

/** Every screen in the app. Keys are serializable so the back stack survives process death. */
@Serializable
sealed interface Destination : NavKey {
    @Serializable
    data object RoleChoice : Destination

    @Serializable
    data object ProducerHome : Destination

    @Serializable
    data object ConsumerHome : Destination

    /** The consumer shows its pairing code. */
    @Serializable
    data object ConsumerPairing : Destination

    /** The producer scans the consumer's pairing code, or confirms an [openedLink]. */
    @Serializable
    data class ProducerPairing(val openedLink: String? = null) : Destination

    /** The producer chooses which folders to send. */
    @Serializable
    data object ProducerFolders : Destination

    /** The consumer sets when its producers sync. */
    @Serializable
    data object ConsumerSchedule : Destination
}

/** Where the app starts for a device with the given role. */
fun startDestinationFor(role: DeviceRole?): Destination = when (role) {
    null -> Destination.RoleChoice
    DeviceRole.PRODUCER -> Destination.ProducerHome
    DeviceRole.CONSUMER -> Destination.ConsumerHome
}

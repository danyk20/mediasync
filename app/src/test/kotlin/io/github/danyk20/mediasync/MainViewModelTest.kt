/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync

import app.cash.turbine.test
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.testing.FakeSettingsRepository
import io.github.danyk20.mediasync.core.testing.MainDispatcherRule
import io.github.danyk20.mediasync.navigation.Destination
import io.github.danyk20.mediasync.navigation.startDestinationFor
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class MainViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun withoutRole_isReadyForOnboarding() = runTest {
        val viewModel = MainViewModel(FakeSettingsRepository(initialRole = null))

        viewModel.uiState.test {
            awaitItem() shouldBe MainUiState.Ready(role = null)
        }
    }

    @Test
    fun followsRoleChanges() = runTest {
        val settings = FakeSettingsRepository()
        val viewModel = MainViewModel(settings)

        viewModel.uiState.test {
            awaitItem() shouldBe MainUiState.Ready(role = null)
            settings.setRole(DeviceRole.CONSUMER)
            awaitItem() shouldBe MainUiState.Ready(role = DeviceRole.CONSUMER)
        }
    }

    @Test
    fun startDestination_matchesRole() {
        startDestinationFor(null) shouldBe Destination.RoleChoice
        startDestinationFor(DeviceRole.PRODUCER) shouldBe Destination.ProducerHome
        startDestinationFor(DeviceRole.CONSUMER) shouldBe Destination.ConsumerHome
    }
}

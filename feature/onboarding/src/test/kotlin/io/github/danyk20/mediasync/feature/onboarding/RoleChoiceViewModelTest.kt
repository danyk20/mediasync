/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.onboarding

import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.testing.FakeSettingsRepository
import io.github.danyk20.mediasync.core.testing.MainDispatcherRule
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test

class RoleChoiceViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val settings = FakeSettingsRepository()
    private val viewModel = RoleChoiceViewModel(settings)

    @Test
    fun choosingProducer_storesProducerRole() {
        viewModel.chooseRole(DeviceRole.PRODUCER)

        settings.role.value shouldBe DeviceRole.PRODUCER
    }

    @Test
    fun choosingConsumer_storesConsumerRole() {
        viewModel.chooseRole(DeviceRole.CONSUMER)

        settings.role.value shouldBe DeviceRole.CONSUMER
    }
}

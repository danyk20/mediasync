/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreSettingsRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun roleIsNullUntilChosen() = runTest {
        val repository = DataStoreSettingsRepository(dataStore())

        repository.role.first().shouldBeNull()
    }

    @Test
    fun storesTheChosenRole() = runTest {
        val repository = DataStoreSettingsRepository(dataStore())

        repository.setRole(DeviceRole.CONSUMER)

        repository.role.first() shouldBe DeviceRole.CONSUMER
    }

    @Test
    fun changingTheRoleReplacesThePreviousOne() = runTest {
        val repository = DataStoreSettingsRepository(dataStore())

        repository.setRole(DeviceRole.CONSUMER)
        repository.setRole(DeviceRole.PRODUCER)

        repository.role.first() shouldBe DeviceRole.PRODUCER
    }

    @Test
    fun treatsAnUnknownStoredRoleAsNotChosen() = runTest {
        val dataStore = dataStore()
        dataStore.edit { it[DataStoreSettingsRepository.RoleKey] = "ROLE_FROM_A_NEWER_VERSION" }

        DataStoreSettingsRepository(dataStore).role.first().shouldBeNull()
    }

    private fun TestScope.dataStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = backgroundScope,
        produceFile = { File(tempFolder.root, "settings.preferences_pb") },
    )
}

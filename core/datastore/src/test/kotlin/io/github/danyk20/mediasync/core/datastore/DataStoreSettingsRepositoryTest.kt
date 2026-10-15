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
import io.github.danyk20.mediasync.core.model.PhotoDetails
import io.github.danyk20.mediasync.core.model.SourcePreset
import io.github.danyk20.mediasync.core.model.SourceSelection
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import java.time.LocalTime
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

    @Test
    fun sendsTheCameraFolderUntilFoldersAreChosen() = runTest {
        DataStoreSettingsRepository(dataStore()).sourceSelection.first() shouldBe SourceSelection.Default
    }

    @Test
    fun storesAPresetOrHandPickedFolders() = runTest {
        val repository = DataStoreSettingsRepository(dataStore())

        repository.setSourceSelection(SourceSelection(SourcePreset.EVERYTHING))
        repository.sourceSelection.first() shouldBe SourceSelection(SourcePreset.EVERYTHING)

        val chosen = SourceSelection.of(setOf("DCIM/Camera/", "Pictures/Screenshots/"))
        repository.setSourceSelection(chosen)
        repository.sourceSelection.first() shouldBe chosen

        repository.setSourceSelection(SourceSelection.of(emptySet()))
        repository.sourceSelection.first() shouldBe SourceSelection.of(emptySet())
    }

    @Test
    fun choosingAPresetForgetsHandPickedFolders() = runTest {
        val dataStore = dataStore()
        val repository = DataStoreSettingsRepository(dataStore)

        repository.setSourceSelection(SourceSelection.of(setOf("DCIM/Camera/")))
        repository.setSourceSelection(SourceSelection(SourcePreset.CAMERA_AND_SCREENSHOTS))

        repository.sourceSelection.first() shouldBe SourceSelection(SourcePreset.CAMERA_AND_SCREENSHOTS)
        dataStore.data.first()[DataStoreSettingsRepository.SourceFoldersKey] shouldBe emptySet()
    }

    @Test
    fun treatsAnUnknownStoredPresetAsTheDefault() = runTest {
        val dataStore = dataStore()
        dataStore.edit { it[DataStoreSettingsRepository.SourcePresetKey] = "PRESET_FROM_A_NEWER_VERSION" }

        DataStoreSettingsRepository(dataStore).sourceSelection.first() shouldBe SourceSelection.Default
    }

    @Test
    fun theScheduleIsDailyAtThreeUntilChanged() = runTest {
        val repository = DataStoreSettingsRepository(dataStore())

        repository.syncSchedule.first() shouldBe SyncSchedule.Default

        repository.setSyncSchedule(SyncSchedule.EveryHours(4, LocalTime.of(1, 0)))
        repository.syncSchedule.first() shouldBe SyncSchedule.EveryHours(4, LocalTime.of(1, 0))
    }

    @Test
    fun anUnreadableStoredScheduleFallsBackToTheDefault() = runTest {
        val dataStore = dataStore()
        dataStore.edit { it[DataStoreSettingsRepository.SyncScheduleKey] = "fortnightly@03:00" }

        DataStoreSettingsRepository(dataStore).syncSchedule.first() shouldBe SyncSchedule.Default
    }

    @Test
    fun photosAreSentWithAllTheirDetailsUntilTheUserChooses() = runTest {
        val dataStore = dataStore()
        val repository = DataStoreSettingsRepository(dataStore)
        repository.photoDetails.first() shouldBe PhotoDetails.KEEP_ALL

        repository.setPhotoDetails(PhotoDetails.REMOVE_LOCATION)
        repository.photoDetails.first() shouldBe PhotoDetails.REMOVE_LOCATION

        dataStore.edit { it[DataStoreSettingsRepository.PhotoDetailsKey] = "FROM_A_NEWER_VERSION" }
        repository.photoDetails.first() shouldBe PhotoDetails.KEEP_ALL
    }

    @Test
    fun internetSyncHasNoWorkerAndStaysOffMobileDataUntilSetUp() = runTest {
        val repository = DataStoreSettingsRepository(dataStore())
        repository.rendezvousUrl.first() shouldBe null
        repository.internetOverMobileData.first() shouldBe false
        repository.videosOverRelay.first() shouldBe false
        repository.mobileDataCapBytes.first() shouldBe null
        repository.setMobileDataCapBytes(2_000_000_000)
        repository.mobileDataCapBytes.first() shouldBe 2_000_000_000
        repository.setMobileDataCapBytes(null)
        repository.mobileDataCapBytes.first() shouldBe null

        repository.setRendezvousUrl("https://mediasync.anna.workers.dev")
        repository.setInternetOverMobileData(true)
        repository.setVideosOverRelay(true)
        repository.videosOverRelay.first() shouldBe true
        repository.rendezvousUrl.first() shouldBe "https://mediasync.anna.workers.dev"
        repository.internetOverMobileData.first() shouldBe true

        repository.setRendezvousUrl(null)
        repository.rendezvousUrl.first() shouldBe null
    }

    @Test
    fun aDismissedSetupCardStaysDismissed() = runTest {
        val dataStore = dataStore()
        val repository = DataStoreSettingsRepository(dataStore)
        repository.dismissedPrompts.first() shouldBe emptySet()

        repository.dismissPrompt(SetupPrompt.NOTIFICATIONS)
        repository.dismissPrompt(SetupPrompt.BATTERY)
        repository.dismissPrompt(SetupPrompt.NOTIFICATIONS)

        repository.dismissedPrompts.first() shouldBe setOf(SetupPrompt.NOTIFICATIONS, SetupPrompt.BATTERY)
    }

    @Test
    fun ignoresSetupCardsItDoesntKnow() = runTest {
        val dataStore = dataStore()
        dataStore.edit {
            it[DataStoreSettingsRepository.DismissedPromptsKey] = setOf("BATTERY", "FROM_A_NEWER_VERSION")
        }

        DataStoreSettingsRepository(dataStore).dismissedPrompts.first() shouldBe setOf(SetupPrompt.BATTERY)
    }

    private fun TestScope.dataStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = backgroundScope,
        produceFile = { File(tempFolder.root, "settings.preferences_pb") },
    )
}

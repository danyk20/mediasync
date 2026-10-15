/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.backup

import android.app.Application
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.danyk20.mediasync.connector.CleanupShortcut
import io.github.danyk20.mediasync.connector.ConnectorId
import io.github.danyk20.mediasync.connector.DeviceAppProfile
import io.github.danyk20.mediasync.connector.FolderLayout
import io.github.danyk20.mediasync.connector.SetupStep
import io.github.danyk20.mediasync.connector.android.BackupOverview
import io.github.danyk20.mediasync.connector.android.CloudBackup
import io.github.danyk20.mediasync.connector.android.InstalledApps
import io.github.danyk20.mediasync.connector.testing.FakeDeviceAppFactory
import io.github.danyk20.mediasync.connector.testing.account
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.testing.MainDispatcherRule
import io.github.danyk20.mediasync.core.testing.captureScreenshot
import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class BackupTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val composeRule = createComposeRule()

    private val factory = FakeDeviceAppFactory(profile = GOOGLE_PHOTOS)
    private val overviews = MutableStateFlow<List<BackupOverview>>(emptyList())
    private val backup = mockk<CloudBackup>(relaxed = true) {
        every { deviceApps } returns listOf(factory)
        every { overviews() } returns overviews
    }
    private var installed = true
    private val viewModel by lazy { BackupViewModel(backup, InstalledApps { installed }) }
    private val events = mutableListOf<String>()

    @Test
    fun eachAppThatCanBackUpIsListedWithWhetherItIsInstalled() = runBlocking<Unit> {
        installed = false

        state().apps shouldBe listOf(AppBackup(factory.descriptor.id, GOOGLE_PHOTOS, installed = false))
    }

    @Test
    fun anAppInstalledMeanwhileIsNoticedOnReturn() = runBlocking<Unit> {
        installed = false
        state().apps.single().installed shouldBe false
        installed = true

        viewModel.refresh()

        state().apps.single().installed shouldBe true
    }

    @Test
    fun anAppSetUpShowsHowItsBackupsStand() = runBlocking<Unit> {
        val overview = overview()

        overviews.value = listOf(overview)

        viewModel.uiState.filterNotNull().first { it.apps.single().overview != null }.apps.single().overview shouldBe
            overview
    }

    @Test
    fun settingUpConnectsTheAppAndStoppingDisconnectsIt() {
        viewModel.connect(factory.descriptor.id)
        viewModel.connect(ConnectorId("not-here")) // Nothing to connect.
        viewModel.disconnect(AppBackup(factory.descriptor.id, GOOGLE_PHOTOS, installed = true, overview()))
        viewModel.disconnect(AppBackup(factory.descriptor.id, GOOGLE_PHOTOS, installed = true)) // Not set up.

        coVerify(exactly = 1) { backup.connect(any()) }
        coVerify(exactly = 1) { backup.connect(factory) }
        coVerify(exactly = 1) { backup.disconnect(account("test-app").id) }
    }

    @Test
    fun theSetupCardGuidesThroughTheAppAndConnectsIt() {
        show(BackupUiState(listOf(AppBackup(factory.descriptor.id, GOOGLE_PHOTOS, installed = true))))

        composeRule.onNodeWithText("Turn on backup for the MediaSync folder", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Choose Storage saver", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Open Google Photos").performScrollTo().performClick()
        composeRule.onNodeWithText("It's set up").performScrollTo().performClick()

        events shouldBe listOf("open com.google.android.apps.photos", "connect test-app")
    }

    @Test
    fun anAppThatIsntInstalledIsOfferedFromTheStore() {
        show(BackupUiState(listOf(AppBackup(factory.descriptor.id, GOOGLE_PHOTOS, installed = false))))

        composeRule.onNodeWithText("It's set up").assertDoesNotExist()
        composeRule.onNodeWithText("Install Google Photos").performScrollTo().performClick()

        events shouldBe listOf("install com.google.android.apps.photos")
    }

    @Test
    fun theStatusSaysWhatWasHandedOverAndWhereToFreeUpSpace() {
        show(status())

        composeRule.onNodeWithText("214 handed over today").assertIsDisplayed()
        composeRule.onNodeWithText("1,830 backed up and removed from this phone").assertIsDisplayed()
        composeRule.onNodeWithText("Open Google Photos").performScrollTo().performClick()
        composeRule.onNodeWithText("Storage settings").performScrollTo().performClick()
        composeRule.onNodeWithText("Stop backing up").performScrollTo().performClick()

        events shouldBe listOf("open com.google.android.apps.photos", "storage", "disconnect test-app")
    }

    @Test
    fun aBuildWithoutBackupSaysSo() {
        show(BackupUiState())

        composeRule.onNodeWithText("This version of MediaSync has no backup to set up.").assertIsDisplayed()
    }

    @Test
    fun otherAppsAndSettingsOpenWherePossible() {
        val context = ApplicationProvider.getApplicationContext<Application>()

        context.openApp("com.example.not.installed") shouldBe false
        context.openStoreListing("com.google.android.apps.photos") shouldBe true
        shadowOf(context).nextStartedActivity.data.toString() shouldBe
            "market://details?id=com.google.android.apps.photos"
        context.openStorageSettings() shouldBe true
        shadowOf(context).nextStartedActivity.action shouldBe Settings.ACTION_INTERNAL_STORAGE_SETTINGS
        shadowOf(context).nextStartedActivity shouldBe null
    }

    @Test
    fun screenshot_setup_light() = screenshot(
        BackupUiState(listOf(AppBackup(factory.descriptor.id, GOOGLE_PHOTOS, installed = true))),
        "backup_setup_light",
    )

    @Test
    fun screenshot_notInstalled_light() = screenshot(
        BackupUiState(listOf(AppBackup(factory.descriptor.id, GOOGLE_PHOTOS, installed = false))),
        "backup_not_installed_light",
    )

    @Test
    fun screenshot_status_dark() = screenshot(status(), "backup_status_dark", darkTheme = true)

    private suspend fun state(): BackupUiState = viewModel.uiState.filterNotNull().first()

    private fun overview() = BackupOverview(
        account = account("test-app"),
        handedOver = 12_403,
        removedByApp = 1_830,
        today = 214,
        waiting = 3,
    )

    private fun status() =
        BackupUiState(listOf(AppBackup(factory.descriptor.id, GOOGLE_PHOTOS, installed = true, overview())))

    private fun screenshot(state: BackupUiState, name: String, darkTheme: Boolean = false) {
        show(state, darkTheme)
        composeRule.onRoot().captureScreenshot(name)
    }

    private fun show(state: BackupUiState, darkTheme: Boolean = false) {
        composeRule.setContent {
            MediaSyncTheme(darkTheme = darkTheme, dynamicColor = false) {
                BackupScreen(
                    state = state,
                    actions = BackupActions(
                        openApp = { events += "open ${it.packageName}" },
                        install = { events += "install ${it.packageName}" },
                        openStorageSettings = { events += "storage" },
                        connect = { events += "connect $it" },
                        disconnect = { events += "disconnect ${it.overview?.account?.id}" },
                    ),
                    onDone = { events += "done" },
                )
            }
        }
    }

    private companion object {
        val GOOGLE_PHOTOS = DeviceAppProfile(
            packageName = "com.google.android.apps.photos",
            appName = "Google Photos",
            folder = "DCIM/MediaSync",
            layout = FolderLayout.SINGLE_FOLDER,
            setup = listOf(SetupStep.BACK_UP_FOLDER, SetupStep.STORAGE_SAVER),
            cleanup = listOf(CleanupShortcut.APP_FREE_UP_SPACE, CleanupShortcut.SYSTEM_SMART_STORAGE),
        )
    }
}

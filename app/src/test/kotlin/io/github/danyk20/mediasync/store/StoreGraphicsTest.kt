/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.store

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.danyk20.mediasync.R
import io.github.danyk20.mediasync.connector.AccountId
import io.github.danyk20.mediasync.connector.ConnectorAccount
import io.github.danyk20.mediasync.connector.android.BackupOverview
import io.github.danyk20.mediasync.connector.googlephotos.GooglePhotosAppConnectorFactory
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.feature.backup.AppBackup
import io.github.danyk20.mediasync.feature.backup.BackupActions
import io.github.danyk20.mediasync.feature.backup.BackupScreen
import io.github.danyk20.mediasync.feature.backup.BackupUiState
import io.github.danyk20.mediasync.feature.consumer.BackupLine
import io.github.danyk20.mediasync.feature.consumer.ConsumerHomeScreen
import io.github.danyk20.mediasync.feature.consumer.ConsumerHomeUiState
import io.github.danyk20.mediasync.feature.consumer.IncomingTransfer
import io.github.danyk20.mediasync.feature.consumer.InternetSyncActions
import io.github.danyk20.mediasync.feature.consumer.InternetSyncScreen
import io.github.danyk20.mediasync.feature.consumer.InternetSyncUiState
import io.github.danyk20.mediasync.feature.producer.ProducerHomeScreen
import io.github.danyk20.mediasync.feature.producer.ProducerHomeUiState
import io.github.danyk20.mediasync.sync.android.ProducerState
import io.github.danyk20.mediasync.sync.android.RelayUsage
import io.github.danyk20.mediasync.sync.engine.TransferProgress
import java.time.Instant
import java.time.ZonedDateTime
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Google Play store graphics (docs/play): screenshots of the real screens with sample data, the 512 px icon and
 * the feature graphic. Rendered only when asked, with `-Pmediasync.storeGraphics` (docs/play/README.md), into
 * docs/play/graphics.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-xxhdpi") // 1080 × 1920: Play takes 9:16 phone screenshots.
class StoreGraphicsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun onlyWhenAsked() = assumeTrue("Run with -Pmediasync.storeGraphics", System.getProperty(PROPERTY) == "true")

    @Test
    fun phone1Sending() = capture("phone-1-sending") {
        ProducerHomeScreen(
            state = ProducerHomeUiState(
                consumerName = "Pixel 2 XL",
                canReadLibrary = true,
                canReadLocation = true,
                schedule = SyncSchedule.Default,
                nextSyncAt = ZonedDateTime.parse("2026-10-16T03:00+02:00[Europe/Zurich]"),
                now = ZonedDateTime.parse("2026-10-15T20:00+02:00[Europe/Zurich]"),
                sync = ProducerState.Syncing(TransferProgress(128, 214, 1_210_000_000, 2_040_000_000, "PXL_1.jpg")),
            ),
            onPair = {},
            onChooseFolders = {},
            onSyncNow = {},
            onStop = {},
            onGrantAccess = {},
            onOpenSettings = {},
        )
    }

    @Test
    fun phone2Receiving() = capture("phone-2-receiving") {
        ConsumerHomeScreen(
            state = ConsumerHomeUiState(
                listening = true,
                transfer = IncomingTransfer("Anna's Pixel", 128, 214, 1_210_000_000, 2_040_000_000),
                producers = listOf("Anna's Pixel", "Tom's Galaxy"),
                receivedCount = 18_203,
                schedule = SyncSchedule.Default,
                internetSync = true,
                relay = RelayUsage(12_000_000_000, 950_000_000_000, 1_000_000_000_000, true, null),
                backup = BackupLine.On("Google Photos", 214),
            ),
            onAddProducer = {},
            onChangeSchedule = {},
        )
    }

    @Test
    fun phone3Backup() = capture("phone-3-backup") {
        BackupScreen(
            state = BackupUiState(
                listOf(
                    AppBackup(
                        connector = GooglePhotosAppConnectorFactory.ID,
                        profile = GooglePhotosAppConnectorFactory.PROFILE,
                        installed = true,
                        overview = BackupOverview(
                            account = ConnectorAccount(
                                AccountId("google-photos-app"),
                                GooglePhotosAppConnectorFactory.ID,
                                "Google Photos",
                                createdAtMillis = 0,
                            ),
                            handedOver = 12_403,
                            removedByApp = 5_800,
                            today = 214,
                        ),
                    ),
                ),
            ),
            actions = BackupActions(),
            onDone = {},
        )
    }

    @Test
    fun phone4Internet() = capture("phone-4-internet") {
        InternetSyncScreen(
            state = InternetSyncUiState(
                worker = "https://mediasync-rendezvous.anna.workers.dev",
                reachable = true,
                relay = RelayUsage(
                    usedBytes = 12_000_000_000,
                    limitBytes = 950_000_000_000,
                    freeBytes = 1_000_000_000_000,
                    available = true,
                    resetsAt = Instant.parse("2026-11-01T00:00:00Z"),
                ),
            ),
            actions = InternetSyncActions(),
            onDone = {},
        )
    }

    /**
     * The launcher icon, full bleed: Play rounds the corners itself. Its symbol is drawn larger than on a phone, whose
     * launcher may cut the icon down to a circle inside the 72 dp safe zone.
     */
    @Test
    @Config(qualifiers = "w512dp-h512dp-mdpi")
    fun icon() = capture("icon-512") {
        Box(Modifier.fillMaxSize().background(colorResource(R.color.ic_launcher_background))) {
            Symbol(Modifier.fillMaxSize())
        }
    }

    @Test
    @Config(qualifiers = "w1024dp-h500dp-mdpi")
    fun featureGraphic() = capture("feature-graphic") {
        val brand = colorResource(R.color.ic_launcher_background)
        Row(
            modifier = Modifier.fillMaxSize().background(brand).padding(horizontal = 72.dp),
            horizontalArrangement = Arrangement.spacedBy(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(220.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f))) {
                Symbol(Modifier.fillMaxSize())
            }
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("MediaSync", color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Your photos, from your phones to one phone you own.\nDirect, end-to-end encrypted, no cloud.",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 28.sp,
                    lineHeight = 38.sp,
                )
            }
        }
    }

    /** The launcher icon's foreground, enlarged so its safe zone fills the space. */
    @Composable
    private fun Symbol(modifier: Modifier) = Image(
        painter = painterResource(R.drawable.ic_launcher_foreground),
        contentDescription = null,
        modifier = modifier.graphicsLayer(scaleX = SYMBOL_SCALE, scaleY = SYMBOL_SCALE),
    )

    private fun capture(name: String, content: @Composable () -> Unit) {
        composeRule.setContent { MediaSyncTheme(dynamicColor = false) { content() } }
        composeRule.onRoot().captureRoboImage(filePath = "$FOLDER/$name.png")
    }

    private companion object {
        const val PROPERTY = "mediasync.storeGraphics"
        const val FOLDER = "../docs/play/graphics"

        /** An adaptive icon's 108 dp layer, of which the 72 dp safe zone fills the space. */
        const val SYMBOL_SCALE = 1.5f
    }
}

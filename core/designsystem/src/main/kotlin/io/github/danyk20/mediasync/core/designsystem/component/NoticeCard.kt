/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.danyk20.mediasync.core.designsystem.R
import io.github.danyk20.mediasync.core.designsystem.icon.MediaSyncIcons
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme

/**
 * A card that asks the user to do something, below the status card: allow notifications, free up space…
 * [warning] marks something that stops syncs; [onDismiss] adds *Not now*.
 */
@Composable
fun NoticeCard(
    title: String,
    body: String,
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {},
    onDismiss: (() -> Unit)? = null,
    warning: Boolean = false,
) {
    val colors = if (warning) {
        CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    } else {
        CardDefaults.outlinedCardColors()
    }
    OutlinedCard(modifier = modifier.fillMaxWidth(), colors = colors) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics {
                            heading()
                        },
                    )
                    Text(text = body, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (action != null || onDismiss != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    onDismiss?.let { TextButton(onClick = it) { Text(stringResource(R.string.notice_not_now)) } }
                    action?.let {
                        val buttonColors = if (warning) {
                            ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            )
                        } else {
                            ButtonDefaults.filledTonalButtonColors()
                        }
                        FilledTonalButton(onClick = onAction, colors = buttonColors) { Text(it) }
                    }
                }
            }
        }
    }
}

/**
 * The cards asking for what background syncs need (docs/PLAN.md §10.3): notifications on Android 13+, to show a
 * sync's progress, and *Unrestricted* battery use on Android 12+, so syncs can run at night.
 */
@Composable
fun BackgroundSetupNotices(
    askNotifications: Boolean,
    askBattery: Boolean,
    onAllowNotifications: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onDismissNotifications: () -> Unit,
    onDismissBattery: () -> Unit,
) {
    if (askBattery) {
        NoticeCard(
            title = stringResource(R.string.setup_battery_title),
            body = stringResource(R.string.setup_battery_body),
            icon = MediaSyncIcons.Battery,
            action = stringResource(R.string.setup_battery_open),
            onAction = onOpenBatterySettings,
            onDismiss = onDismissBattery,
        )
    }
    if (askNotifications) {
        NoticeCard(
            title = stringResource(R.string.setup_notifications_title),
            body = stringResource(R.string.setup_notifications_body),
            icon = MediaSyncIcons.Notifications,
            action = stringResource(R.string.setup_notifications_allow),
            onAction = onAllowNotifications,
            onDismiss = onDismissNotifications,
        )
    }
}

@Preview
@Composable
private fun NoticeCardPreview() {
    MediaSyncTheme(dynamicColor = false) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            BackgroundSetupNotices(
                askNotifications = true,
                askBattery = true,
                onAllowNotifications = {},
                onOpenBatterySettings = {},
                onDismissNotifications = {},
                onDismissBattery = {},
            )
            NoticeCard(
                title = "Storage full",
                body = "Free up space to receive more.",
                icon = MediaSyncIcons.Warning,
                warning = true,
            )
        }
    }
}

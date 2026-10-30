/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.danyk20.mediasync.core.designsystem.icon.MediaSyncIcons
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme

/**
 * The large status card at the top of each home screen: answers "is everything safe?" at a glance. [actionLabel]: a
 * button under the text, for a closer look at what the card says.
 */
@Composable
fun StatusCard(
    title: String,
    body: String,
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    tone: StatusTone = StatusTone.Neutral,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    val colors = when (tone) {
        StatusTone.Positive -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )

        StatusTone.Neutral -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        )
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        colors = colors,
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(28.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleLarge)
                Text(text = body, style = MaterialTheme.typography.bodyMedium)
                if (actionLabel != null) {
                    val padding = ButtonDefaults.TextButtonContentPadding
                    TextButton(
                        onClick = onAction,
                        // Its label lines up with the text above.
                        modifier = Modifier.offset(x = -padding.calculateStartPadding(LocalLayoutDirection.current)),
                    ) { Text(actionLabel) }
                }
            }
        }
    }
}

@Preview
@Composable
private fun StatusCardPreview() {
    MediaSyncTheme(dynamicColor = false) {
        StatusCard(title = "Up to date", body = "Next sync tonight at 03:00", icon = MediaSyncIcons.Info)
    }
}

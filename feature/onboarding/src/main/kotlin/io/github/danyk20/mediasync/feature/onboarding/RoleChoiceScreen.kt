/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.danyk20.mediasync.core.designsystem.component.ChoiceCard
import io.github.danyk20.mediasync.core.designsystem.icon.MediaSyncIcons
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.model.DeviceRole

@Composable
fun RoleChoiceRoute(modifier: Modifier = Modifier, viewModel: RoleChoiceViewModel = hiltViewModel()) {
    RoleChoiceScreen(onRoleChosen = viewModel::chooseRole, modifier = modifier)
}

/** First-launch screen: decides whether this phone sends (producer) or receives (consumer). */
@Composable
fun RoleChoiceScreen(onRoleChosen: (DeviceRole) -> Unit, modifier: Modifier = Modifier) {
    Scaffold(modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.onboarding_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.onboarding_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            ChoiceCard(
                title = stringResource(R.string.onboarding_role_producer_title),
                description = stringResource(R.string.onboarding_role_producer_description),
                icon = MediaSyncIcons.Send,
                onClick = { onRoleChosen(DeviceRole.PRODUCER) },
            )
            ChoiceCard(
                title = stringResource(R.string.onboarding_role_consumer_title),
                description = stringResource(R.string.onboarding_role_consumer_description),
                icon = MediaSyncIcons.Receive,
                onClick = { onRoleChosen(DeviceRole.CONSUMER) },
            )
            Text(
                text = stringResource(R.string.onboarding_change_later),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview
@Composable
private fun RoleChoiceScreenPreview() {
    MediaSyncTheme(dynamicColor = false) {
        RoleChoiceScreen(onRoleChosen = {})
    }
}

/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.zacsweers.metrox.viewmodel.metroViewModel
import io.github.danyk20.mediasync.core.designsystem.component.ChoiceCard
import io.github.danyk20.mediasync.core.designsystem.icon.MediaSyncIcons
import io.github.danyk20.mediasync.core.designsystem.theme.MediaSyncTheme
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.feature.onboarding.generated.resources.Res
import io.github.danyk20.mediasync.feature.onboarding.generated.resources.onboarding_change_later
import io.github.danyk20.mediasync.feature.onboarding.generated.resources.onboarding_how_pair
import io.github.danyk20.mediasync.feature.onboarding.generated.resources.onboarding_how_receive
import io.github.danyk20.mediasync.feature.onboarding.generated.resources.onboarding_how_sync
import io.github.danyk20.mediasync.feature.onboarding.generated.resources.onboarding_how_title
import io.github.danyk20.mediasync.feature.onboarding.generated.resources.onboarding_question
import io.github.danyk20.mediasync.feature.onboarding.generated.resources.onboarding_role_consumer_description
import io.github.danyk20.mediasync.feature.onboarding.generated.resources.onboarding_role_consumer_title
import io.github.danyk20.mediasync.feature.onboarding.generated.resources.onboarding_role_producer_description
import io.github.danyk20.mediasync.feature.onboarding.generated.resources.onboarding_role_producer_title
import io.github.danyk20.mediasync.feature.onboarding.generated.resources.onboarding_subtitle
import io.github.danyk20.mediasync.feature.onboarding.generated.resources.onboarding_title
import org.jetbrains.compose.resources.stringResource

@Composable
fun RoleChoiceRoute(modifier: Modifier = Modifier, viewModel: RoleChoiceViewModel = metroViewModel()) {
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
                text = stringResource(Res.string.onboarding_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(Res.string.onboarding_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HowItWorks()
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(Res.string.onboarding_question),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            ChoiceCard(
                title = stringResource(Res.string.onboarding_role_producer_title),
                description = stringResource(Res.string.onboarding_role_producer_description),
                icon = MediaSyncIcons.Send,
                onClick = { onRoleChosen(DeviceRole.PRODUCER) },
            )
            ChoiceCard(
                title = stringResource(Res.string.onboarding_role_consumer_title),
                description = stringResource(Res.string.onboarding_role_consumer_description),
                icon = MediaSyncIcons.Receive,
                onClick = { onRoleChosen(DeviceRole.CONSUMER) },
            )
            Text(
                text = stringResource(Res.string.onboarding_change_later),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The three steps from two phones to synced photos, before choosing this phone's part in them. */
@Composable
private fun HowItWorks() {
    Text(
        text = stringResource(Res.string.onboarding_how_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
    listOf(Res.string.onboarding_how_receive, Res.string.onboarding_how_pair, Res.string.onboarding_how_sync)
        .forEachIndexed { index, step ->
            // Read out as one: "1. Choose one phone to receive…".
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.semantics(mergeDescendants = true) {},
            ) {
                Text(
                    text = "${index + 1}.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(text = stringResource(step), style = MaterialTheme.typography.bodyLarge)
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

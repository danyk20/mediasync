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
import androidx.compose.runtime.staticCompositionLocalOf
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
import io.github.danyk20.mediasync.feature.onboarding.generated.resources.onboarding_computer_change_later
import io.github.danyk20.mediasync.feature.onboarding.generated.resources.onboarding_computer_consumer_description
import io.github.danyk20.mediasync.feature.onboarding.generated.resources.onboarding_computer_consumer_title
import io.github.danyk20.mediasync.feature.onboarding.generated.resources.onboarding_computer_how_pair
import io.github.danyk20.mediasync.feature.onboarding.generated.resources.onboarding_computer_how_receive
import io.github.danyk20.mediasync.feature.onboarding.generated.resources.onboarding_computer_how_sync
import io.github.danyk20.mediasync.feature.onboarding.generated.resources.onboarding_computer_producer_description
import io.github.danyk20.mediasync.feature.onboarding.generated.resources.onboarding_computer_producer_title
import io.github.danyk20.mediasync.feature.onboarding.generated.resources.onboarding_computer_question
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
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** `true` where the app runs on a computer (the desktop app sets it): the screen then says "computer", not "phone". */
val LocalIsComputer = staticCompositionLocalOf { false }

@Composable
fun RoleChoiceRoute(modifier: Modifier = Modifier, viewModel: RoleChoiceViewModel = metroViewModel()) {
    RoleChoiceScreen(onRoleChosen = viewModel::chooseRole, modifier = modifier)
}

/** The words of the screen for one kind of device. */
private class RoleTexts(
    val question: StringResource,
    val producerTitle: StringResource,
    val producerDescription: StringResource,
    val consumerTitle: StringResource,
    val consumerDescription: StringResource,
    val changeLater: StringResource,
    val steps: List<StringResource>,
)

private val PhoneTexts = RoleTexts(
    Res.string.onboarding_question,
    Res.string.onboarding_role_producer_title,
    Res.string.onboarding_role_producer_description,
    Res.string.onboarding_role_consumer_title,
    Res.string.onboarding_role_consumer_description,
    Res.string.onboarding_change_later,
    listOf(Res.string.onboarding_how_receive, Res.string.onboarding_how_pair, Res.string.onboarding_how_sync),
)

private val ComputerTexts = RoleTexts(
    Res.string.onboarding_computer_question,
    Res.string.onboarding_computer_producer_title,
    Res.string.onboarding_computer_producer_description,
    Res.string.onboarding_computer_consumer_title,
    Res.string.onboarding_computer_consumer_description,
    Res.string.onboarding_computer_change_later,
    listOf(
        Res.string.onboarding_computer_how_receive,
        Res.string.onboarding_computer_how_pair,
        Res.string.onboarding_computer_how_sync,
    ),
)

/** First-launch screen: decides whether this phone (or computer) sends (producer) or receives (consumer). */
@Composable
fun RoleChoiceScreen(onRoleChosen: (DeviceRole) -> Unit, modifier: Modifier = Modifier) {
    val texts = if (LocalIsComputer.current) ComputerTexts else PhoneTexts
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
            HowItWorks(texts.steps)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(texts.question),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            ChoiceCard(
                title = stringResource(texts.producerTitle),
                description = stringResource(texts.producerDescription),
                icon = MediaSyncIcons.Send,
                onClick = { onRoleChosen(DeviceRole.PRODUCER) },
            )
            ChoiceCard(
                title = stringResource(texts.consumerTitle),
                description = stringResource(texts.consumerDescription),
                icon = MediaSyncIcons.Receive,
                onClick = { onRoleChosen(DeviceRole.CONSUMER) },
            )
            Text(
                text = stringResource(texts.changeLater),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The three steps from two devices to synced photos, before choosing this one's part in them. */
@Composable
private fun HowItWorks(steps: List<StringResource>) {
    Text(
        text = stringResource(Res.string.onboarding_how_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
    steps.forEachIndexed { index, step ->
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

/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.consumer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.danyk20.mediasync.core.designsystem.icon.MediaSyncIcons
import io.github.danyk20.mediasync.sync.android.SetupFailure
import io.github.danyk20.mediasync.sync.android.SetupResult
import io.github.danyk20.mediasync.sync.android.SetupStep

/** Setting up internet sync with the user's own Cloudflare account (docs/PLAN.md §8.4), as the screen shows it. */
sealed interface CloudflareSetup {
    /** This build carries no Worker to deploy: only a Worker's address can be entered. */
    data object Unavailable : CloudflareSetup

    data object Ready : CloudflareSetup

    /** Signing in with Cloudflare: the browser is open, or its answer is being turned into a token. */
    data object SigningIn : CloudflareSetup

    data class Running(val step: SetupStep) : CloudflareSetup

    /** [signedIn]: with a token from signing in, rather than a pasted one. */
    data class Finished(val result: SetupResult, val signedIn: Boolean = false) : CloudflareSetup
}

/** What the user can do on the internet sync screen. */
class InternetSyncActions(
    val save: (String) -> Boolean = { true },
    val turnOff: () -> Unit = {},
    val setUp: (token: String) -> Unit = {},
    val signIn: () -> Unit = {},
    val openTokenPage: () -> Unit = {},
    val dismissSetup: () -> Unit = {},
)

/**
 * Signing in with Cloudflare when the build can ([signIn]), with the three token steps folded below it; otherwise those
 * steps (an account, a token for MediaSync, and the token pasted here). Then the deployment's progress.
 */
@Composable
internal fun CloudflareSetupCard(setup: CloudflareSetup, signIn: Boolean, actions: InternetSyncActions) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.consumer_cloudflare_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            when (setup) {
                CloudflareSetup.SigningIn -> SigningIn()
                is CloudflareSetup.Running -> Progress(setup.step)
                is CloudflareSetup.Finished -> Outcome(setup, actions.dismissSetup)
                else -> if (signIn) SignInSteps(actions) else TokenSteps(actions)
            }
        }
    }
}

@Composable
private fun SignInSteps(actions: InternetSyncActions) {
    var withToken by rememberSaveable { mutableStateOf(false) }
    val shown = stringResource(R.string.consumer_cloudflare_token_shown)
    val hidden = stringResource(R.string.consumer_cloudflare_token_hidden)
    Text(stringResource(R.string.consumer_cloudflare_sign_in_intro), style = MaterialTheme.typography.bodyMedium)
    Button(onClick = actions.signIn, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.consumer_cloudflare_sign_in))
    }
    TextButton(
        onClick = { withToken = !withToken },
        modifier = Modifier.semantics {
            stateDescription = if (withToken) shown else hidden
        },
    ) {
        Text(stringResource(R.string.consumer_cloudflare_use_token))
        Icon(
            painterResource(MediaSyncIcons.Expand),
            contentDescription = null,
            modifier = Modifier.padding(start = 8.dp).rotate(if (withToken) HALF_TURN else 0f),
        )
    }
    if (withToken) TokenSteps(actions)
}

@Composable
private fun TokenSteps(actions: InternetSyncActions) {
    var token by rememberSaveable { mutableStateOf("") }
    Text(stringResource(R.string.consumer_cloudflare_step_account), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.consumer_cloudflare_step_token), style = MaterialTheme.typography.bodyMedium)
    OutlinedButton(onClick = actions.openTokenPage) { Text(stringResource(R.string.consumer_cloudflare_open_token)) }
    Text(stringResource(R.string.consumer_cloudflare_step_paste), style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = token,
        onValueChange = { token = it },
        label = { Text(stringResource(R.string.consumer_cloudflare_token_label)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { actions.setUp(token) }, enabled = token.isNotBlank()) {
        Text(stringResource(R.string.consumer_cloudflare_set_up))
    }
}

@Composable
private fun SigningIn() {
    Text(stringResource(R.string.consumer_cloudflare_progress_sign_in), style = MaterialTheme.typography.bodyLarge)
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
}

@Composable
private fun Progress(step: SetupStep) {
    Text(
        stringResource(
            when (step) {
                SetupStep.ACCOUNT -> R.string.consumer_cloudflare_progress_account
                SetupStep.RELAY -> R.string.consumer_cloudflare_progress_relay
                SetupStep.UPLOAD -> R.string.consumer_cloudflare_progress_upload
                SetupStep.SCHEDULE -> R.string.consumer_cloudflare_progress_schedule
                SetupStep.ADDRESS -> R.string.consumer_cloudflare_progress_address
                SetupStep.STARTING -> R.string.consumer_cloudflare_progress_starting
            },
        ),
        style = MaterialTheme.typography.bodyLarge,
    )
    LinearProgressIndicator(
        progress = { (step.ordinal + 1f) / SetupStep.entries.size },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Outcome(finished: CloudflareSetup.Finished, onDismiss: () -> Unit) {
    val result = finished.result
    val failed = result is SetupResult.Failed && result.reason != SetupFailure.NOT_STARTED
    Text(
        text = when (result) {
            is SetupResult.Done -> stringResource(
                when {
                    result.relay -> R.string.consumer_cloudflare_done
                    finished.signedIn -> R.string.consumer_cloudflare_done_no_relay_sign_in
                    else -> R.string.consumer_cloudflare_done_no_relay
                },
            )

            is SetupResult.Failed -> failureMessage(result)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
    Row {
        TextButton(onClick = onDismiss) {
            Text(
                stringResource(if (failed) R.string.consumer_cloudflare_try_again else R.string.consumer_cloudflare_ok),
            )
        }
    }
}

@Composable
private fun failureMessage(failure: SetupResult.Failed): String = when (failure.reason) {
    SetupFailure.INVALID_TOKEN -> stringResource(R.string.consumer_cloudflare_invalid_token)

    SetupFailure.NOT_ALLOWED -> stringResource(R.string.consumer_cloudflare_not_allowed)

    SetupFailure.OFFLINE -> stringResource(R.string.consumer_cloudflare_offline)

    SetupFailure.NOT_STARTED -> stringResource(R.string.consumer_cloudflare_not_started)

    SetupFailure.REFUSED -> stringResource(R.string.consumer_cloudflare_refused, failure.detail.orEmpty())

    SetupFailure.SIGN_IN_CANCELLED -> stringResource(R.string.consumer_cloudflare_sign_in_cancelled)

    SetupFailure.SIGN_IN_MISMATCH -> stringResource(R.string.consumer_cloudflare_sign_in_mismatch)

    SetupFailure.SIGN_IN_REFUSED ->
        stringResource(R.string.consumer_cloudflare_sign_in_refused, failure.detail.orEmpty())
}

private const val HALF_TURN = 180f

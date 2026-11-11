/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.danyk20.mediasync.core.designsystem.component.formatMegabytes
import io.github.danyk20.mediasync.core.model.TestFileSize
import io.github.danyk20.mediasync.sync.engine.TestFileHeld
import io.github.danyk20.mediasync.sync.engine.TestFileOutcome
import io.github.danyk20.mediasync.sync.engine.TestFilePath
import io.github.danyk20.mediasync.sync.engine.TestFileStatus

/**
 * The test file (docs/PLAN.md §7, *Test file*). [consumerName]: on a paired sending phone, the receiving phone, to
 * which one can be sent; `null` on a receiving phone, which only shows the [last] one checked.
 */
data class TestFileUi(
    val consumerName: String? = null,
    /** The size as the user types it, in MB. */
    val size: String = TestFileSize.megabytes(TestFileSize.DEFAULT_BYTES),
    /** [size] isn't a size from 0.1 to 10240 MB. */
    val sizeInvalid: Boolean = false,
    /** The size of the test file waiting to be sent; `null` while none waits. */
    val waitingBytes: Long? = null,
    /** Why it's still waiting, if it was held back. */
    val problem: TestFileHeld? = null,
    val last: TestFileOutcome? = null,
) {
    val canAdd: Boolean get() = !sizeInvalid && size.isNotBlank()
}

/** What the user does with the test file. */
data class TestFileActions(
    val onSizeChange: (String) -> Unit = {},
    val onAdd: () -> Unit = {},
    val onRemove: () -> Unit = {},
)

@Composable
internal fun TestFileSection(test: TestFileUi, actions: TestFileActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.diagnostics_test_file),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        val consumer = test.consumerName
        Text(
            text = if (consumer != null) {
                stringResource(R.string.diagnostics_test_file_intro, consumer)
            } else {
                stringResource(R.string.diagnostics_test_file_received)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            consumer == null -> Unit
            test.waitingBytes != null -> Waiting(test.waitingBytes, test.problem, consumer, actions.onRemove)
            else -> NewTestFile(test, actions)
        }
        test.last?.let { LastTestFile(it) }
    }
}

@Composable
private fun Waiting(bytes: Long, problem: TestFileHeld?, consumer: String, onRemove: () -> Unit) {
    Text(
        text = stringResource(
            R.string.diagnostics_test_file_waiting,
            formatMegabytes(bytes, LocalConfiguration.current.locales[0]),
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    problemText(problem)?.let { text ->
        Text(
            text = stringResource(text, consumer),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    OutlinedButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.diagnostics_test_file_remove))
    }
}

/** Why the test file still waits, as a string taking the receiving phone's name; `null` when nothing's in the way. */
internal fun problemText(problem: TestFileHeld?): Int? = when (problem) {
    TestFileHeld.RECEIVER_OUTDATED -> R.string.diagnostics_test_file_outdated
    TestFileHeld.NO_SPACE -> R.string.diagnostics_test_file_no_space
    TestFileHeld.NOT_WRITABLE -> R.string.diagnostics_test_file_not_writable
    TestFileHeld.STOPPED, null -> null
}

@Composable
private fun NewTestFile(test: TestFileUi, actions: TestFileActions) {
    OutlinedTextField(
        value = test.size,
        onValueChange = actions.onSizeChange,
        label = { Text(stringResource(R.string.diagnostics_test_file_size)) },
        supportingText = {
            val text = if (test.sizeInvalid) {
                R.string.diagnostics_test_file_size_invalid
            } else {
                R.string.diagnostics_test_file_size_hint
            }
            Text(stringResource(text))
        },
        isError = test.sizeInvalid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (test.canAdd) actions.onAdd() }),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = actions.onAdd, enabled = test.canAdd, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.diagnostics_test_file_add))
    }
}

/** The last test file checked: whether it arrived exactly as sent, its size, how long it took and how. */
@Composable
private fun LastTestFile(last: TestFileOutcome) {
    val locale = LocalConfiguration.current.locales[0]
    Text(
        text = stringResource(R.string.diagnostics_test_file_last),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    Text(
        text = stringResource(
            when (last.status) {
                TestFileStatus.MATCHED -> R.string.diagnostics_test_file_matched
                TestFileStatus.MISMATCHED -> R.string.diagnostics_test_file_mismatched
                TestFileStatus.NOT_STORED -> R.string.diagnostics_test_file_not_stored
            },
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = if (last.status == TestFileStatus.MATCHED) Color.Unspecified else MaterialTheme.colorScheme.error,
    )
    Text(
        text = stringResource(
            R.string.diagnostics_test_file_result,
            formatMegabytes(last.bytes, locale),
            String.format(locale, "%.1f", last.micros / MICROS_PER_SECOND),
            String.format(locale, "%.1f", last.megabytesPerSecond),
            stringResource(
                when (last.path) {
                    TestFilePath.WIFI -> R.string.diagnostics_test_file_over_wifi
                    TestFilePath.TURBO -> R.string.diagnostics_test_file_over_turbo
                    TestFilePath.INTERNET -> R.string.diagnostics_test_file_over_internet
                    TestFilePath.RELAY -> R.string.diagnostics_test_file_over_relay
                },
            ),
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
}

private const val MICROS_PER_SECOND = 1e6

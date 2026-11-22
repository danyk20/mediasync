/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
@file:Suppress("MatchingDeclarationName") // The platform actions of several kinds, one file.

package io.github.danyk20.mediasync.feature.diagnostics

import androidx.compose.runtime.Composable

/** Shares the diagnostic text as a file through the system's app picker; does nothing where there is none. */
@Composable
internal expect fun rememberLogSharer(): suspend (text: String) -> Unit

/** Asks where to save the diagnostic text, then writes [text] there. Returns what the *Save* button calls. */
@Composable
internal expect fun rememberLogSaver(text: suspend () -> String): () -> Unit

/** Closes MediaSync and removes what it stored (*Reset MediaSync*), where the system can do that. */
@Composable
internal expect fun rememberResetMediaSync(): () -> Unit

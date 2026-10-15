/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector

/** Whether a connector can reach its service as the user. */
sealed interface AuthState {
    /** Nothing to sign in to, such as for another app on this phone. */
    data object NotNeeded : AuthState

    data object SignedIn : AuthState

    /** The user has to sign in (again) before backups continue. */
    data object SignInNeeded : AuthState
}

/**
 * Signs a connector in to its service. Interactive sign-in (OAuth with Google Identity Services, from v1.1) happens
 * in the connector's Android module; this is what the shared code needs to know.
 */
interface ConnectorAuthenticator {
    val kind: AuthKind

    suspend fun state(account: ConnectorAccount): AuthState

    /** Forgets the account's credentials on this phone. */
    suspend fun signOut(account: ConnectorAccount)

    /** For connectors with nothing to sign in to. */
    object None : ConnectorAuthenticator {
        override val kind: AuthKind get() = AuthKind.NONE

        override suspend fun state(account: ConnectorAccount): AuthState = AuthState.NotNeeded

        override suspend fun signOut(account: ConnectorAccount) = Unit
    }
}

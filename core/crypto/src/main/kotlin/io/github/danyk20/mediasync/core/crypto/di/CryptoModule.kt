/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.crypto.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.ContributesTo
import io.github.danyk20.mediasync.core.crypto.KeystoreIdentityProvider
import io.github.danyk20.mediasync.transport.lan.IdentityProvider

@ContributesTo(AppScope::class)
@BindingContainer
interface CryptoModule {
    @Binds
    val KeystoreIdentityProvider.bindIdentityProvider: IdentityProvider
}

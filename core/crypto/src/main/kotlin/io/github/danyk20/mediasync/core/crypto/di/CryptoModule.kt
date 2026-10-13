/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.crypto.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.danyk20.mediasync.core.crypto.IdentityProvider
import io.github.danyk20.mediasync.core.crypto.KeystoreIdentityProvider

@Module
@InstallIn(SingletonComponent::class)
internal abstract class CryptoModule {
    @Binds
    abstract fun bindIdentityProvider(impl: KeystoreIdentityProvider): IdentityProvider
}

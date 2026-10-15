/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector.googlephotos

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import io.github.danyk20.mediasync.connector.BackupConnectorFactory
import io.github.danyk20.mediasync.connector.android.ConnectorKey

@Module
@InstallIn(SingletonComponent::class)
internal abstract class GooglePhotosAppModule {
    @Binds
    @IntoMap
    @ConnectorKey("google-photos-app")
    abstract fun bindFactory(factory: GooglePhotosAppConnectorFactory): BackupConnectorFactory

    @Binds
    abstract fun bindFolder(impl: MediaStoreFolder): PhoneFolder
}

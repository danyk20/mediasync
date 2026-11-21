/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector.googlephotos

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoMap
import io.github.danyk20.mediasync.connector.BackupConnectorFactory
import io.github.danyk20.mediasync.connector.android.ConnectorKey

@ContributesTo(AppScope::class)
@BindingContainer
interface GooglePhotosAppModule {
    @Binds
    @IntoMap
    @ConnectorKey("google-photos-app")
    val GooglePhotosAppConnectorFactory.bindFactory: BackupConnectorFactory

    @Binds
    val MediaStoreFolder.bindFolder: PhoneFolder
}

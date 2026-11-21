/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.quickaccess

import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentProvider
import dev.zacsweers.metrox.android.MetroAppComponentProviders
import dev.zacsweers.metrox.android.MetroApplication
import io.mockk.mockk
import kotlin.reflect.KClass

/**
 * Robolectric creates the manifest's [SyncWidget] through the app's component factory, which needs a Metro
 * provider for it (the app's own application supplies one from its graph). This test application supplies
 * a widget whose [QuickSync] is a mock.
 */
class QuickAccessTestApplication :
    Application(),
    MetroApplication {

    override val appComponentProviders: MetroAppComponentProviders = object : MetroAppComponentProviders {
        override val activityProviders: Map<KClass<out Activity>, () -> Activity> = emptyMap()
        override val providerProviders: Map<KClass<out ContentProvider>, () -> ContentProvider> = emptyMap()
        override val receiverProviders: Map<KClass<out BroadcastReceiver>, () -> BroadcastReceiver> =
            mapOf(SyncWidget::class to { SyncWidget(mockk(relaxed = true)) })
        override val serviceProviders: Map<KClass<out Service>, () -> Service> = emptyMap()
    }
}

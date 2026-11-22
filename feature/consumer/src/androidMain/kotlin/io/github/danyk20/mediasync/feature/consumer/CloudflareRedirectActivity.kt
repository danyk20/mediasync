/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.consumer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.android.ActivityKey

/**
 * Where the browser comes back to after signing in with Cloudflare: the redirect address, an https App Link that only
 * a build with the OAuth client declares. It hands the address over and returns to the app, which closes the
 * browser tab above it.
 */
@ContributesIntoMap(AppScope::class, binding<Activity>())
@ActivityKey
@Inject
class CloudflareRedirectActivity(private val signIn: CloudflareSignIn) : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.dataString?.let(signIn::receive)
        packageManager.getLaunchIntentForPackage(packageName)?.let { app ->
            startActivity(app.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        finish()
    }
}

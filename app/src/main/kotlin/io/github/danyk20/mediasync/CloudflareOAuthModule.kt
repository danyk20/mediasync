/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import io.github.danyk20.mediasync.feature.consumer.CloudflareOAuthClient

/** MediaSync's OAuth client for signing in with Cloudflare, from the build's settings (app/build.gradle.kts). */
@ContributesTo(AppScope::class)
@BindingContainer
object CloudflareOAuthModule {

    @Provides
    fun provideCloudflareOAuthClient(): CloudflareOAuthClient =
        CloudflareOAuthClient(BuildConfig.CLOUDFLARE_OAUTH_CLIENT_ID, BuildConfig.CLOUDFLARE_OAUTH_REDIRECT_URI)
}

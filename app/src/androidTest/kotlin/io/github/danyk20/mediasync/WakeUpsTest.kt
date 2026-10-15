/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.danyk20.mediasync.sync.android.PushTokens
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Wake-ups (docs/PLAN.md §10.4): the app registers with its Firebase project for the Firebase Installation ID its
 * Worker wakes it by. Only on a device with Google Play services and in a build with the project's google-services.json; CI's
 * emulators have neither, so it runs on developers' devices.
 */
@RunWith(AndroidJUnit4::class)
class WakeUpsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun theAppRegistersWithItsFirebaseProjectForAnInstallationId() {
        assumeTrue("No Google Play services on this device", hasPlayServices())
        assumeTrue("A build without google-services.json", hasFirebaseProject())

        val token = PushTokens(context).current()

        assertNotNull("No FCM registration", token)
        assertTrue("Not a Firebase Installation ID: ${token?.length} characters", INSTALLATION_ID.matches(token!!))
        assertTrue("Not kept", PushTokens(context).current() == token)
    }

    private fun hasPlayServices(): Boolean = try {
        context.packageManager.getPackageInfo("com.google.android.gms", 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** The Google services plugin turns google-services.json into this resource. */
    private fun hasFirebaseProject(): Boolean =
        context.resources.getIdentifier("google_app_id", "string", context.packageName) != 0

    private companion object {
        /** 22 base64url characters. */
        val INSTALLATION_ID = Regex("[A-Za-z0-9_-]{22}")
    }
}

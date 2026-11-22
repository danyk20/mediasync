/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.diagnostics

import android.content.Context
import android.os.Build
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.github.danyk20.mediasync.core.model.ExercisedOnDevice
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

@ExercisedOnDevice(coveredBy = "UpdatesTest (version rules and the GitHub answer); a check on an emulator")
@ContributesBinding(AppScope::class)
@Inject
class GitHubReleases(private val context: Context) : Releases {
    override fun fromPlay(): Boolean = installer() == PLAY_STORE

    override fun installed(): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    override suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        try {
            val connection = URL(LATEST).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
                parseRelease(connection.inputStream.bufferedReader().use { it.readText() })
            } finally {
                connection.disconnect()
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun installer(): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getInstallerPackageName(context.packageName)
    }

    private companion object {
        const val PLAY_STORE = "com.android.vending"
        const val LATEST = "https://api.github.com/repos/danyk20/mediasync/releases/latest"
        const val TIMEOUT_MILLIS = 10_000
    }
}

/** The release in GitHub's answer for the latest release; `null` if it isn't one. */
internal fun parseRelease(json: String): Release? = try {
    val release = JSONObject(json)
    val version = release.getString("tag_name").removePrefix("v")
    Release(version, release.getString("html_url")).takeIf { version.isNotBlank() }
} catch (_: JSONException) {
    null
}

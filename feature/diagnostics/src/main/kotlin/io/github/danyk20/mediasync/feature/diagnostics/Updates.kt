/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.diagnostics

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.danyk20.mediasync.core.model.ExercisedOnDevice
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/** A published MediaSync release: its version, such as `0.5.0`, and its page. */
data class Release(val version: String, val url: String)

/**
 * Updates for a MediaSync installed from GitHub (docs/PLAN.md §14.4). Installed from Google Play, Play updates it, and
 * MediaSync never asks GitHub. The check runs only when the user asks for it: nothing contacts GitHub on its own.
 */
interface Releases {
    /** Whether this copy came from Google Play, which then updates it. */
    fun fromPlay(): Boolean

    /** This copy's version, such as `0.4.0`. */
    fun installed(): String

    /** The latest published release; `null` if GitHub couldn't be reached. */
    suspend fun latest(): Release?
}

@ExercisedOnDevice(coveredBy = "UpdatesTest (version rules and the GitHub answer); a check on an emulator")
class GitHubReleases @Inject constructor(@ApplicationContext private val context: Context) : Releases {
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

/** Whether [latest] (`0.5.0`) is a newer version than [installed] (`0.4.0`): numbers compared part by part. */
internal fun isNewer(latest: String, installed: String): Boolean {
    val new = parts(latest) ?: return false
    val old = parts(installed) ?: return true
    for (i in 0 until maxOf(new.size, old.size)) {
        val a = new.getOrElse(i) { 0 }
        val b = old.getOrElse(i) { 0 }
        if (a != b) return a > b
    }
    return false
}

/** `1.2.3` as [1, 2, 3]; a suffix such as `-debug` is left out. `null` if it isn't a version. */
private fun parts(version: String): List<Int>? =
    version.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: return null }

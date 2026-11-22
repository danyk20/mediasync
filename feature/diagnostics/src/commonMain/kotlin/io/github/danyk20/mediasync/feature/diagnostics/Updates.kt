/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.diagnostics

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

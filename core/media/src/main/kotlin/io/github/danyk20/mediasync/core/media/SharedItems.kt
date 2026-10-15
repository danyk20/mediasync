/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.media

import android.content.Context
import androidx.core.content.edit
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Photos and videos the user shared to MediaSync from another app, to send now. Each is a gallery item's content
 * URI, or, for one that isn't in the gallery (a WhatsApp file, a photo only in the cloud), a copy's `file:` URI in
 * [copies]. They go before everything else, whatever folders are chosen, and are dropped once sent.
 */
@Singleton
class SharedItems internal constructor(context: Context, private val now: () -> Long) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context, System::currentTimeMillis)

    private val preferences = context.getSharedPreferences("shared_items", Context.MODE_PRIVATE)

    /** Where copies of shared files that aren't in the gallery wait to be sent. */
    val copies: File = File(context.filesDir, "shared")

    /** Queues [locations] to send. */
    @Synchronized
    fun add(locations: Collection<String>) = preferences.edit {
        val at = now()
        locations.forEach { putLong(it, at) }
    }

    /** What's queued, oldest first, without what has waited longer than a week: sent or unsendable, it goes. */
    @Synchronized
    fun all(): List<String> {
        val queued = preferences.all.mapNotNull { (location, at) -> (at as? Long)?.let { location to it } }
        val (stale, fresh) = queued.partition { (_, at) -> now() - at > MAX_AGE_MILLIS }
        if (stale.isNotEmpty()) remove(stale.map { it.first })
        return fresh.sortedBy { it.second }.map { it.first }
    }

    /** Drops [locations], sent or gone, and deletes their copies. */
    @Synchronized
    fun remove(locations: Collection<String>) {
        if (locations.isEmpty()) return
        preferences.edit { locations.forEach(::remove) }
        locations.mapNotNull { copyOf(it) }.forEach { it.delete() }
    }

    /** The copy behind [location], if it is one of [copies]. */
    private fun copyOf(location: String): File? = location.toUri().takeIf { it.scheme == "file" }?.path
        ?.let(::File)
        ?.takeIf { it.canonicalPath.startsWith(copies.canonicalPath + File.separator) }

    private companion object {
        val MAX_AGE_MILLIS = TimeUnit.DAYS.toMillis(7)
    }
}

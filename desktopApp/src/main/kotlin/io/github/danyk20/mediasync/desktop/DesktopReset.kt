/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.room.Transactor
import androidx.room.useWriterConnection
import io.github.danyk20.mediasync.core.database.MediaSyncDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * *Reset MediaSync* on a computer, as the phone's reset does it: every setting (the role, the folders, the days, the
 * schedule and the rest) and every table of the database (paired devices, what was sent and received, the sync
 * history, the fingerprints, the backup accounts) are forgotten, so the next start asks again. The caller then closes
 * the app.
 *
 * Kept: the device identity (on Android it is a keystore key, which app data does not hold), the diagnostics log and
 * the received photos in the inbox: they are the user's photos, not the app's data.
 */
class DesktopReset(
    private val settings: DataStore<Preferences>,
    private val database: MediaSyncDatabase,
    private val history: DesktopSyncRecorder,
) {
    suspend fun reset() {
        clearTables()
        settings.edit { it.clear() }
        history.forget()
    }

    /** Empties every table Room made, in one transaction; Room's own bookkeeping tables stay. */
    private suspend fun clearTables() = withContext(Dispatchers.IO) {
        database.useWriterConnection { transactor ->
            transactor.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                val tables = usePrepared(TABLES_QUERY) { statement ->
                    buildList { while (statement.step()) add(statement.getText(0)) }
                }
                tables.forEach { table -> usePrepared("DELETE FROM \"$table\"") { it.step() } }
            }
        }
    }

    private companion object {
        const val TABLES_QUERY = "SELECT name FROM sqlite_master WHERE type = 'table' " +
            "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%'"
    }
}

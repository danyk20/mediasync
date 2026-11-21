/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

private const val LATEST_VERSION = 7

/** The database on the desktop's bundled SQLite: it opens, stores and upgrades like Android's framework SQLite. */
class JvmDatabaseTest {

    @TempDir
    lateinit var directory: File

    @Test
    fun peersRoundTripThroughTheBundledDriver() = runTest {
        val database = Room.inMemoryDatabaseBuilder<MediaSyncDatabase>()
            .setDriver(BundledSQLiteDriver())
            .build()
        try {
            database.peerDao().upsert(PeerEntity("ab", "Pixel", "CONSUMER", 1))

            database.peerDao().get("ab")?.name shouldBe "Pixel"
            database.peerDao().get("cd") shouldBe null
        } finally {
            database.close()
        }
    }

    @Test
    fun everySchemaVersionUpgradesToTheLatest() {
        val helper = MigrationTestHelper(
            Path.of("schemas"),
            File(directory, "mediasync.db").toPath(),
            BundledSQLiteDriver(),
            MediaSyncDatabase::class,
            { MediaSyncDatabase_Impl() },
        )
        helper.createDatabase(1).close()

        helper.runMigrationsAndValidate(LATEST_VERSION, emptyList()).close()
    }
}

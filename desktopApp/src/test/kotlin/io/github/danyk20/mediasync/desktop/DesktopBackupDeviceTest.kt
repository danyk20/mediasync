/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import dev.zacsweers.metro.createGraphFactory
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Choosing the backup folder goes through the picker and is kept in the settings. */
class DesktopBackupDeviceTest {

    @TempDir
    lateinit var directory: Path

    private val graph by lazy {
        createGraphFactory<DesktopGraph.Factory>().create(AppPaths(directory, isolated = true))
    }

    private fun device(picks: Path?, seen: MutableList<Path?> = mutableListOf()) = DesktopBackupDevice(
        graph.folderBackup,
        { current ->
            seen += current
            picks
        },
        graph.settings,
    )

    @Test
    fun keepsTheFolderTheUserPicked() = runBlocking<Unit> {
        val folder = directory.resolve("photos")

        device(folder).chooseFolder()

        graph.settings.backupFolder.first() shouldBe folder.toAbsolutePath().toString()
    }

    @Test
    fun cancellingThePickerChangesNothing() = runBlocking<Unit> {
        graph.settings.setBackupFolder("/keep/me")

        device(picks = null).chooseFolder()

        graph.settings.backupFolder.first() shouldBe "/keep/me"
    }

    @Test
    fun startsThePickerAtTheCurrentFolder() = runBlocking<Unit> {
        graph.settings.setBackupFolder(directory.resolve("current").toString())
        val seen = mutableListOf<Path?>()

        device(picks = null, seen).chooseFolder()

        seen shouldBe listOf(directory.resolve("current"))
    }

    @Test
    fun stoppingForgetsTheFolder() = runBlocking<Unit> {
        graph.settings.setBackupFolder("/some/folder")

        device(picks = null).stopFolder()

        graph.settings.backupFolder.first().shouldBeNull()
    }

    @Test
    fun theHomeScreenLineFollowsTheChoice() = runBlocking<Unit> {
        val device = DesktopConsumerDevice(graph.folderBackup)
        device.backupLine().first() shouldBe io.github.danyk20.mediasync.feature.consumer.BackupLine.Off

        graph.settings.setBackupFolder(directory.resolve("Photos").toString())

        device.backupLine().first { it !is io.github.danyk20.mediasync.feature.consumer.BackupLine.Off } shouldBe
            io.github.danyk20.mediasync.feature.consumer.BackupLine.Folder("Photos", 0, reachable = false)
    }
}

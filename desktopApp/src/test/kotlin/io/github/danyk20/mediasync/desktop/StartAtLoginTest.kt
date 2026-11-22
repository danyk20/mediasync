/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import io.github.danyk20.mediasync.feature.diagnostics.StartAtLoginState
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** *Start at login* on temporary folders, with a recording login item: never the real entries. */
class StartAtLoginTest {

    @TempDir
    lateinit var directory: Path

    private val item = RecordingItem()

    private fun login(launcher: Path?, isolated: Boolean = false) = DesktopStartAtLogin(
        item,
        { launcher },
        AppPaths(directory.resolve("data"), isolated),
    )

    private fun launcher(): Path = Files.createFile(directory.resolve("MediaSync"))

    @Test
    fun `an installed copy turns it on and the entry starts it hidden`() = runBlocking<Unit> {
        val program = launcher()
        val login = login(program)
        login.state() shouldBe StartAtLoginState(on = false, canChange = true)

        login.set(true) shouldBe StartAtLoginState(on = true, canChange = true)

        item.command shouldBe listOf(program.toString(), "--background")
        login.set(false) shouldBe StartAtLoginState(on = false, canChange = true)
        item.command.shouldBeNull()
    }

    @Test
    fun `a copy that is not installed cannot turn it on`() = runBlocking<Unit> {
        val login = login(launcher = null)

        login.state() shouldBe StartAtLoginState(on = false, canChange = false)
        login.set(true) shouldBe StartAtLoginState(on = false, canChange = false)
        item.writes shouldBe 0
    }

    @Test
    fun `no sending phone paired yet at the first start leaves it off`() = runBlocking<Unit> {
        val login = login(launcher())

        login.watchProducers(flowOf(0))

        login.state() shouldBe StartAtLoginState(on = false, canChange = true)
        item.writes shouldBe 0
    }

    @Test
    fun `phones already paired when the app starts are not the first pairing`() = runBlocking<Unit> {
        val login = login(launcher())

        login.watchProducers(flowOf(1, 2))

        item.writes shouldBe 0
    }

    @Test
    fun `the first pairing turns it on once, and the user turning it off sticks`() = runBlocking<Unit> {
        val program = launcher()
        val login = login(program)

        login.watchProducers(flowOf(0, 1))
        item.command shouldBe listOf(program.toString(), "--background")

        login.set(false)
        login.watchProducers(flowOf(0, 1)) // another phone pairs, after a restart
        item.command.shouldBeNull()
    }

    @Test
    fun `a later start points the entry at the launcher where it is now`() = runBlocking<Unit> {
        val first = launcher()
        login(first).watchProducers(flowOf(0, 1))
        val moved = Files.move(first, directory.resolve("Moved"))

        login(moved).refreshEntry()

        item.command shouldBe listOf(moved.toString(), "--background")
    }

    @Test
    fun `a copy that is not installed leaves no mark, so the installed one still turns it on`() = runBlocking<Unit> {
        login(launcher = null).watchProducers(flowOf(0, 1))
        item.writes shouldBe 0
        Files.exists(directory.resolve("data/start-at-login-decided")) shouldBe false

        login(launcher()).watchProducers(flowOf(0, 1))
        item.writes shouldBe 1
    }

    @Test
    fun `a copy with its own data folder never registers`() = runBlocking<Unit> {
        login(launcher(), isolated = true).watchProducers(flowOf(0, 1))

        item.writes shouldBe 0
    }

    @Test
    fun `the launcher is the installer's path, if that file exists`() {
        val program = launcher()

        installedLauncher { program.toString() } shouldBe program
        installedLauncher { directory.resolve("gone").toString() }.shouldBeNull()
        installedLauncher { " " }.shouldBeNull()
        installedLauncher { null }.shouldBeNull()
    }

    @Test
    fun `a hidden start needs both the argument and a tray`() {
        startsHidden(arrayOf("--background"), trayAvailable = true) shouldBe true
        startsHidden(arrayOf("--background"), trayAvailable = false) shouldBe false
        startsHidden(emptyArray(), trayAvailable = true) shouldBe false
    }

    private class RecordingItem : LoginItem {
        var command: List<String>? = null
        var writes = 0

        override fun isEnabled() = command != null

        override fun enable(command: List<String>) {
            this.command = command
            writes++
        }

        override fun disable() {
            command = null
        }
    }
}

/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.platform.desktop

import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DesktopDeviceConditionsTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `never pauses background work`() {
        DesktopDeviceConditions { false }.pauseReason() shouldBe null
        DesktopDeviceConditions { false }.lowForTurbo() shouldBe false
        DesktopDeviceConditions { false }.chargingHeld() shouldBe false
    }

    @Test
    fun `is plugged in unless the power source says it is on battery`() {
        DesktopDeviceConditions { true }.pluggedIn() shouldBe true
        DesktopDeviceConditions { false }.pluggedIn() shouldBe false
        DesktopDeviceConditions { null }.pluggedIn() shouldBe true
        DesktopDeviceConditions { error("boom") }.pluggedIn() shouldBe true
    }

    private fun source(os: String, output: String? = null) = SystemPowerSource(os, root.toFile(), run = { output })

    @Test
    fun `reads pmset on macOS`() {
        source("Mac OS X", "Now drawing from 'AC Power'\n").onAcPower() shouldBe true
        source("Mac OS X", "Now drawing from 'Battery Power'\n").onAcPower() shouldBe false
        source("Mac OS X", "Now drawing from 'UPS Power'\n").onAcPower() shouldBe false
        source("Mac OS X", "something else").onAcPower() shouldBe null
        source("Mac OS X", null).onAcPower() shouldBe null
    }

    private fun supply(name: String, type: String, online: String) {
        val dir = Files.createDirectories(root.resolve(name))
        Files.writeString(dir.resolve("type"), "$type\n")
        Files.writeString(dir.resolve("online"), "$online\n")
    }

    @Test
    fun `reads the mains supply on Linux`() {
        supply("BAT0", "Battery", "0")
        source("Linux").onAcPower() shouldBe null // No mains supply listed: a desktop PC; don't know.
        supply("AC", "Mains", "1")
        source("Linux").onAcPower() shouldBe true
        supply("AC", "Mains", "0")
        source("Linux").onAcPower() shouldBe false
    }

    @Test
    fun `does not know on Windows`() {
        source("Windows 11").onAcPower() shouldBe null
    }

    @Test
    fun `the real runner returns the output of a command, and null for one that fails or is missing`() {
        SystemPowerSource.runCommand(listOf("java", "-version")) shouldBe
            SystemPowerSource.runCommand(listOf("java", "-version"))
        SystemPowerSource.runCommand(listOf("definitely-not-a-command-mediasync")) shouldBe null
        PowerSource.system().onAcPower() // This machine; must not throw.
    }
}

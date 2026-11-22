/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Each login item against a temporary home or a fake `reg`: nothing here touches the real login entries. */
class LoginItemTest {

    @TempDir
    lateinit var home: Path

    private val command = listOf("/Applications/Media Sync.app/Contents/MacOS/MediaSync", BACKGROUND_ARGUMENT)

    @Test
    fun `macOS writes a launch agent that runs the app at load and reads it back`() {
        val item = MacLoginItem(home)
        item.isEnabled() shouldBe false

        item.enable(command)

        item.isEnabled() shouldBe true
        val plist = Files.readString(home.resolve("Library/LaunchAgents/io.github.danyk20.mediasync.plist"))
        plist shouldContain "<key>Label</key>\n    <string>io.github.danyk20.mediasync</string>"
        plist shouldContain "<string>/Applications/Media Sync.app/Contents/MacOS/MediaSync</string>"
        plist shouldContain "<string>--background</string>"
        plist shouldContain "<key>RunAtLoad</key>\n    <true/>"
    }

    @Test
    fun `macOS escapes the program's path and replaces an older entry`() {
        val item = MacLoginItem(home)
        item.enable(listOf("/Apps/A&B/MediaSync"))
        item.enable(command)

        val plist = Files.readString(item.file)
        plist shouldContain "Media Sync.app"
        (plist.contains("A&B")) shouldBe false
        item.plist(listOf("/Apps/A&B/MediaSync")) shouldContain "A&amp;B"
    }

    @Test
    fun `macOS removes the launch agent, and removing twice is fine`() {
        val item = MacLoginItem(home)
        item.enable(command)

        item.disable()
        item.disable()

        item.isEnabled() shouldBe false
        Files.exists(item.file) shouldBe false
    }

    @Test
    fun `Linux writes an autostart entry with the program quoted`() {
        val item = XdgLoginItem(home.resolve(".config"))
        item.isEnabled() shouldBe false

        item.enable(listOf("/opt/media sync/bin/MediaSync", BACKGROUND_ARGUMENT))

        item.isEnabled() shouldBe true
        val entry = Files.readString(home.resolve(".config/autostart/mediasync.desktop"))
        entry shouldContain "[Desktop Entry]"
        entry shouldContain "Exec=\"/opt/media sync/bin/MediaSync\" \"--background\""
        entry shouldContain "Type=Application"
        item.disable()
        item.isEnabled() shouldBe false
    }

    @Test
    fun `Linux quotes the characters the desktop entry spec reserves`() {
        val item = XdgLoginItem(home)

        item.entry(listOf("/a\"b\$c%d`e\\f")) shouldContain "Exec=\"/a\\\"b\\\$c%%d\\`e\\\\f\""
    }

    @Test
    fun `Linux uses XDG_CONFIG_HOME when it is set`() {
        XdgLoginItem.configHome(mapOf("XDG_CONFIG_HOME" to "/cfg"), home).toString() shouldBe "/cfg"
        XdgLoginItem.configHome(mapOf("XDG_CONFIG_HOME" to " "), home) shouldBe home.resolve(".config")
        XdgLoginItem.configHome(emptyMap(), home) shouldBe home.resolve(".config")
    }

    @Test
    fun `Windows adds, finds and deletes the Run value through reg`() {
        val registry = FakeRegistry()
        val item = WindowsLoginItem(registry)
        item.isEnabled() shouldBe false

        item.enable(listOf("C:\\Program Files\\MediaSync\\MediaSync.exe", BACKGROUND_ARGUMENT))

        item.isEnabled() shouldBe true
        registry.values[WindowsLoginItem.VALUE] shouldBe
            "\"C:\\Program Files\\MediaSync\\MediaSync.exe\" \"--background\""
        registry.calls.first { it[0] == "add" } shouldBe listOf(
            "add",
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
            "/v",
            "MediaSync",
            "/t",
            "REG_SZ",
            "/d",
            "\"C:\\Program Files\\MediaSync\\MediaSync.exe\" \"--background\"",
            "/f",
        )
        item.disable()
        item.isEnabled() shouldBe false
        item.disable() // No value left: reg says so, and that is fine.
    }

    @Test
    fun `Windows reports a reg add that failed`() {
        val item = WindowsLoginItem { RegistryResult(1, "ERROR: Access is denied.") }

        shouldThrow<IllegalStateException> { item.enable(command) }.message shouldContain "Access is denied"
    }

    @Test
    fun `the operating system picks the login item`() {
        loginItemFor("Mac OS X", home).shouldBeInstanceOf<MacLoginItem>()
        loginItemFor("Windows 11", home, registry = FakeRegistry()).shouldBeInstanceOf<WindowsLoginItem>()
        loginItemFor("Linux", home, emptyMap()).shouldBeInstanceOf<XdgLoginItem>()
        loginItemFor("Plan 9", home) shouldBe NoLoginItem
    }

    /** A registry that keeps the values `reg add` sets under the Run key, in memory. */
    class FakeRegistry : RegistryRunner {
        val values = mutableMapOf<String, String>()
        val calls = mutableListOf<List<String>>()

        override fun run(arguments: List<String>): RegistryResult {
            calls += arguments
            val name = arguments[arguments.indexOf("/v") + 1]
            return when (arguments[0]) {
                "add" -> {
                    values[name] = arguments[arguments.indexOf("/d") + 1]
                    RegistryResult(0, "The operation completed successfully.")
                }

                "query" -> if (name in
                    values
                ) {
                    RegistryResult(0, "    $name    REG_SZ    ${values[name]}")
                } else {
                    missing()
                }

                "delete" -> if (values.remove(name) != null) RegistryResult(0, "") else missing()

                else -> error("unexpected reg ${arguments[0]}")
            }
        }

        private fun missing() =
            RegistryResult(1, "ERROR: The system was unable to find the specified registry key or value.")
    }
}

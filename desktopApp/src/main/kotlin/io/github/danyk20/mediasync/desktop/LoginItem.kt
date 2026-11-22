/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** What starts MediaSync when the user logs in: one entry, in the way this operating system keeps them. */
interface LoginItem {
    /** Whether the entry exists. */
    fun isEnabled(): Boolean

    /** Writes the entry (or replaces it) so that logging in runs [command]: the program and its arguments. */
    fun enable(command: List<String>)

    /** Removes the entry; nothing to do if there is none. */
    fun disable()
}

/** An operating system with no known way to start at login, and a copy that must not touch the real entries. */
object NoLoginItem : LoginItem {
    override fun isEnabled() = false

    override fun enable(command: List<String>) = Unit

    override fun disable() = Unit
}

/** macOS: a LaunchAgent in `~/Library/LaunchAgents`, which launchd starts at login. [home] is the user's home. */
class MacLoginItem(home: Path) : LoginItem {
    internal val file: Path = home.resolve("Library").resolve("LaunchAgents").resolve("$LABEL.plist")

    override fun isEnabled() = Files.isRegularFile(file)

    override fun enable(command: List<String>) {
        Files.createDirectories(file.parent)
        Files.writeString(file, plist(command))
    }

    override fun disable() {
        Files.deleteIfExists(file)
    }

    internal fun plist(command: List<String>): String {
        val arguments = command.joinToString("\n") { "        <string>${xmlEscape(it)}</string>" }
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>$LABEL</string>
    <key>ProgramArguments</key>
    <array>
$arguments
    </array>
    <key>RunAtLoad</key>
    <true/>
</dict>
</plist>
"""
    }

    private fun xmlEscape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        const val LABEL = "io.github.danyk20.mediasync"
    }
}

/** Linux and the other desktops that follow XDG: `autostart/mediasync.desktop` in [configHome] (`~/.config`). */
class XdgLoginItem(configHome: Path) : LoginItem {
    internal val file: Path = configHome.resolve("autostart").resolve("mediasync.desktop")

    override fun isEnabled() = Files.isRegularFile(file)

    override fun enable(command: List<String>) {
        Files.createDirectories(file.parent)
        Files.writeString(file, entry(command))
    }

    override fun disable() {
        Files.deleteIfExists(file)
    }

    internal fun entry(command: List<String>): String = """[Desktop Entry]
Type=Application
Name=MediaSync
Comment=Receives photos and videos from your phones
Exec=${command.joinToString(" ", transform = ::quote)}
Terminal=false
X-GNOME-Autostart-enabled=true
"""

    /** The Desktop Entry spec's quoting: in double quotes, with `"`, `` ` ``, `$` and `\` escaped and `%` doubled. */
    private fun quote(argument: String): String {
        val escaped = argument.replace("\\", "\\\\").replace("\"", "\\\"").replace("`", "\\`").replace("$", "\\$")
            .replace("%", "%%")
        return "\"$escaped\""
    }

    companion object {
        /** `$XDG_CONFIG_HOME`, or `~/.config` without it. */
        fun configHome(environment: Map<String, String>, home: Path): Path =
            environment["XDG_CONFIG_HOME"]?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
                ?: home.resolve(".config")
    }
}

/** What running `reg` gave: its exit code and what it printed. */
data class RegistryResult(val exitCode: Int, val output: String)

/** Runs `reg` with [arguments]; a fake in tests, so they never touch the real registry. */
fun interface RegistryRunner {
    fun run(arguments: List<String>): RegistryResult
}

/** The real `reg.exe`. */
class RegExe : RegistryRunner {
    override fun run(arguments: List<String>): RegistryResult {
        val process = ProcessBuilder(listOf("reg") + arguments).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return RegistryResult(process.waitFor(), output)
    }
}

/** Windows: the value `MediaSync` under `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`, set through `reg`. */
class WindowsLoginItem(private val registry: RegistryRunner = RegExe()) : LoginItem {
    override fun isEnabled() = registry.run(listOf("query", KEY, "/v", VALUE)).exitCode == 0

    override fun enable(command: List<String>) {
        val line = command.joinToString(" ") { "\"$it\"" }
        val result = registry.run(listOf("add", KEY, "/v", VALUE, "/t", "REG_SZ", "/d", line, "/f"))
        check(result.exitCode == 0) { "reg add failed: ${result.output.trim()}" }
    }

    override fun disable() {
        // Exit code 1 when there is no such value: nothing to remove.
        registry.run(listOf("delete", KEY, "/v", VALUE, "/f"))
    }

    companion object {
        const val KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        const val VALUE = "MediaSync"
    }
}

/** The login item of the operating system named by [os] (`os.name`), under [home]. */
fun loginItemFor(
    os: String,
    home: Path,
    environment: Map<String, String> = System.getenv(),
    registry: RegistryRunner = RegExe(),
): LoginItem {
    val name = os.lowercase()
    return when {
        "mac" in name || "darwin" in name -> MacLoginItem(home)
        "win" in name -> WindowsLoginItem(registry)
        "linux" in name || "nix" in name || "bsd" in name -> XdgLoginItem(XdgLoginItem.configHome(environment, home))
        else -> NoLoginItem
    }
}

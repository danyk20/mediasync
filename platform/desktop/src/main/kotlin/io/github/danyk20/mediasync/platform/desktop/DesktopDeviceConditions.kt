/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.platform.desktop

import io.github.danyk20.mediasync.sync.android.DeviceConditions
import io.github.danyk20.mediasync.sync.android.PauseReason
import java.io.File
import java.util.concurrent.TimeUnit

/** Whether the computer runs on mains power; `null` when that can't be told. */
fun interface PowerSource {
    fun onAcPower(): Boolean?

    companion object {
        /** Asks the operating system; `null` (don't know) on any doubt. */
        fun system(): PowerSource = SystemPowerSource()
    }
}

/**
 * A computer is never too hot or too low on battery for MediaSync to receive: there is no thermal or battery
 * signal, so background work is never paused ([pauseReason] is `null`). Only [pluggedIn] asks the OS, and says
 * "plugged in" whenever it doesn't know.
 */
class DesktopDeviceConditions(private val power: PowerSource = PowerSource.system()) : DeviceConditions {
    override fun pauseReason(): PauseReason? = null

    override fun pluggedIn(): Boolean = runCatching { power.onAcPower() }.getOrNull() ?: true
}

/**
 * macOS: `pmset -g batt`; Linux: the `online` file of each `Mains` supply under
 * `/sys/class/power_supply`; Windows: unknown.
 * [run] runs a command and returns its output, or `null` if it failed.
 */
internal class SystemPowerSource(
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val powerSupplyRoot: File = File("/sys/class/power_supply"),
    private val run: (List<String>) -> String? = ::runCommand,
) : PowerSource {

    override fun onAcPower(): Boolean? {
        val os = osName.lowercase()
        return when {
            "mac" in os -> run(listOf("pmset", "-g", "batt"))?.let(::parsePmset)
            "linux" in os -> linuxMains()
            else -> null
        }
    }

    private fun linuxMains(): Boolean? {
        val mains = powerSupplyRoot.listFiles().orEmpty().filter {
            runCatching { File(it, "type").readText().trim() == "Mains" }.getOrDefault(false)
        }
        if (mains.isEmpty()) return null // A desktop PC has none: that is "don't know".
        return mains.any { runCatching { File(it, "online").readText().trim() == "1" }.getOrDefault(false) }
    }

    internal companion object {
        /** `Now drawing from 'AC Power'` or `'Battery Power'`; `null` for anything else. */
        fun parsePmset(output: String): Boolean? = when {
            "'AC Power'" in output -> true
            "'Battery Power'" in output || "'UPS Power'" in output -> false
            else -> null
        }

        fun runCommand(command: List<String>): String? = try {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            if (process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0) {
                output
            } else {
                process.destroyForcibly()
                null
            }
        } catch (_: java.io.IOException) {
            null
        }

        private const val COMMAND_TIMEOUT_SECONDS = 3L
    }
}

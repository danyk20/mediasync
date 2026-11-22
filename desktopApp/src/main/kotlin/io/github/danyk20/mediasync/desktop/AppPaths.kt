/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Where the desktop app keeps everything it owns: the identity, the database, the settings, the inbox and the log.
 *
 * With the environment variable [OVERRIDE] (or the system property of the same name) set, everything lives in that
 * folder and the identity's password goes in a file instead of the operating system's credential store: tests and
 * checks never touch the real folders or the keychain.
 */
class AppPaths(val root: Path, val isolated: Boolean = false) {
    /** The PKCS#12 identity file and, without a credential store, its password. */
    val identity: Path = root.resolve("identity")
    val database: Path = root.resolve("mediasync.db")
    val settings: Path = root.resolve("settings.preferences_pb")

    /** Received files wait here until a backup connector has copied them (docs/adr M8: the inbox). */
    val inbox: Path = root.resolve("inbox")
    val logs: Path = root.resolve("logs")

    companion object {
        const val OVERRIDE = "MEDIASYNC_DATA_DIR"
        private const val FOLDER = "MediaSync"
        private const val LINUX_FOLDER = "mediasync"

        /** The folder of the operating system's convention, or the [OVERRIDE]. */
        fun resolve(
            environment: Map<String, String> = System.getenv(),
            properties: (String) -> String? = System::getProperty,
        ): AppPaths {
            val override = (properties(OVERRIDE) ?: environment[OVERRIDE])?.takeIf { it.isNotBlank() }
            if (override != null) return AppPaths(Paths.get(override).toAbsolutePath(), isolated = true)
            val os = properties("os.name").orEmpty().lowercase()
            val home = properties("user.home").orEmpty()
            val root = when {
                "mac" in os || "darwin" in os -> Paths.get(home, "Library", "Application Support", FOLDER)

                "win" in os -> Paths.get(
                    environment["APPDATA"] ?: Paths.get(home, "AppData", "Roaming").toString(),
                    FOLDER,
                )

                else -> Paths.get(
                    environment["XDG_DATA_HOME"]?.takeIf { it.isNotBlank() }
                        ?: Paths.get(home, ".local", "share").toString(),
                    LINUX_FOLDER,
                )
            }
            return AppPaths(root)
        }
    }
}

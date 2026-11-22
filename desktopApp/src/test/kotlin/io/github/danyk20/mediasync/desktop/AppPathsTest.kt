/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import io.kotest.matchers.shouldBe
import java.nio.file.Paths
import org.junit.jupiter.api.Test

class AppPathsTest {
    private fun resolve(os: String, environment: Map<String, String> = emptyMap(), home: String = "/home/ann") =
        AppPaths.resolve(environment) { name ->
            when (name) {
                "os.name" -> os
                "user.home" -> home
                else -> null
            }
        }

    @Test
    fun macKeepsItsDataInApplicationSupport() {
        val paths = resolve("Mac OS X", home = "/Users/ann")

        paths.root shouldBe Paths.get("/Users/ann/Library/Application Support/MediaSync")
        paths.isolated shouldBe false
    }

    @Test
    fun windowsUsesAppData() {
        resolve("Windows 11", mapOf("APPDATA" to "C:\\Users\\ann\\AppData\\Roaming")).root.fileName.toString() shouldBe
            "MediaSync"
    }

    @Test
    fun linuxFollowsXdgDataHome() {
        resolve("Linux", mapOf("XDG_DATA_HOME" to "/data/xdg")).root shouldBe Paths.get("/data/xdg/mediasync")
    }

    @Test
    fun linuxFallsBackToLocalShare() {
        resolve("Linux").root shouldBe Paths.get("/home/ann/.local/share/mediasync")
    }

    @Test
    fun theOverrideMovesEverythingAndIsolates() {
        val paths = resolve("Linux", mapOf(AppPaths.OVERRIDE to "/tmp/check"))

        paths.root shouldBe Paths.get("/tmp/check")
        paths.isolated shouldBe true
        paths.inbox shouldBe Paths.get("/tmp/check/inbox")
        paths.database shouldBe Paths.get("/tmp/check/mediasync.db")
        paths.settings shouldBe Paths.get("/tmp/check/settings.preferences_pb")
    }

    @Test
    fun aBlankOverrideIsIgnored() {
        resolve("Linux", mapOf(AppPaths.OVERRIDE to "  ")).isolated shouldBe false
    }
}

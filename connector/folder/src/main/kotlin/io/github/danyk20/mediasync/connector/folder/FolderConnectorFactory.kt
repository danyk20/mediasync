/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector.folder

import io.github.danyk20.mediasync.connector.BackupConnector
import io.github.danyk20.mediasync.connector.BackupConnectorFactory
import io.github.danyk20.mediasync.connector.Capability
import io.github.danyk20.mediasync.connector.ConnectorAccount
import io.github.danyk20.mediasync.connector.ConnectorDescriptor
import io.github.danyk20.mediasync.connector.ConnectorId
import io.github.danyk20.mediasync.connector.SettingField
import io.github.danyk20.mediasync.connector.SettingsSchema
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.time.ZoneId

/** Setting keys of the folder connector, in [ConnectorAccount.settings]. */
object FolderSettings {
    /** The folder to back up into: an absolute path on this computer. */
    const val FOLDER = "folder"

    /** How files are arranged inside it: [LAYOUT_YEAR_MONTH] (the default) or [LAYOUT_FLAT]. */
    const val LAYOUT = "layout"
    const val LAYOUT_YEAR_MONTH = "year-month"
    const val LAYOUT_FLAT = "flat"
}

/** The folder connector: a photo and video backup target that needs no sign-in (docs/connectors.md). */
val FolderDescriptor = ConnectorDescriptor(
    id = ConnectorId("folder"),
    displayName = "Folder",
    capabilities = setOf(Capability.VIDEO, Capability.VERIFY),
)

/**
 * Creates connectors backing up to the folder in [FolderSettings.FOLDER]. Dates in the folder names (`2025/05`) are
 * those of [zone], the computer's time zone unless a test says otherwise.
 */
class FolderConnectorFactory(private val zone: ZoneId = ZoneId.systemDefault()) : BackupConnectorFactory {

    override val descriptor: ConnectorDescriptor get() = FolderDescriptor

    override val settingsSchema: SettingsSchema = SettingsSchema(
        listOf(
            SettingField.Text(FolderSettings.FOLDER, required = true),
            SettingField.Choice(
                FolderSettings.LAYOUT,
                options = listOf(FolderSettings.LAYOUT_YEAR_MONTH, FolderSettings.LAYOUT_FLAT),
                default = FolderSettings.LAYOUT_YEAR_MONTH,
            ),
        ),
    )

    override fun create(account: ConnectorAccount): BackupConnector = FolderConnector(
        root = account.settings[FolderSettings.FOLDER]?.let(::parsePath),
        layout = if (account.settings[FolderSettings.LAYOUT] == FolderSettings.LAYOUT_FLAT) {
            FolderLayoutKind.FLAT
        } else {
            FolderLayoutKind.YEAR_MONTH
        },
        zone = zone,
    )

    private fun parsePath(text: String): Path? = try {
        text.takeIf { it.isNotBlank() }?.let { Paths.get(it) }?.takeIf { it.isAbsolute }
    } catch (_: InvalidPathException) {
        null
    }
}

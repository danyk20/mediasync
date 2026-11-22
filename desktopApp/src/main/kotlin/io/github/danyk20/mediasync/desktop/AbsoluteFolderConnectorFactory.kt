/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import io.github.danyk20.mediasync.connector.BackupConnector
import io.github.danyk20.mediasync.connector.BackupConnectorFactory
import io.github.danyk20.mediasync.connector.BackupItem
import io.github.danyk20.mediasync.connector.ConnectorAccount
import io.github.danyk20.mediasync.connector.ProgressSink
import io.github.danyk20.mediasync.connector.UploadResult
import io.github.danyk20.mediasync.connector.folder.FolderSettings

/**
 * The folder connector, except that a copy's remote id is its absolute path on this computer rather than a path
 * below the folder. The receipt then says where the copy is, so the copy is still checked there after the user
 * chooses another folder ([InboxCleaner]); the folder connector itself is unchanged.
 */
class AbsoluteFolderConnectorFactory(private val inner: BackupConnectorFactory) : BackupConnectorFactory by inner {

    override fun create(account: ConnectorAccount): BackupConnector {
        val connector = inner.create(account)
        val root = account.settings[FolderSettings.FOLDER]?.let(::fileOf)?.takeIf { it.isAbsolute }
        return object : BackupConnector by connector {
            override suspend fun upload(item: BackupItem, progress: ProgressSink): UploadResult =
                when (val result = connector.upload(item, progress)) {
                    is UploadResult.Uploaded -> if (root == null) {
                        result
                    } else {
                        result.copy(remoteId = root.resolve(result.remoteId).normalize().toString())
                    }

                    else -> result
                }
        }
    }
}

/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector.folder

import io.github.danyk20.mediasync.connector.BackupConnector
import io.github.danyk20.mediasync.connector.BackupConnectorFactory
import io.github.danyk20.mediasync.connector.BackupItem
import io.github.danyk20.mediasync.connector.BackupReceipt
import io.github.danyk20.mediasync.connector.ConnectorAccount
import io.github.danyk20.mediasync.connector.contract.BackupConnectorContractTest
import java.io.File
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/** The folder connector against the shared kit (docs/PLAN.md §9.5), with a temporary folder as the destination. */
class FolderContractTest : BackupConnectorContractTest() {

    @get:Rule
    val destination = TemporaryFolder()

    override val factory: BackupConnectorFactory by lazy {
        val real = FolderConnectorFactory()
        object : BackupConnectorFactory by real {
            override fun create(account: ConnectorAccount): BackupConnector =
                real.create(account.copy(settings = mapOf(FolderSettings.FOLDER to destination.root.absolutePath)))
        }
    }

    override fun store(item: BackupItem) = Unit

    override fun copiesAtDestination(item: BackupItem): List<ByteArray> = destination.root.walkTopDown()
        .filter { it.isFile && !it.name.startsWith(TEMP_PREFIX) }
        .map(File::readBytes)
        .toList()

    override fun deleteAtDestination(receipt: BackupReceipt): Boolean =
        destination.root.resolve(receipt.remoteId).delete()
}

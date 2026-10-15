/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector.googlephotos

import io.github.danyk20.mediasync.connector.BackupConnectorFactory
import io.github.danyk20.mediasync.connector.BackupItem
import io.github.danyk20.mediasync.connector.contract.BackupConnectorContractTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** The kit's checks, against a phone whose gallery is kept in memory (the real one: MediaStoreFolderTest). */
class GooglePhotosAppContractTest : BackupConnectorContractTest() {
    private val phone = FakePhone()

    override val factory: BackupConnectorFactory = GooglePhotosAppConnectorFactory(phone)

    override fun store(item: BackupItem) {
        phone.files[item.location] = FakePhone.File(item.content.open().use { it.readBytes() }, "DCIM/MediaSync/")
    }

    override fun copiesAtDestination(item: BackupItem): List<ByteArray> = listOfNotNull(
        phone.files[item.location]?.takeIf { !it.trashed && it.folder == "DCIM/MediaSync/" }?.bytes,
    )

    override fun removeFromPhone(item: BackupItem): Boolean = phone.files.remove(item.location) != null

    override fun trashOnPhone(item: BackupItem): Boolean {
        phone.files[item.location] = phone.files.getValue(item.location).copy(trashed = true)
        return true
    }
}

internal class FakePhone : PhoneFolder {
    data class File(val bytes: ByteArray, val folder: String, val trashed: Boolean = false)

    val files = mutableMapOf<String, File>()
    var readable = true

    override fun find(location: String): PhoneFile = when {
        !readable -> PhoneFile.Unreadable
        else -> files[location]?.let { PhoneFile.Found(it.folder, it.trashed) } ?: PhoneFile.Gone
    }

    override fun changes(): Flow<Unit> = emptyFlow()
}

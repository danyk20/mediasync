/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.database

import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.sync.engine.PairedPeer
import io.github.danyk20.mediasync.sync.engine.PeerRegistry
import io.github.danyk20.mediasync.sync.engine.ReceivedItem
import io.github.danyk20.mediasync.sync.engine.ReceivedLedger
import io.github.danyk20.mediasync.sync.engine.SentLedger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okio.ByteString
import okio.ByteString.Companion.decodeHex

/** A paired device as the UI shows it. */
data class Peer(
    val id: DeviceId,
    val name: String,
    val role: DeviceRole,
    val pairedAtMillis: Long,
    val lastAddress: String?,
    val lastSeenMillis: Long?,
    /** Producer side: the schedule the consumer asked for; `null` until it says. */
    val schedule: SyncSchedule? = null,
)

/** Paired devices: the engine's [PeerRegistry] plus what the UI and the transport need. */
@Singleton
class PeerStore @Inject constructor(private val dao: PeerDao) : PeerRegistry {
    override fun isPaired(id: DeviceId): Boolean = dao.get(id.hex) != null

    override fun register(peer: PairedPeer) {
        dao.upsert(PeerEntity(peer.id.hex, peer.name, peer.role.name, System.currentTimeMillis()))
    }

    fun register(peer: PairedPeer, address: String?) {
        val now = System.currentTimeMillis()
        dao.upsert(PeerEntity(peer.id.hex, peer.name, peer.role.name, now, address, address?.let { now }))
    }

    fun observeAll(): Flow<List<Peer>> = dao.observeAll().map { peers -> peers.map { it.toPeer() } }

    /** The consumer this producer sends to (one in v1.0): the most recently paired. */
    fun consumer(): Peer? = dao.withRole(DeviceRole.CONSUMER.name).firstOrNull()?.toPeer()

    /** [consumer], as it changes. */
    fun observeConsumer(): Flow<Peer?> =
        dao.observeWithRole(DeviceRole.CONSUMER.name).map { it.firstOrNull()?.toPeer() }

    /** Producer side: pairing with a consumer replaces any earlier one (one in v1.0); what was sent stays recorded. */
    fun keepOnlyConsumer(id: DeviceId) = dao.deleteOthersWithRole(DeviceRole.CONSUMER.name, id.hex)

    fun recordAddress(id: DeviceId, address: String) = dao.recordAddress(id.hex, address, System.currentTimeMillis())

    /** Producer side: keeps the schedule the consumer asked for; a `null` (it didn't say) changes nothing. */
    fun recordSchedule(id: DeviceId, schedule: SyncSchedule?) {
        schedule?.let { dao.recordSchedule(id.hex, it.encode()) }
    }

    fun forget(id: DeviceId) = dao.delete(id.hex)

    private fun PeerEntity.toPeer() = Peer(
        DeviceId(deviceId),
        name,
        DeviceRole.valueOf(role),
        pairedAtMillis,
        lastAddress,
        lastSeenMillis,
        schedule?.let(SyncSchedule::decode),
    )
}

@Singleton
class RoomSentLedger @Inject constructor(private val dao: SentItemDao) : SentLedger {
    override fun sentFingerprints(consumer: DeviceId): Set<ByteString> =
        dao.fingerprintsFor(consumer.hex).mapTo(HashSet()) { it.decodeHex() }

    override fun markSent(consumer: DeviceId, fingerprint: ByteString, contentId: ByteString) {
        dao.insert(SentItemEntity(consumer.hex, fingerprint.hex(), contentId.hex(), System.currentTimeMillis()))
    }

    fun observeLastSentAt(consumer: DeviceId): Flow<Long?> = dao.observeLastSentAt(consumer.hex)
}

@Singleton
class RoomReceivedLedger @Inject constructor(private val dao: ReceivedItemDao) : ReceivedLedger {
    override fun contentIdOf(fingerprint: ByteString): ByteString? = dao.contentIdOf(fingerprint.hex())?.decodeHex()

    override fun record(item: ReceivedItem) {
        val meta = item.descriptor.meta
        dao.insert(
            ReceivedItemEntity(
                producerId = item.producer.hex,
                fingerprint = item.descriptor.fingerprint.hex(),
                contentId = item.contentId.hex(),
                displayName = item.descriptor.displayName,
                mimeType = item.descriptor.mimeType,
                size = item.descriptor.size,
                capturedAtMillis = meta.capturedAtMillis,
                utcOffsetMinutes = meta.utcOffsetMinutes,
                album = meta.album,
                favorite = meta.favorite,
                location = item.location,
                receivedAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    fun observeCount(): Flow<Int> = dao.observeCount()

    fun observeCountFrom(producer: DeviceId): Flow<Int> = dao.observeCountFrom(producer.hex)
}

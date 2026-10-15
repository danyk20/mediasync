/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.database

import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.sync.engine.PAIR_SECRET_BYTES
import io.github.danyk20.mediasync.sync.engine.PairedPeer
import io.github.danyk20.mediasync.sync.engine.PeerRegistry
import io.github.danyk20.mediasync.sync.engine.ReceivedItem
import io.github.danyk20.mediasync.sync.engine.ReceivedLedger
import io.github.danyk20.mediasync.sync.engine.SentLedger
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString

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
    /** Producer side: the consumer's *Turbo receive* key (docs/PLAN.md §8.3); `null` until it says. */
    val turboKey: ByteString? = null,
    /** The pair's secret for internet signaling (ADR 0009 §4); `null` until known. */
    val pairSecret: ByteString? = null,
) {
    /** Never the key: this may end up in logs. */
    override fun toString() = "Peer($id, $name, $role, paired at $pairedAtMillis, last at $lastAddress)"
}

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

    /**
     * Producer side: keeps what it learned about the consumer in a session: where it was reached ([address]), its
     * [schedule], its *Turbo receive* [turboKey] (docs/PLAN.md §8.3) and their [pairSecret] (ADR 0009 §4). A `null`
     * (not reached on the local network, or an older consumer that didn't say) changes nothing.
     */
    fun recordConsumer(
        id: DeviceId,
        address: String? = null,
        schedule: SyncSchedule? = null,
        turboKey: ByteString? = null,
        pairSecret: ByteString? = null,
    ) {
        address?.let { dao.recordAddress(id.hex, it, System.currentTimeMillis()) }
        schedule?.let { dao.recordSchedule(id.hex, it.encode()) }
        turboKey?.let { dao.recordTurboKey(id.hex, it.hex()) }
        pairSecret?.let { dao.recordPairSecret(id.hex, it.hex()) }
    }

    /** Consumer side: the secret shared with [producer], made on first use; `null` if it isn't paired. */
    @Synchronized
    override fun pairSecret(producer: DeviceId): ByteString? {
        val peer = dao.get(producer.hex) ?: return null
        peer.pairSecret?.let { return it.decodeHex() }
        val secret = ByteArray(PAIR_SECRET_BYTES).also(SecureRandom()::nextBytes).toByteString()
        dao.recordPairSecret(producer.hex, secret.hex())
        return secret
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
        turboKey?.decodeHex(),
        pairSecret?.decodeHex(),
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

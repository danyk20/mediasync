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
    /** Producer side: the consumer's rendezvous Worker for internet sync; `null` until it says. */
    val rendezvousUrl: String? = null,
) {
    /** Never the key: this may end up in logs. */
    override fun toString() = "Peer($id, $name, $role, paired at $pairedAtMillis, last at $lastAddress)"
}

/**
 * What a producer learns about its consumer in a session: where it was reached ([address], on the local network only),
 * its [schedule], its *Turbo receive* [turboKey] (docs/PLAN.md §8.3), their [pairSecret] and its [rendezvousUrl] for
 * internet sync (ADR 0009). A `null` (not said, or an older consumer) changes nothing.
 */
data class ConsumerFacts(
    val address: String? = null,
    val schedule: SyncSchedule? = null,
    val turboKey: ByteString? = null,
    val pairSecret: ByteString? = null,
    val rendezvousUrl: String? = null,
) {
    /** Never the keys: this may end up in logs. */
    override fun toString() = "ConsumerFacts(address=$address, schedule=$schedule, rendezvousUrl=$rendezvousUrl)"
}

/** Paired devices: the engine's [PeerRegistry] plus what the UI and the transport need. */
@Singleton
@Suppress("TooManyFunctions") // One question about the paired phones each.
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

    /** As a receiving phone: the sending phones paired with it. */
    fun producers(): List<Peer> = dao.withRole(DeviceRole.PRODUCER.name).map { it.toPeer() }

    /** [consumer], as it changes. */
    fun observeConsumer(): Flow<Peer?> =
        dao.observeWithRole(DeviceRole.CONSUMER.name).map { it.firstOrNull()?.toPeer() }

    /** Producer side: pairing with a consumer replaces any earlier one (one in v1.0); what was sent stays recorded. */
    fun keepOnlyConsumer(id: DeviceId) = dao.deleteOthersWithRole(DeviceRole.CONSUMER.name, id.hex)

    /** Producer side: keeps what it learned about the consumer in a session; what [facts] leaves `null` stays. */
    fun recordConsumer(id: DeviceId, facts: ConsumerFacts) {
        facts.address?.let { dao.recordAddress(id.hex, it, System.currentTimeMillis()) }
        facts.schedule?.let { dao.recordSchedule(id.hex, it.encode()) }
        with(facts) {
            if (turboKey != null || pairSecret != null || rendezvousUrl != null) {
                dao.recordFacts(id.hex, turboKey?.hex(), pairSecret?.hex(), rendezvousUrl)
            }
        }
    }

    /** Consumer side: the secret shared with [producer], made on first use; `null` if it isn't paired. */
    @Synchronized
    override fun pairSecret(producer: DeviceId): ByteString? {
        val peer = dao.get(producer.hex) ?: return null
        peer.pairSecret?.let { return it.decodeHex() }
        val secret = ByteArray(PAIR_SECRET_BYTES).also(SecureRandom()::nextBytes).toByteString()
        dao.recordFacts(producer.hex, turboKey = null, pairSecret = secret.hex(), rendezvousUrl = null)
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
        rendezvousUrl,
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

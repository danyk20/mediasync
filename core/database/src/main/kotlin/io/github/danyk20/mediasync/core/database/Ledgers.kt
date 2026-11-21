/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.core.database

import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.sync.android.LeftOutList
import io.github.danyk20.mediasync.sync.android.PeerBook
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

typealias Peer = io.github.danyk20.mediasync.sync.android.Peer
typealias ConsumerFacts = io.github.danyk20.mediasync.sync.android.ConsumerFacts

/** Paired devices: the engine's [PeerRegistry] plus what the UI and the transport need. */
@Singleton
@Suppress("TooManyFunctions") // One question about the paired phones each.
class PeerStore @Inject constructor(private val dao: PeerDao) : PeerBook {
    override fun isPaired(id: DeviceId): Boolean = dao.get(id.hex) != null

    override fun register(peer: PairedPeer) {
        dao.upsert(PeerEntity(peer.id.hex, peer.name, peer.role.name, System.currentTimeMillis()))
    }

    override fun register(peer: PairedPeer, address: String?) {
        val now = System.currentTimeMillis()
        dao.upsert(PeerEntity(peer.id.hex, peer.name, peer.role.name, now, address, address?.let { now }))
    }

    fun observeAll(): Flow<List<Peer>> = dao.observeAll().map { peers -> peers.map { it.toPeer() } }

    /** The consumer this producer sends to (one in v1.0): the most recently paired. */
    override fun consumer(): Peer? = dao.withRole(DeviceRole.CONSUMER.name).firstOrNull()?.toPeer()

    /** As a receiving phone: the sending phones paired with it. */
    override fun producers(): List<Peer> = dao.withRole(DeviceRole.PRODUCER.name).map { it.toPeer() }

    /** [consumer], as it changes. */
    fun observeConsumer(): Flow<Peer?> =
        dao.observeWithRole(DeviceRole.CONSUMER.name).map { it.firstOrNull()?.toPeer() }

    /** Producer side: pairing with a consumer replaces any earlier one (one in v1.0); what was sent stays recorded. */
    override fun keepOnlyConsumer(id: DeviceId) = dao.deleteOthersWithRole(DeviceRole.CONSUMER.name, id.hex)

    /** Producer side: keeps what it learned about the consumer in a session; what [facts] leaves `null` stays. */
    override fun recordConsumer(id: DeviceId, facts: ConsumerFacts) {
        facts.address?.let { dao.recordAddress(id.hex, it, System.currentTimeMillis()) }
        facts.schedule?.let { dao.recordSchedule(id.hex, it.encode()) }
        with(facts) {
            val wifi = wifiAddresses?.takeIf { it.isNotEmpty() }?.joinToString(" ")
            val told = listOf(turboKey, pairSecret, rendezvousUrl, wifi)
            if (told.any { it != null }) {
                dao.recordFacts(id.hex, turboKey?.hex(), pairSecret?.hex(), rendezvousUrl, wifi)
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
        wifiAddresses?.split(' ')?.filter { it.isNotEmpty() }.orEmpty(),
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

/** Producer side: the fingerprints of the photos and videos the user chose not to sync. */
@Singleton
class LeftOutStore @Inject constructor(private val dao: LeftOutItemDao) : LeftOutList {
    /** Blocking, for the sync engine's I/O threads. */
    override fun fingerprints(): Set<ByteString> = dao.fingerprints().toFingerprints()

    override fun observe(): Flow<Set<ByteString>> = dao.observeFingerprints().map { it.toFingerprints() }

    override suspend fun add(fingerprints: Collection<ByteString>) {
        val now = System.currentTimeMillis()
        dao.insert(fingerprints.map { LeftOutItemEntity(it.hex(), now) })
    }

    // In chunks: Android 10's SQLite takes at most 999 arguments.
    override suspend fun remove(fingerprints: Collection<ByteString>) =
        fingerprints.map { it.hex() }.chunked(MAX_ARGUMENTS).forEach { dao.delete(it) }

    private fun List<String>.toFingerprints(): Set<ByteString> = mapTo(HashSet()) { it.decodeHex() }

    private companion object {
        const val MAX_ARGUMENTS = 500
    }
}

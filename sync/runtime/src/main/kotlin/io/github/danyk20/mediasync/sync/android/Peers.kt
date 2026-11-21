/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.core.model.SyncSchedule
import io.github.danyk20.mediasync.sync.engine.PairedPeer
import io.github.danyk20.mediasync.sync.engine.PeerRegistry
import kotlinx.coroutines.flow.Flow
import okio.ByteString

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
    /** Producer side: where the consumer last said it listens on its Wi-Fi (`192.168.1.20:47420`). */
    val wifiAddresses: List<String> = emptyList(),
) {
    /** Never the key: this may end up in logs. */
    override fun toString() = "Peer($id, $name, $role, paired at $pairedAtMillis, last at $lastAddress)"
}

/**
 * What a producer learns about its consumer in a session: where it was reached ([address], on the local network only),
 * its [schedule], its *Turbo receive* [turboKey] (docs/PLAN.md §8.3), their [pairSecret] and its [rendezvousUrl] for
 * internet sync (ADR 0009), and where it said it listens on its Wi-Fi ([wifiAddresses], docs/PLAN.md §8.2). A `null`
 * (not said, or an older consumer) changes nothing.
 */
data class ConsumerFacts(
    val address: String? = null,
    val schedule: SyncSchedule? = null,
    val turboKey: ByteString? = null,
    val pairSecret: ByteString? = null,
    val rendezvousUrl: String? = null,
    val wifiAddresses: List<String>? = null,
) {
    /** Never the keys: this may end up in logs. */
    override fun toString() = "ConsumerFacts(address=$address, schedule=$schedule, rendezvousUrl=$rendezvousUrl, " +
        "wifiAddresses=$wifiAddresses)"
}

/** The paired phones, as the sync runtime needs them; Room-backed on Android. */
interface PeerBook : PeerRegistry {
    fun register(peer: PairedPeer, address: String?)

    /** The consumer this producer sends to; `null` while not paired. */
    fun consumer(): Peer?

    /** As a receiving phone: the sending phones paired with it. */
    fun producers(): List<Peer>

    /** Producer side: pairing with a consumer replaces any earlier one. */
    fun keepOnlyConsumer(id: DeviceId)

    /** Producer side: keeps what it learned about the consumer in a session. */
    fun recordConsumer(id: DeviceId, facts: ConsumerFacts)
}

/** The fingerprints of the photos and videos the user left out; Room-backed on Android. */
interface LeftOutList {
    /** Blocking, for the sync engine's I/O threads. */
    fun fingerprints(): Set<ByteString>

    fun observe(): Flow<Set<ByteString>>

    suspend fun add(fingerprints: Collection<ByteString>)

    suspend fun remove(fingerprints: Collection<ByteString>)
}

/** Where a finished sync is recorded; SharedPreferences-backed on Android. */
interface SyncRecorder {
    fun recordSync(atMillis: Long, waitingForSpace: Int)
}

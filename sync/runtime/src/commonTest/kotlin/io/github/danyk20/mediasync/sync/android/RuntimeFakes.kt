/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.sync.engine.LocalDevice
import io.github.danyk20.mediasync.sync.engine.MediaSource
import io.github.danyk20.mediasync.sync.engine.PairedPeer
import io.github.danyk20.mediasync.sync.engine.testing.FakeMediaSource
import io.github.danyk20.mediasync.sync.engine.testing.FakeSentLedger
import io.github.danyk20.mediasync.transport.PeerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import okio.ByteString
import okio.Closeable
import okio.IOException

/** The paired phones, as a test sets them: one consumer, or none. */
internal class FakePeers(var current: Peer? = null) : PeerBook {
    override fun isPaired(id: DeviceId): Boolean = current?.id == id

    override fun register(peer: PairedPeer) = Unit

    override fun register(peer: PairedPeer, address: String?) = Unit

    override fun consumer(): Peer? = current

    override fun producers(): List<Peer> = emptyList()

    override fun keepOnlyConsumer(id: DeviceId) = Unit

    override fun recordConsumer(id: DeviceId, facts: ConsumerFacts) = Unit
}

/** Left-out fingerprints in memory. */
internal class FakeLeftOutList : LeftOutList {
    private val state = MutableStateFlow<Set<ByteString>>(emptySet())

    override fun fingerprints(): Set<ByteString> = state.value

    override fun observe(): Flow<Set<ByteString>> = state

    override suspend fun add(fingerprints: Collection<ByteString>) = state.update { it + fingerprints }

    override suspend fun remove(fingerprints: Collection<ByteString>) = state.update { it - fingerprints.toSet() }
}

internal object NoSyncRecord : SyncRecorder {
    override fun recordSync(atMillis: Long, waitingForSpace: Int) = Unit
}

/** Hands out [connections] in order, and refuses once they run out. */
internal class FakeLanLinks(private val connections: ArrayDeque<PeerConnection>) : LanLinks {
    override fun connect(
        address: HostPort,
        expected: DeviceId,
        connectTimeoutMillis: Int,
        via: LinkRoute?,
    ): PeerConnection = connections.removeFirstOrNull() ?: throw IOException("Refused at $address")
}

/** Advertises nothing and finds nothing. */
internal object NoDiscovery : ServiceDiscovery {
    override fun advertise(port: Int, serviceId: String): Closeable = object : Closeable {
        override fun close() = Unit
    }

    override suspend fun find(serviceId: String, timeoutMillis: Long): String? = null
}

/** A producer wired to the fakes above; the sync's own collaborators are the given ones. */
internal fun producerSync(
    peers: PeerBook,
    lan: LanLinks,
    scope: CoroutineScope,
    source: MediaSource = FakeMediaSource(),
): ProducerSync = ProducerSync(
    peers = peers,
    ledger = FakeSentLedger(),
    source = source,
    leftOut = LeftOutItems(FakeLeftOutList(), source),
    device = DeviceInfo { LocalDevice("Test phone", "0.0.0") },
    network = SendLinks(lan, NoDiscovery),
    history = NoSyncRecord,
    conditions = DeviceConditions { null },
    scope = scope,
    turboSearch = TurboReceiveSearch(),
)

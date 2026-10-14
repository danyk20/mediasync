/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.engine.testing

import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.sync.engine.Fingerprints
import io.github.danyk20.mediasync.sync.engine.IncomingFile
import io.github.danyk20.mediasync.sync.engine.MediaDescriptor
import io.github.danyk20.mediasync.sync.engine.MediaMeta
import io.github.danyk20.mediasync.sync.engine.MediaSink
import io.github.danyk20.mediasync.sync.engine.MediaSource
import io.github.danyk20.mediasync.sync.engine.PairedPeer
import io.github.danyk20.mediasync.sync.engine.PairingDecision
import io.github.danyk20.mediasync.sync.engine.PairingGate
import io.github.danyk20.mediasync.sync.engine.PairingRequest
import io.github.danyk20.mediasync.sync.engine.PeerRegistry
import io.github.danyk20.mediasync.sync.engine.ReceivedItem
import io.github.danyk20.mediasync.sync.engine.ReceivedLedger
import io.github.danyk20.mediasync.sync.engine.SentLedger
import io.github.danyk20.mediasync.sync.engine.SourceItem
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import okio.Source

/** A photo or video held in memory. */
class FakeMedia(
    val key: String,
    val bytes: ByteArray,
    val displayName: String = "$key.jpg",
    val mimeType: String = "image/jpeg",
    val meta: MediaMeta = MediaMeta(capturedAtMillis = key.hashCode().toLong()),
) {
    val descriptor = MediaDescriptor(Fingerprints.of(bytes), bytes.size.toLong(), mimeType, displayName, meta)
    val item = SourceItem(key, descriptor)
}

class FakeMediaSource(media: List<FakeMedia> = emptyList()) : MediaSource {
    private val media = ConcurrentHashMap(media.associateBy { it.key })
    val opened = mutableListOf<String>()

    fun put(item: FakeMedia) {
        media[item.key] = item
    }

    fun remove(key: String) {
        media.remove(key)
    }

    override fun items(): List<SourceItem> = media.values.map { it.item }

    override fun open(item: SourceItem): Source {
        val found = media[item.key] ?: throw IOException("${item.key} no longer exists")
        synchronized(opened) { opened += item.key }
        return Buffer().write(found.bytes)
    }
}

class FakeSentLedger : SentLedger {
    val entries = ConcurrentHashMap<Pair<DeviceId, ByteString>, ByteString>()

    override fun sentFingerprints(consumer: DeviceId): Set<ByteString> =
        entries.keys.filter { it.first == consumer }.map { it.second }.toSet()

    override fun markSent(consumer: DeviceId, fingerprint: ByteString, contentId: ByteString) {
        entries[consumer to fingerprint] = contentId
    }
}

class FakeReceivedLedger : ReceivedLedger {
    val items = mutableListOf<ReceivedItem>()

    override fun contentIdOf(fingerprint: ByteString): ByteString? =
        synchronized(items) { items.firstOrNull { it.descriptor.fingerprint == fingerprint }?.contentId }

    override fun record(item: ReceivedItem) {
        synchronized(items) { items += item }
    }
}

class FakePeerRegistry(vararg paired: PairedPeer) : PeerRegistry {
    val peers = ConcurrentHashMap(paired.associateBy { it.id })

    override fun isPaired(id: DeviceId) = peers.containsKey(id)

    override fun register(peer: PairedPeer) {
        peers[peer.id] = peer
    }
}

/** Accepts exactly one token, like the consumer's QR screen; records what was asked. */
class FakePairingGate(private val validToken: ByteString, private val userAccepts: Boolean = true) : PairingGate {
    val requests = mutableListOf<PairingRequest>()

    override fun decide(request: PairingRequest): PairingDecision {
        requests += request
        return when {
            request.token != validToken -> PairingDecision.INVALID_TOKEN
            userAccepts -> PairingDecision.ACCEPTED
            else -> PairingDecision.DECLINED
        }
    }
}

/**
 * Stores files in memory. Committed files are visible in [stored]; interrupted ones stay in [partials]
 * until resumed, like MediaStore's IS_PENDING items.
 */
class FakeMediaSink(
    var availableBytes: Long = Long.MAX_VALUE,
    /** When set, writing fails after this many bytes, like a full or broken disk. */
    var failWritesAfter: Long? = null,
) : MediaSink {
    val stored = ConcurrentHashMap<String, ByteArray>()
    val partials = ConcurrentHashMap<Pair<DeviceId, ByteString>, Buffer>()
    private var nextLocation = 0

    override fun availableBytes() = availableBytes

    override fun partialSize(producer: DeviceId, fingerprint: ByteString): Long =
        partials[producer to fingerprint]?.size ?: 0

    override fun open(producer: DeviceId, descriptor: MediaDescriptor, offset: Long): IncomingFile {
        val key = producer to descriptor.fingerprint
        val partial = partials.getOrPut(key) { Buffer() }
        require(offset <= partial.size) { "Can't resume at $offset; only ${partial.size} bytes kept" }
        val kept = Buffer().write(partial.snapshot().substring(0, offset.toInt()))
        partials[key] = kept
        return object : IncomingFile {
            private var done = false

            override fun existingBytes(): Source = Buffer().write(kept.snapshot())

            override fun write(bytes: ByteArray, offset: Int, byteCount: Int) {
                val limit = failWritesAfter
                if (limit != null && kept.size + byteCount > limit) throw IOException("Disk full")
                kept.write(bytes, offset, byteCount)
            }

            override fun commit(): String {
                val location = "content://fake/${nextLocation++}/${descriptor.displayName}"
                stored[location] = kept.readByteArray()
                partials.remove(key)
                done = true
                return location
            }

            override fun discard() {
                partials.remove(key)
                done = true
            }

            override fun close() {
                // Uncommitted bytes stay in `partials` so a later session can resume.
            }
        }
    }

    fun storedContents(): Set<ByteString> = stored.values.map { it.toByteString() }.toSet()
}

/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.webrtc

import android.content.Context
import io.github.danyk20.mediasync.core.model.DeviceId
import io.github.danyk20.mediasync.transport.PeerConnection as SyncConnection
import io.github.danyk20.mediasync.transport.rendezvous.ChannelPeerConnection
import io.github.danyk20.mediasync.transport.rendezvous.ChannelStream
import io.github.danyk20.mediasync.transport.rendezvous.IceServer
import io.github.danyk20.mediasync.transport.rendezvous.MessageChannel
import io.github.danyk20.mediasync.transport.rendezvous.PeerLinks
import io.github.danyk20.mediasync.transport.rendezvous.RelayCounter
import io.github.danyk20.mediasync.transport.rendezvous.Signal
import io.github.danyk20.mediasync.transport.rendezvous.SignalPipe
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStats
import org.webrtc.RTCStatsReport
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/**
 * Internet sync's connection (PLAN §8.4): WebRTC between the two phones, direct where their networks allow, through the
 * TURN relay otherwise. One ordered, reliable data channel carries an ordinary sync session, so the sync engine runs as
 * on Wi-Fi. The signals that set it up are sealed with the pair's key, which also vouches for the DTLS fingerprints in
 * them: nobody in between can take part.
 */
class WebRtcLink(context: Context) : PeerLinks {
    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(
                context.applicationContext,
            ).createInitializationOptions(),
        )
        PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    /** As the producer: offers, and returns the connection once its data channel is open. Blocks. */
    override fun connect(
        peer: DeviceId,
        iceServers: List<IceServer>,
        pipe: SignalPipe,
        timeoutMillis: Long,
    ): SyncConnection {
        val session = UUID.randomUUID().toString()
        val attempt = Attempt(peer, session, iceServers, pipe)
        attempt.startAsOfferer()
        return attempt.await(timeoutMillis)
    }

    /** As the consumer: answers [offer] and returns the connection once the producer's data channel is open. Blocks. */
    override fun accept(
        peer: DeviceId,
        offer: Signal.Offer,
        iceServers: List<IceServer>,
        pipe: SignalPipe,
        timeoutMillis: Long,
    ): SyncConnection {
        val attempt = Attempt(peer, offer.session, iceServers, pipe)
        attempt.startAsAnswerer(offer)
        return attempt.await(timeoutMillis)
    }

    /** One attempt to connect, until its data channel opens or it gives up. */
    private inner class Attempt(
        private val peer: DeviceId,
        private val session: String,
        iceServers: List<IceServer>,
        private val pipe: SignalPipe,
    ) {
        private val opened = CountDownLatch(1)

        @Volatile
        private var channel: DataChannel? = null

        @Volatile
        private var failure: String? = null
        private var stream: ChannelStream? = null
        private val connection: PeerConnection = checkNotNull(
            factory.createPeerConnection(
                PeerConnection.RTCConfiguration(iceServers.map { it.toWebRtc() }).apply {
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                    continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
                },
                Events(),
            ),
        ) { "WebRTC couldn't create a connection" }

        fun startAsOfferer() {
            adopt(connection.createDataChannel(CHANNEL, DataChannel.Init().apply { ordered = true }))
            connection.createOffer(
                created { offer ->
                    connection.setLocalDescription(done(), offer)
                    pipe.send(Signal.Offer(session, offer.description))
                },
                MediaConstraints(),
            )
            relaySignals()
        }

        fun startAsAnswerer(offer: Signal.Offer) {
            connection.setRemoteDescription(
                done {
                    connection.createAnswer(
                        created { answer ->
                            connection.setLocalDescription(done(), answer)
                            pipe.send(Signal.Answer(session, answer.description))
                        },
                        MediaConstraints(),
                    )
                },
                SessionDescription(SessionDescription.Type.OFFER, offer.sdp),
            )
            relaySignals()
        }

        /** Applies the other phone's answer and candidates as they come, until connected or given up. */
        private fun relaySignals() = Thread({
            while (opened.count > 0 && failure == null) {
                // Signals from an earlier attempt are ignored.
                pipe.incoming.poll(POLL_MILLIS, TimeUnit.MILLISECONDS)?.takeIf { it.session == session }?.let(::apply)
            }
        }, "mediasync-webrtc-signals").apply { isDaemon = true }.start()

        private fun apply(signal: Signal) = when (signal) {
            is Signal.Answer ->
                connection.setRemoteDescription(done(), SessionDescription(SessionDescription.Type.ANSWER, signal.sdp))

            is Signal.Candidate ->
                connection.addIceCandidate(IceCandidate(signal.sdpMid, signal.sdpMLineIndex, signal.candidate))

            is Signal.Bye -> fail("The other phone gave up")

            is Signal.Offer -> Unit // Only one offer per attempt.
        }

        fun await(timeoutMillis: Long): SyncConnection {
            val ready = opened.await(timeoutMillis, TimeUnit.MILLISECONDS)
            val open = stream
            if (!ready || open == null || failure != null) {
                pipe.send(Signal.Bye(session))
                close()
                throw IOException(failure ?: "No WebRTC connection within $timeoutMillis ms")
            }
            return Link(ChannelPeerConnection(peer, open, REMOTE_ADDRESS))
        }

        /** The open connection; it counts what goes through the relay (PLAN §8.5) from WebRTC's statistics. */
        private inner class Link(private val channel: ChannelPeerConnection) :
            SyncConnection by channel,
            RelayCounter {
            @Volatile
            private var lastRelayed = 0L

            override fun relayedBytes(): Long {
                if (closed.get()) return lastRelayed
                val report = AtomicReference<RTCStatsReport?>()
                val done = CountDownLatch(1)
                connection.getStats { report.set(it).also { done.countDown() } }
                if (done.await(STATS_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                    report.get()?.let { lastRelayed = relayedBytes(it.statsMap) }
                }
                return lastRelayed
            }
        }

        private fun adopt(dataChannel: DataChannel) {
            channel = dataChannel
            val stream = ChannelStream(Channel(dataChannel)).also { stream = it }
            dataChannel.registerObserver(
                object : DataChannel.Observer {
                    override fun onBufferedAmountChange(previousAmount: Long) = stream.onBufferedAmountChange()

                    override fun onStateChange() {
                        when (dataChannel.state()) {
                            DataChannel.State.OPEN -> opened.countDown()
                            DataChannel.State.CLOSED -> stream.onClosed()
                            else -> Unit
                        }
                    }

                    override fun onMessage(buffer: DataChannel.Buffer) {
                        val bytes = ByteArray(buffer.data.remaining())
                        buffer.data.get(bytes)
                        stream.onMessage(bytes)
                    }
                },
            )
        }

        private fun fail(reason: String) {
            failure = reason
            stream?.onClosed(IOException(reason))
            opened.countDown()
        }

        private val closed = AtomicBoolean()

        /** Closes and frees the native connection, once. */
        private fun close() {
            if (!closed.compareAndSet(false, true)) return
            channel?.run {
                unregisterObserver()
                close()
                dispose()
            }
            connection.dispose() // Closes it, then frees it.
        }

        private inner class Channel(private val dataChannel: DataChannel) : MessageChannel {
            override fun send(message: ByteArray): Boolean =
                dataChannel.send(DataChannel.Buffer(ByteBuffer.wrap(message), true))

            override fun bufferedAmount(): Long = dataChannel.bufferedAmount()

            override fun close() = this@Attempt.close()
        }

        @Suppress("TooManyFunctions") // PeerConnection.Observer has them all abstract.
        private inner class Events : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) =
                pipe.send(Signal.Candidate(session, candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp))

            override fun onDataChannel(dataChannel: DataChannel) = adopt(dataChannel)

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                if (state == PeerConnection.IceConnectionState.FAILED) fail("WebRTC found no way between the phones")
                if (state == PeerConnection.IceConnectionState.CLOSED) stream?.onClosed()
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit

            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit

            override fun onAddStream(stream: MediaStream) = Unit

            override fun onRemoveStream(stream: MediaStream) = Unit

            override fun onRenegotiationNeeded() = Unit

            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
        }

        private fun created(then: (SessionDescription) -> Unit) = object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) = then(description)

            override fun onSetSuccess() = Unit

            override fun onCreateFailure(error: String) = fail("WebRTC couldn't describe the session: $error")

            override fun onSetFailure(error: String) = Unit
        }

        private fun done(then: () -> Unit = {}) = object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) = Unit

            override fun onSetSuccess() = then()

            override fun onCreateFailure(error: String) = Unit

            override fun onSetFailure(error: String) = fail("WebRTC couldn't apply a session description: $error")
        }
    }

    private companion object {
        const val CHANNEL = "mediasync"
        const val REMOTE_ADDRESS = "webrtc"
        const val POLL_MILLIS = 200L
        const val STATS_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * The bytes sent and received over the selected candidate pair, if it goes through a TURN relay on either side
 * (a `relay` candidate); 0 for a direct connection. [stats] is WebRTC's statistics report, by id.
 */
internal fun relayedBytes(stats: Map<String, RTCStats>): Long {
    val selectedId = stats.values.firstOrNull { it.type == "transport" }?.members?.get("selectedCandidatePairId")
    val pair = stats[selectedId] ?: stats.values.firstOrNull {
        it.type == "candidate-pair" && it.members["nominated"] == true && it.members["state"] == "succeeded"
    } ?: return 0
    val relayed = listOf("localCandidateId", "remoteCandidateId").any { side ->
        stats[pair.members[side]]?.members?.get("candidateType") == "relay"
    }
    if (!relayed) return 0
    return listOf("bytesSent", "bytesReceived").sumOf { (pair.members[it] as? Number)?.toLong() ?: 0L }
}

private fun IceServer.toWebRtc(): PeerConnection.IceServer = PeerConnection.IceServer.builder(urls).apply {
    username?.let(::setUsername)
    credential?.let(::setPassword)
}.createIceServer()

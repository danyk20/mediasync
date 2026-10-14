/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.rendezvous

import com.sun.net.httpserver.HttpServer
import io.github.danyk20.mediasync.core.model.DeviceId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import org.junit.jupiter.api.Test

/** The phone's side of the rendezvous Worker's API (ADR 0009 §3). */
class RendezvousClientTest {

    private val producer = DeviceId("2".repeat(64))
    private val signer =
        RendezvousSigner(certificate = byteArrayOf(9, 9, 9), sign = { it.copyOf(8) }, clock = { 1_000_000 })
    private val sent = mutableListOf<HttpRequest>()
    private var answers = ArrayDeque<HttpResponse>()

    private fun client() = RendezvousClient(URI("https://rendezvous.example.com"), signer) { request ->
        sent += request
        answers.removeFirst()
    }

    private fun answer(status: Int, json: String, headers: Map<String, String> = emptyMap()) {
        answers.addLast(HttpResponse(status, headers, json.encodeToByteArray()))
    }

    @Test
    fun theConsumerRegistersThePairUnderTheDerivedIdWithItsPushToken() {
        answer(201, """{"pairId":"x","created":true}""")
        answer(200, """{"pairId":"x","created":false}""")
        answer(200, """{"pairId":"x","created":false}""")
        val pair = pairId(signer.deviceId, producer)

        client().registerPair(producer, PushToken.Of("fcm-token")) shouldBe PairRegistration("x", created = true)
        client().registerPair(producer) // Keeps the token.
        client().registerPair(producer, PushToken.Remove)

        sent.map { it.method to it.url.toString() }.distinct() shouldBe
            listOf("PUT" to "https://rendezvous.example.com/v1/pairs/$pair")
        sent.map { it.body!!.decodeToString() } shouldBe listOf(
            """{"producer":"${producer.hex}","fcmToken":"fcm-token"}""",
            """{"producer":"${producer.hex}"}""",
            """{"producer":"${producer.hex}","fcmToken":null}""",
        )
        sent.first().headers shouldContainKey RendezvousSigner.SIGNATURE
        sent.first().headers["Content-Type"] shouldBe "application/json"
        PushToken.Of("fcm-token").toString() shouldNotContain "fcm-token"
    }

    @Test
    fun iceServersComeWithTheirLifetimeAndWhetherTheRelayIsOffered() {
        answer(
            200,
            """{"iceServers":[{"urls":["stun:stun.cloudflare.com:3478"]},""" +
                """{"urls":["turn:turn.cloudflare.com:3478"],"username":"u","credential":"secret"}],""" +
                """"ttlSeconds":3600,"relay":{"available":true}}""",
        )
        answer(
            200,
            """{"iceServers":[{"urls":["stun:stun.cloudflare.com:3478"]}],"ttlSeconds":3600,""" +
                """"relay":{"available":false,"reason":"budget_exhausted","resumesAt":"2026-11-01T00:00:00.000Z"}}""",
        )

        val offered = client().iceServers("p")
        val exhausted = client().iceServers("p")

        offered.iceServers shouldHaveSize 2
        offered.relay shouldBe RelayAvailability(available = true)
        offered.iceServers[1].credential shouldBe "secret"
        offered.iceServers[1].toString() shouldNotContain "secret"
        exhausted.relay shouldBe RelayAvailability(false, "budget_exhausted", "2026-11-01T00:00:00.000Z")
        sent.first().method shouldBe "POST"
        sent.first().url.path shouldBe "/v1/pairs/p/ice-servers"
    }

    @Test
    fun theRelayMeterIsReadAndReportedTo() {
        val meter = """{"period":{"start":"2026-10-01T00:00:00.000Z","end":"2026-11-01T00:00:00.000Z"},""" +
            """"usedBytes":312000000000,"authoritativeBytes":300000000000,"authoritativeAt":null,""" +
            """"liveBytes":12000000000,"limitBytes":950000000000,"freeAllowanceBytes":1000000000000,""" +
            """"relayAvailable":true,"settings":{"budgetGb":950},"pair":{"approxBytes":5}}"""
        answer(200, meter)
        answer(200, meter)

        val read = client().relayUsage("p")
        client().reportRelayUsage("p", session = "AAAAAAAAAAAAAAAAAAAAAA", relayedBytes = 1_048_576)

        read.usedBytes shouldBe 312_000_000_000
        read.pairShare shouldBe RelayMeter.PairShare(5)
        read.period.end shouldBe "2026-11-01T00:00:00.000Z"
        sent[1].body!!.decodeToString() shouldBe """{"session":"AAAAAAAAAAAAAAAAAAAAAA","relayedBytes":1048576}"""
    }

    @Test
    fun aClockTheWorkerFindsOffIsCorrectedAndTheRequestRetriedOnce() {
        answer(
            401,
            """{"error":{"code":"auth_clock_skew","message":"Your clock is off"}}""",
            mapOf("x-mediasync-server-time" to "5000"),
        )
        answer(200, """{"pairId":"p","role":"producer","pushTokenRegistered":true,"peerOnline":false}""")

        val status = client().pairStatus("p")

        status shouldBe PairStatus("p", "producer", pushTokenRegistered = true, peerOnline = false)
        sent.map { it.headers.getValue(RendezvousSigner.TIMESTAMP) } shouldBe listOf("1000", "5000")
    }

    @Test
    fun refusalsCarryTheWorkersCodeAndWhenToTryAgain() {
        answer(
            429,
            """{"error":{"code":"wake_throttled","message":"A wake-up was sent moments ago"}}""",
            mapOf("retry-after" to "12"),
        )
        answer(502, "Bad gateway")
        answer(200, """{"deleted":true}""")

        val throttled = shouldThrow<RendezvousException> { client().wake("p", payload = "hi") }
        val gateway = shouldThrow<RendezvousException> { client().wake("p") }
        client().unpair("p")

        throttled.code shouldBe "wake_throttled"
        throttled.retryAfterSeconds shouldBe 12
        gateway.code shouldBe "http_502"
        sent[0].body!!.decodeToString() shouldBe """{"payload":"hi"}"""
        sent.last().method shouldBe "DELETE"
        sent.last().body shouldBe null
    }

    @Test
    fun theRealTransportSendsHeadersAndBodyAndReadsErrors() {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            val reply = "${exchange.requestMethod} ${exchange.requestHeaders.getFirst("X-Test")} $body"
                .encodeToByteArray()
            val status = if (exchange.requestURI.path == "/fail") 409 else 200
            exchange.responseHeaders.add("Retry-After", "7")
            exchange.sendResponseHeaders(status, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val transport = UrlConnectionTransport()

            val ok = transport.execute(
                HttpRequest("POST", URI("$base/ok"), mapOf("X-Test" to "t"), "b".encodeToByteArray()),
            )
            val failed = transport.execute(HttpRequest("GET", URI("$base/fail"), mapOf("X-Test" to "u"), null))

            ok.status shouldBe 200
            ok.body.decodeToString() shouldBe "POST t b"
            failed.status shouldBe 409
            failed.body.decodeToString() shouldBe "GET u "
            failed.headers["retry-after"] shouldBe "7"
        } finally {
            server.stop(0)
        }
    }
}

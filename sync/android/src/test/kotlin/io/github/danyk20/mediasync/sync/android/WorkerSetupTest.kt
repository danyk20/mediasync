/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import io.github.danyk20.mediasync.core.diagnostics.Diagnostics
import io.github.danyk20.mediasync.core.testing.FakeSettingsRepository
import io.github.danyk20.mediasync.transport.rendezvous.HttpRequest
import io.github.danyk20.mediasync.transport.rendezvous.HttpResponse
import io.github.danyk20.mediasync.transport.rendezvous.WorkerDeployer
import io.github.danyk20.mediasync.transport.rendezvous.WorkerScript
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Setting up internet sync with the user's Cloudflare account, against Cloudflare as faked here. */
@RunWith(RobolectricTestRunner::class)
class WorkerSetupTest {

    private val script = WorkerScript(
        module = "export default {}".encodeToByteArray(),
        compatibilityDate = "2026-10-01",
        vars = emptyMap(),
        durableObjects = mapOf("PAIRS" to "PairMailbox"),
        rateLimits = emptyList(),
        crons = emptyList(),
        migrationTag = "v1",
    )
    private val settings = FakeSettingsRepository()
    private val receiver = mockk<ConsumerReceiver>(relaxed = true)
    private val tokens = mutableListOf<String>()
    private val requests = mutableListOf<HttpRequest>()

    /** What the network throws, if anything. */
    private var failure: Throwable? = null

    /** Cloudflare's answer to a path, or `null` for its default. */
    private var answer: (method: String, path: String) -> Pair<Int, String>? = { _, _ -> null }

    /** Health checks until the Worker answers. */
    private var unansweredHealthChecks = 0

    /** What checking wake-ups through the new Worker finds. */
    private var wakeUps: WakeUpStatus? = null
    private val wakeUpChecks = mutableListOf<String>()

    private fun setup(bundle: WorkerScript? = script) = WorkerSetup(
        bundle = { bundle },
        deployer = { token ->
            tokens += token
            WorkerDeployer(token, { request ->
                requests += request
                failure?.let { throw it }
                // As Android's HTTP stack does: a header value with a line break or other control character is refused.
                request.headers.forEach { (name, value) ->
                    require(value.all { it == '\t' || it in ' '..'~' }) { "Unexpected char in $name value" }
                }
                val path = request.url.path.removePrefix("/client/v4/")
                if (path == "/v1/health") {
                    val status = if (unansweredHealthChecks-- > 0) 404 else 200
                    return@WorkerDeployer HttpResponse(status, emptyMap(), ByteArray(0))
                }
                val (status, result) = answer(request.method, path) ?: (200 to defaultResult(request.method, path))
                val body = if (status < 300) {
                    """{"success":true,"result":$result}"""
                } else {
                    """{"success":false,"errors":$result}"""
                }
                HttpResponse(status, emptyMap(), body.encodeToByteArray())
            })
        },
        settings = settings,
        receiver = receiver,
        startTimeoutMillis = 2_000,
        pollMillis = 10,
        checkWakeUps = { worker ->
            wakeUpChecks += worker
            wakeUps
        },
    )

    private fun defaultResult(method: String, path: String) = when {
        path == "accounts" -> """[{"id":"acc1"}]"""
        path.endsWith("/workers/scripts") && method == "GET" -> "[]"
        path.endsWith("/turn_keys") -> """{"uid":"u","key":"k"}"""
        path.endsWith("/workers/subdomain") -> """{"subdomain":"anna"}"""
        else -> "{}"
    }

    private fun deploy(
        setup: WorkerSetup = setup(),
        steps: MutableList<SetupStep> = mutableListOf(),
        token: String = "  token-123\n",
    ) = runBlocking { setup.deploy(token) { steps += it } }

    @Test
    fun itDeploysWaitsForTheWorkerAndTurnsInternetSyncOn() {
        unansweredHealthChecks = 3
        val steps = mutableListOf<SetupStep>()

        val result = deploy(steps = steps)

        result shouldBe SetupResult.Done("https://mediasync-rendezvous.anna.workers.dev", relay = true)
        steps shouldBe SetupStep.entries
        tokens shouldBe listOf("token-123") // As pasted, without the spaces around it.
        settings.rendezvousUrl.value shouldBe "https://mediasync-rendezvous.anna.workers.dev"
        verify { receiver.refreshInternet() }
    }

    @Test
    fun itChecksThatSendingPhonesCanWakeThisPhoneThroughTheNewWorker() {
        wakeUps = WakeUpStatus(1, "wake_relay_failed: The wake relay answered HTTP 404 (Cloudflare error 1042)")

        val result = deploy()

        result shouldBe SetupResult.Done("https://mediasync-rendezvous.anna.workers.dev", relay = true, wakeUps)
        wakeUpChecks shouldBe listOf("https://mediasync-rendezvous.anna.workers.dev")
    }

    @Test
    fun aWorkerThatDoesntStartIsntCheckedForWakeUps() {
        unansweredHealthChecks = Int.MAX_VALUE

        deploy() shouldBe SetupResult.Failed(SetupFailure.NOT_STARTED)
        wakeUpChecks shouldBe emptyList()
    }

    @Test
    fun aTokenPastedWithLineBreaksOrInvisibleCharactersInItStillWorks() {
        // Copied from a web page: a line break, a zero-width space and a no-break space inside the token.
        val result = deploy(token = "Bearer token-\n12\u200B3\u00A0\r\n")

        result shouldBe SetupResult.Done("https://mediasync-rendezvous.anna.workers.dev", relay = true)
        tokens shouldBe listOf("token-123")
    }

    @Test
    fun aTokenThatCantBeOneIsInvalidWithoutAskingCloudflare() {
        deploy(token = "tökén-123") shouldBe SetupResult.Failed(SetupFailure.INVALID_TOKEN)
        deploy(token = " \n ") shouldBe SetupResult.Failed(SetupFailure.INVALID_TOKEN)
        requests shouldBe emptyList()
    }

    @Test
    fun anUnexpectedErrorIsAFailureNotACrash() {
        val logged = mutableListOf<String>()
        Diagnostics.install { _, message -> logged += message }
        failure = IllegalStateException("Broken answer for Bearer token-123")

        val result = deploy()

        result shouldBe SetupResult.Failed(SetupFailure.REFUSED, "IllegalStateException")
        logged.single() shouldContain "IllegalStateException"
        logged.single() shouldNotContain "token-123" // The token never reaches the diagnostic log.
        Diagnostics.install { _, _ -> }
    }

    @Test
    fun aTokenCloudflareDoesntKnowIsSaidToBeInvalid() {
        answer =
            { _, path ->
                if (path ==
                    "accounts"
                ) {
                    403 to """[{"code":9109,"message":"Invalid access token"}]"""
                } else {
                    null
                }
            }

        deploy() shouldBe SetupResult.Failed(SetupFailure.INVALID_TOKEN, "account: Invalid access token")
        settings.rendezvousUrl.value shouldBe null
    }

    @Test
    fun aTokenThatMayNotEditWorkersSaysSo() {
        answer = { method, path ->
            if (method == "PUT" && path.endsWith("/scripts/mediasync-rendezvous")) {
                403 to """[{"code":10000,"message":"Authentication error"}]"""
            } else {
                null
            }
        }

        (deploy() as SetupResult.Failed).reason shouldBe SetupFailure.NOT_ALLOWED
    }

    @Test
    fun withoutTheRelayKeyItStillSetsUpForDirectConnections() {
        answer = { _, path -> if (path.endsWith("/turn_keys")) 403 to """[{"code":10000,"message":"No"}]""" else null }

        deploy() shouldBe SetupResult.Done("https://mediasync-rendezvous.anna.workers.dev", relay = false)
    }

    @Test
    fun aRelayKeyAnswerWithoutTheKeyIsLoggedAndSetupCarriesOn() {
        val logged = mutableListOf<String>()
        Diagnostics.install { _, message -> logged += message }
        answer =
            { method, path -> if (method == "POST" && path.endsWith("/turn_keys")) 201 to """{"uid":"u"}""" else null }

        val result = deploy()

        result shouldBe SetupResult.Done("https://mediasync-rendezvous.anna.workers.dev", relay = false)
        logged shouldContain "Internet: no relay: TURN key answer without its key (fields: uid)"
        Diagnostics.install { _, _ -> }
    }

    @Test
    fun aWorkerSlowToStartIsKeptForLater() {
        unansweredHealthChecks = Int.MAX_VALUE

        deploy() shouldBe SetupResult.Failed(SetupFailure.NOT_STARTED)
        settings.rendezvousUrl.value shouldBe "https://mediasync-rendezvous.anna.workers.dev"
    }

    @Test
    fun withoutANetworkItSaysCloudflareCantBeReached() {
        val offline = WorkerSetup(
            bundle = { script },
            deployer = { WorkerDeployer(it, { throw IOException("No network") }) },
            settings = settings,
            receiver = receiver,
        )

        deploy(offline) shouldBe SetupResult.Failed(SetupFailure.OFFLINE)
    }

    @Test
    fun theAppCarriesTheWorkerWithItsSettings() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val carried = context.assets.open("rendezvous/worker.json").use { manifest ->
            WorkerScript.of(
                context.assets.open("rendezvous/worker.js").use {
                    it.readBytes()
                },
                manifest.readBytes().decodeToString(),
            )
        }

        WorkerSetup(context, settings, receiver, mockk(relaxed = true)).available() shouldBe true
        carried.durableObjects shouldBe mapOf("PAIRS" to "PairMailbox", "METER" to "UsageMeter")
        carried.migrationTag shouldBe "v1"
        carried.crons shouldBe listOf("*/15 * * * *")
        // Its wake-ups go through the developer's relay, which it must be able to reach even in the relay's account.
        carried.vars["WAKE_RELAY_URL"] shouldStartWith "https://"
        carried.compatibilityFlags shouldContain "global_fetch_strictly_public"
        carried.module.decodeToString() shouldContain "PairMailbox"
    }

    @Test
    fun aBuildWithoutTheWorkerOffersOnlyAnAddress() {
        setup(bundle = null).available() shouldBe false
        setup().available() shouldBe true
    }

    @Test
    fun theTokenPageAsksForWorkersAndTheRelay() {
        val url = URLDecoder.decode(WorkerSetup.TOKEN_URL, Charsets.UTF_8)

        url shouldContain "https://dash.cloudflare.com/profile/api-tokens?permissionGroupKeys="
        url shouldContain """{"key":"workers_scripts","type":"edit"}"""
        url shouldContain "name=MediaSync"
    }
}

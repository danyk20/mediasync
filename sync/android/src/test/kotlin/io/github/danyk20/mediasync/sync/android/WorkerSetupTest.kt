/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import io.github.danyk20.mediasync.core.testing.FakeSettingsRepository
import io.github.danyk20.mediasync.transport.rendezvous.HttpResponse
import io.github.danyk20.mediasync.transport.rendezvous.WorkerDeployer
import io.github.danyk20.mediasync.transport.rendezvous.WorkerScript
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Setting up internet sync with the user's Cloudflare account, against Cloudflare as faked here. */
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

    /** Cloudflare's answer to a path, or `null` for its default. */
    private var answer: (method: String, path: String) -> Pair<Int, String>? = { _, _ -> null }

    /** Health checks until the Worker answers. */
    private var unansweredHealthChecks = 0

    private fun setup(bundle: WorkerScript? = script) = WorkerSetup(
        bundle = { bundle },
        deployer = { token ->
            tokens += token
            WorkerDeployer(token, { request ->
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
    )

    private fun defaultResult(method: String, path: String) = when {
        path == "accounts" -> """[{"id":"acc1"}]"""
        path.endsWith("/workers/scripts") && method == "GET" -> "[]"
        path.endsWith("/turn_keys") -> """{"uid":"u","key":"k"}"""
        path.endsWith("/workers/subdomain") -> """{"subdomain":"anna"}"""
        else -> "{}"
    }

    private fun deploy(setup: WorkerSetup = setup(), steps: MutableList<SetupStep> = mutableListOf()) =
        runBlocking { setup.deploy("  token-123\n") { steps += it } }

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

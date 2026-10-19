/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.rendezvous

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import java.net.URI
import java.security.SecureRandom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/** Deploying the rendezvous Worker into the user's Cloudflare account, against Cloudflare's API as faked here. */
class WorkerDeployerTest {

    private val script = WorkerScript(
        module = "export default {}".encodeToByteArray(),
        compatibilityDate = "2026-10-01",
        vars = mapOf("RELAY_BUDGET_GB" to "950"),
        durableObjects = mapOf("PAIRS" to "PairMailbox", "METER" to "UsageMeter"),
        rateLimits = listOf(WorkerScript.RateLimit("IP_LIMITER", "1001", limit = 120, periodSeconds = 60)),
        crons = listOf("*/15 * * * *"),
        migrationTag = "v1",
    )
    private val sent = mutableListOf<HttpRequest>()

    /** Cloudflare's answers, by method and path: a status and the `result` (or the `errors` when it fails). */
    private val routes = mutableMapOf<String, Pair<Int, String>>(
        "GET accounts" to (200 to """[{"id":"acc1","name":"Anna's account"}]"""),
        "GET accounts/acc1/workers/scripts" to (200 to "[]"),
        "POST accounts/acc1/calls/turn_keys" to (201 to """{"uid":"turn-id","key":"turn-secret","name":"x"}"""),
        "PUT accounts/acc1/workers/scripts/mediasync-rendezvous" to (200 to """{"id":"mediasync-rendezvous"}"""),
        "PUT accounts/acc1/workers/scripts/mediasync-rendezvous/schedules" to (200 to """{"schedules":[]}"""),
        "GET accounts/acc1/workers/subdomain" to (200 to """{"subdomain":"anna"}"""),
        "POST accounts/acc1/workers/scripts/mediasync-rendezvous/subdomain" to (200 to """{"enabled":true}"""),
    )

    private fun deployer() = WorkerDeployer("token-123", { request ->
        sent += request
        val path = request.url.path.removePrefix("/client/v4/")
        val (status, result) = routes["${request.method} $path"] ?: (404 to """[{"code":7003,"message":"No route"}]""")
        val body = if (status in 200..299) {
            """{"success":true,"errors":[],"result":$result}"""
        } else {
            """{"success":false,"errors":$result,"result":null}"""
        }
        HttpResponse(status, emptyMap(), body.encodeToByteArray())
    }, random = SecureRandom())

    private fun upload() = sent.single { it.method == "PUT" && it.url.path.endsWith("/mediasync-rendezvous") }

    /** The metadata part of the multipart upload. */
    private fun metadata(): JsonObject {
        val body = upload().body!!.decodeToString()
        val json = body.substringAfter("Content-Type: application/json\r\n\r\n").substringBefore("\r\n--")
        return Json.parseToJsonElement(json).jsonObject
    }

    private fun bindings() = metadata().getValue("bindings").jsonArray.map { it.jsonObject }

    private fun binding(name: String) = bindings().single { it["name"]?.jsonPrimitive?.content == name }

    @Test
    fun aFirstDeploymentSetsUpTheWorkerWithItsRelayAndAddress() {
        val steps = mutableListOf<WorkerDeployer.Step>()

        val deployment = deployer().deploy(script) { steps += it }

        deployment shouldBe Deployment("acc1", "https://mediasync-rendezvous.anna.workers.dev", relay = true)
        steps shouldBe WorkerDeployer.Step.entries
        sent.all { it.headers["Authorization"] == "Bearer token-123" } shouldBe true
        sent.first().url shouldBe URI("https://api.cloudflare.com/client/v4/accounts?per_page=50")

        val metadata = metadata()
        metadata.getValue("main_module").jsonPrimitive.content shouldBe "worker.js"
        metadata.getValue("compatibility_date").jsonPrimitive.content shouldBe "2026-10-01"
        metadata.getValue("observability").toString() shouldBe """{"enabled":false}"""
        metadata.getValue("migrations").toString() shouldBe
            """{"new_tag":"v1","steps":[{"new_sqlite_classes":["PairMailbox","UsageMeter"]}]}"""
        binding("PAIRS").toString() shouldBe
            """{"type":"durable_object_namespace","name":"PAIRS","class_name":"PairMailbox"}"""
        binding("IP_LIMITER").toString() shouldBe
            """{"type":"ratelimit","name":"IP_LIMITER","namespace_id":"1001","simple":{"limit":120,"period":60}}"""
        binding("RELAY_BUDGET_GB").toString() shouldBe """{"type":"plain_text","name":"RELAY_BUDGET_GB","text":"950"}"""
        binding("CF_ACCOUNT_ID").toString() shouldBe """{"type":"secret_text","name":"CF_ACCOUNT_ID","text":"acc1"}"""
        binding("TURN_KEY_ID").getValue("text").jsonPrimitive.content shouldBe "turn-id"
        binding("TURN_KEY_API_TOKEN").getValue("text").jsonPrimitive.content shouldBe "turn-secret"

        val upload = upload()
        upload.headers["Content-Type"]!! shouldStartWith "multipart/form-data; boundary="
        upload.body!!.decodeToString() shouldContain
            "Content-Disposition: form-data; name=\"worker.js\"; filename=\"worker.js\"\r\n" +
            "Content-Type: application/javascript+module\r\n\r\nexport default {}\r\n"
        sent.single { it.url.path.endsWith("/schedules") }.body!!.decodeToString() shouldBe
            """[{"cron":"*/15 * * * *"}]"""
        sent.last().body!!.decodeToString() shouldBe """{"enabled":true,"previews_enabled":false}"""
    }

    @Test
    fun aRedeploymentKeepsTheSecretsAndMigrations() {
        routes["GET accounts/acc1/workers/scripts"] = 200 to """[{"id":"mediasync-rendezvous","migration_tag":"v1"}]"""
        routes["GET accounts/acc1/workers/scripts/mediasync-rendezvous/secrets"] =
            200 to """[{"name":"CF_ACCOUNT_ID","type":"secret_text"},{"name":"TURN_KEY_ID","type":"secret_text"}]"""

        val deployment = deployer().deploy(script)

        deployment.relay shouldBe true
        sent.map { it.url.path } shouldNotContain "/client/v4/accounts/acc1/calls/turn_keys"
        metadata().containsKey("migrations") shouldBe false
        metadata().getValue("keep_bindings").toString() shouldBe """["secret_text"]"""
        bindings().none { it["type"]?.jsonPrimitive?.content == "secret_text" } shouldBe true
    }

    @Test
    fun aTokenThatMayNotMakeARelayKeyStillDeploysForDirectConnections() {
        routes["POST accounts/acc1/calls/turn_keys"] = 403 to """[{"code":10000,"message":"Authentication error"}]"""

        val deployment = deployer().deploy(script)

        deployment.relay shouldBe false
        bindings().map { it["name"]?.jsonPrimitive?.content } shouldNotContain "TURN_KEY_ID"
    }

    @Test
    fun theRelayKeyIsReadFromTheSecretFieldCloudflareAnswersWith() {
        // As a phone saw it on 2026-10-19 (fields: created, modified, name, secret, uid).
        routes["POST accounts/acc1/calls/turn_keys"] = 201 to
            """{"uid":"turn-id","secret":"turn-secret","name":"mediasync-rendezvous","created":"x","modified":"x"}"""

        val deployment = deployer().deploy(script)

        deployment shouldBe Deployment("acc1", "https://mediasync-rendezvous.anna.workers.dev", relay = true)
        binding("TURN_KEY_ID").getValue("text").jsonPrimitive.content shouldBe "turn-id"
        binding("TURN_KEY_API_TOKEN").getValue("text").jsonPrimitive.content shouldBe "turn-secret"
        sent.none { it.method == "DELETE" } shouldBe true
    }

    @Test
    fun aRelayKeyAnswerWithoutTheKeyDeploysForDirectConnectionsAndRemovesTheKey() {
        // As a phone saw it: Cloudflare made the key but its answer had no "key" (the app used to crash on it).
        routes["POST accounts/acc1/calls/turn_keys"] =
            201 to """{"uid":"turn-id","name":"mediasync-rendezvous","created":"2026-10-19T12:12:00Z","modified":"x"}"""
        routes["DELETE accounts/acc1/calls/turn_keys/turn-id"] = 200 to "null"

        val deployment = deployer().deploy(script)

        deployment.relay shouldBe false
        deployment.relayNote shouldBe "TURN key answer without its key (fields: created, modified, name, uid)"
        bindings().map { it["name"]?.jsonPrimitive?.content } shouldNotContain "TURN_KEY_ID"
        sent.map { "${it.method} ${it.url.path}" } shouldContain
            "DELETE /client/v4/accounts/acc1/calls/turn_keys/turn-id"
    }

    @Test
    fun aRelayKeyAnswerThatIsntAnObjectDeploysForDirectConnections() {
        routes["POST accounts/acc1/calls/turn_keys"] = 201 to "[]"

        deployer().deploy(script).relayNote shouldBe "TURN key answer without its key (fields: none)"
    }

    @Test
    fun aRedeploymentWithABetterTokenAddsTheRelay() {
        routes["GET accounts/acc1/workers/scripts"] = 200 to """[{"id":"mediasync-rendezvous","migration_tag":"v1"}]"""
        routes["GET accounts/acc1/workers/scripts/mediasync-rendezvous/secrets"] =
            200 to """[{"name":"CF_ACCOUNT_ID","type":"secret_text"}]"""

        deployer().deploy(script).relay shouldBe true

        bindings().map { it["name"]?.jsonPrimitive?.content } shouldContain "TURN_KEY_ID"
        bindings().map { it["name"]?.jsonPrimitive?.content } shouldNotContain "CF_ACCOUNT_ID" // Kept.
    }

    @Test
    fun anAccountWithoutAWorkersDevSubdomainGetsOne() {
        routes["GET accounts/acc1/workers/subdomain"] = 404 to """[{"code":10007,"message":"No subdomain"}]"""
        routes["PUT accounts/acc1/workers/subdomain"] = 200 to """{"subdomain":"mediasync-x1y2z3w4"}"""

        val deployment = deployer().deploy(script)

        deployment.url shouldBe "https://mediasync-rendezvous.mediasync-x1y2z3w4.workers.dev"
        val asked = sent.single { it.method == "PUT" && it.url.path.endsWith("/workers/subdomain") }
        asked.body!!.decodeToString() shouldContain """{"subdomain":"mediasync-"""
    }

    @Test
    fun aTokenThatIsntValidSaysSo() {
        routes["GET accounts"] = 403 to """[{"code":9109,"message":"Invalid access token"}]"""

        val failure = shouldThrow<DeployException> { deployer().deploy(script) }

        failure.step shouldBe WorkerDeployer.Step.ACCOUNT
        failure.code shouldBe 9109
        failure.message shouldBe "account: Invalid access token"
    }

    @Test
    fun aTokenWithoutAnAccountSaysSo() {
        routes["GET accounts"] = 200 to "[]"

        shouldThrow<DeployException> { deployer().deploy(script) }.message shouldContain "can't reach any"
    }

    @Test
    fun aRefusedUploadSaysWhy() {
        routes["PUT accounts/acc1/workers/scripts/mediasync-rendezvous"] =
            400 to """[{"code":10021,"message":"Uncaught SyntaxError"}]"""

        val failure = shouldThrow<DeployException> { deployer().deploy(script) }

        failure.step shouldBe WorkerDeployer.Step.UPLOAD
        failure.code shouldBe 10021
    }

    @Test
    fun theBundleStepsManifestDescribesTheScript() {
        val manifest = """
            {"compatibilityDate":"2026-10-01","migrationTag":"v1",
             "durableObjects":{"PAIRS":"PairMailbox","METER":"UsageMeter"},
             "rateLimits":[{"name":"IP_LIMITER","namespaceId":"1001","limit":120,"period":60}],
             "vars":{"RELAY_BUDGET_GB":"950"},"crons":["*/15 * * * *"],"comment":"from wrangler.toml"}
        """.trimIndent()

        val parsed = WorkerScript.of(script.module, manifest)

        listOf(
            parsed.compatibilityDate,
            parsed.migrationTag,
            parsed.durableObjects,
            parsed.rateLimits,
            parsed.vars,
        ) shouldBe
            listOf(script.compatibilityDate, script.migrationTag, script.durableObjects, script.rateLimits, script.vars)
        parsed.crons shouldBe script.crons
    }

    @Test
    fun theWorkerIsLiveOnceItsHealthAnswers() {
        var up = false
        val deployer = WorkerDeployer("t", { request ->
            request.url.toString() shouldBe "https://mediasync-rendezvous.anna.workers.dev/v1/health"
            HttpResponse(if (up) 200 else 404, emptyMap(), ByteArray(0))
        })

        deployer.isLive("https://mediasync-rendezvous.anna.workers.dev") shouldBe false
        up = true
        deployer.isLive("https://mediasync-rendezvous.anna.workers.dev") shouldBe true
    }
}

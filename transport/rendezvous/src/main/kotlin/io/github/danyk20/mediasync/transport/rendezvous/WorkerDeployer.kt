/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.rendezvous

import java.io.IOException
import java.net.URI
import java.security.SecureRandom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okio.Buffer

/**
 * The rendezvous Worker as it's deployed: its bundled ES [module] and the settings of its Wrangler config
 * (infra/rendezvous/wrangler.toml), which a deployment from the phone has to repeat.
 */
class WorkerScript(
    val module: ByteArray,
    val compatibilityDate: String,
    /** Plain-text settings, such as the relay budget. */
    val vars: Map<String, String>,
    /** Durable Object bindings: binding name → class, all SQLite-backed. */
    val durableObjects: Map<String, String>,
    val rateLimits: List<RateLimit>,
    val crons: List<String>,
    /** The last Durable Object migration of the config. */
    val migrationTag: String,
) {
    /** A Workers Rate Limiting binding: [limit] requests per [periodSeconds] (10 or 60). */
    data class RateLimit(val name: String, val namespaceId: String, val limit: Int, val periodSeconds: Int)
}

/** Where a deployment went: the Worker's address, and whether its TURN relay could be set up. */
data class Deployment(val accountId: String, val url: String, val relay: Boolean)

/** Cloudflare refused a step of the deployment; [code] is Cloudflare's error code, if it gave one. */
class DeployException(val step: WorkerDeployer.Step, val code: Int?, message: String) :
    IOException("${step.name.lowercase()}: $message")

/**
 * Deploys the rendezvous Worker into the user's own Cloudflare account, from the receiving phone, with an API token
 * the user created for it (PLAN §8.4, bring your own Cloudflare): each household runs its own Worker, with its own free
 * relay allowance. It uses Cloudflare's REST API as Wrangler does. A Worker already there is updated and keeps its
 * secrets. Calls block; run them off the main thread.
 */
class WorkerDeployer(
    private val token: String,
    private val http: HttpTransport = UrlConnectionTransport(),
    private val api: URI = URI(API),
    private val random: SecureRandom = SecureRandom(),
) {
    /** The steps of a deployment, as the screen shows them. */
    enum class Step { ACCOUNT, RELAY, UPLOAD, SCHEDULE, ADDRESS }

    /** Deploys [script] as [name] and returns where it is. Calls [onStep] as each step starts. */
    fun deploy(script: WorkerScript, name: String = NAME, onStep: (Step) -> Unit = {}): Deployment {
        onStep(Step.ACCOUNT)
        val account = account()
        val deployedTag = deployedMigrationTag(account, name)
        // Kept from an earlier deployment: only what's missing is set.
        val kept = if (deployedTag == null) emptySet() else secretNames(account, name)
        onStep(Step.RELAY)
        val relayKey = if (TURN_KEY_ID in kept) null else relayKey(account)
        onStep(Step.UPLOAD)
        val secrets = buildMap {
            if (ACCOUNT_ID !in kept) put(ACCOUNT_ID, account)
            relayKey?.let { (id, key) ->
                put(TURN_KEY_ID, id)
                put(TURN_KEY, key)
            }
        }
        upload(account, name, script, secrets, migrate = deployedTag != script.migrationTag)
        onStep(Step.SCHEDULE)
        val crons = buildJsonArray { script.crons.forEach { cron -> addJsonObject { put("cron", cron) } } }
        call(Step.SCHEDULE, "PUT", "accounts/$account/workers/scripts/$name/schedules", crons)
        onStep(Step.ADDRESS)
        val subdomain = subdomain(account)
        call(
            Step.ADDRESS,
            "POST",
            "accounts/$account/workers/scripts/$name/subdomain",
            buildJsonObject {
                put("enabled", true)
                put("previews_enabled", false)
            },
        )
        return Deployment(
            account,
            "https://$name.$subdomain.workers.dev",
            relay =
                TURN_KEY_ID in kept || relayKey != null,
        )
    }

    /** Whether the Worker at [url] answers yet: a new workers.dev address takes a few minutes to be found. */
    fun isLive(url: String): Boolean = try {
        http.execute(HttpRequest("GET", URI("$url/v1/health"), emptyMap(), null)).status == HTTP_OK
    } catch (_: IOException) {
        false
    }

    /** The account the token was made for; the first, if it can reach several. */
    private fun account(): String {
        val accounts = call(Step.ACCOUNT, "GET", "accounts?per_page=50").jsonArray
        val first = accounts.firstOrNull()?.jsonObject ?: throw DeployException(
            Step.ACCOUNT,
            null,
            "The token can't reach any Cloudflare account",
        )
        return first.getValue("id").jsonPrimitive.content
    }

    /** The migration tag of the Worker deployed as [name]; `null` if there's none yet. */
    private fun deployedMigrationTag(account: String, name: String): String? {
        val scripts = call(Step.ACCOUNT, "GET", "accounts/$account/workers/scripts").jsonArray.map { it.jsonObject }
        val script = scripts.firstOrNull { it["id"]?.jsonPrimitive?.content == name } ?: return null
        return script["migration_tag"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content ?: NO_TAG
    }

    /** A TURN key for the relay; `null` if the token may not make one: the Worker then connects directly only. */
    private fun relayKey(account: String): Pair<String, String>? = try {
        val key = call(Step.RELAY, "POST", "accounts/$account/calls/turn_keys", buildJsonObject { put("name", NAME) })
            .jsonObject
        key.getValue("uid").jsonPrimitive.content to key.getValue("key").jsonPrimitive.content
    } catch (e: DeployException) {
        if (e.code == null || e.code !in PERMISSION_ERRORS) throw e
        null
    }

    /** The names of the secrets the Worker deployed as [name] has. */
    private fun secretNames(account: String, name: String): Set<String> =
        call(Step.ACCOUNT, "GET", "accounts/$account/workers/scripts/$name/secrets").jsonArray
            .mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }
            .toSet()

    private fun upload(
        account: String,
        name: String,
        script: WorkerScript,
        secrets: Map<String, String>,
        migrate: Boolean,
    ) {
        val metadata = buildJsonObject {
            put("main_module", MODULE)
            put("compatibility_date", script.compatibilityDate)
            putJsonObject("observability") { put("enabled", false) } // No request logs (PLAN §8.4).
            putJsonArray("bindings") { bindings(script, secrets).forEach(::add) }
            // A redeployment keeps the secrets set at the first.
            putJsonArray("keep_bindings") { add("secret_text") }
            if (migrate) {
                putJsonObject("migrations") {
                    put("new_tag", script.migrationTag)
                    putJsonArray("steps") {
                        addJsonObject {
                            putJsonArray("new_sqlite_classes") { script.durableObjects.values.forEach(::add) }
                        }
                    }
                }
            }
        }
        val boundary = "mediasync-${random.nextLong().toULong()}"
        val body = Buffer().apply {
            part(boundary, "metadata", null, "application/json", JSON.encodeToString(metadata).encodeToByteArray())
            part(boundary, MODULE, MODULE, "application/javascript+module", script.module)
            writeUtf8("--$boundary--\r\n")
        }.readByteArray()
        send(
            Step.UPLOAD,
            "PUT",
            "accounts/$account/workers/scripts/$name",
            body,
            "multipart/form-data; boundary=$boundary",
        )
    }

    private fun bindings(script: WorkerScript, secrets: Map<String, String>): List<JsonObject> = buildList {
        script.durableObjects.forEach { (binding, className) ->
            add(
                buildJsonObject {
                    put("type", "durable_object_namespace")
                    put("name", binding)
                    put("class_name", className)
                },
            )
        }
        script.rateLimits.forEach { limit ->
            add(
                buildJsonObject {
                    put("type", "ratelimit")
                    put("name", limit.name)
                    put("namespace_id", limit.namespaceId)
                    putJsonObject("simple") {
                        put("limit", limit.limit)
                        put("period", limit.periodSeconds)
                    }
                },
            )
        }
        script.vars.forEach { (name, value) -> add(text("plain_text", name, value)) }
        secrets.forEach { (name, value) -> add(text("secret_text", name, value)) }
    }

    private fun text(type: String, name: String, value: String) = buildJsonObject {
        put("type", type)
        put("name", name)
        put("text", value)
    }

    /** The account's workers.dev subdomain; one is made for an account that has none yet. */
    private fun subdomain(account: String): String {
        try {
            val existing = call(Step.ADDRESS, "GET", "accounts/$account/workers/subdomain").jsonObject
            return existing.getValue("subdomain").jsonPrimitive.content
        } catch (e: DeployException) {
            if (e.code != NO_SUBDOMAIN) throw e
        }
        repeat(SUBDOMAIN_ATTEMPTS - 1) {
            try {
                return createSubdomain(account)
            } catch (e: DeployException) {
                if (e.code != SUBDOMAIN_TAKEN) throw e
            }
        }
        return createSubdomain(account)
    }

    private fun createSubdomain(account: String): String {
        val name = "mediasync-" + (1..SUBDOMAIN_SUFFIX).map { SUBDOMAIN_CHARS[random.nextInt(SUBDOMAIN_CHARS.length)] }
            .joinToString("")
        val made = call(
            Step.ADDRESS,
            "PUT",
            "accounts/$account/workers/subdomain",
            buildJsonObject {
                put("subdomain", name)
            },
        ).jsonObject
        return made["subdomain"]?.jsonPrimitive?.content ?: name
    }

    /** One API call with a JSON body (or none); its `result`, or a [DeployException] with Cloudflare's first error. */
    private fun call(step: Step, method: String, path: String, json: JsonElement? = null): JsonElement =
        send(step, method, path, json?.let { JSON.encodeToString(JsonElement.serializer(), it).encodeToByteArray() })

    private fun send(
        step: Step,
        method: String,
        path: String,
        body: ByteArray?,
        contentType: String = "application/json",
    ): JsonElement {
        val headers = buildMap {
            put("Authorization", "Bearer $token")
            if (body != null) put("Content-Type", contentType)
        }
        val response = http.execute(HttpRequest(method, api.resolve(path), headers, body))
        val parsed = runCatching { JSON.parseToJsonElement(response.body.decodeToString()).jsonObject }.getOrNull()
        val success = parsed?.get("success")?.jsonPrimitive?.content == "true"
        if (response.status !in SUCCESS || !success) {
            val error = (parsed?.get("errors") as? JsonArray)?.firstOrNull()?.jsonObject
            throw DeployException(
                step,
                error?.get("code")?.jsonPrimitive?.int,
                error?.get("message")?.jsonPrimitive?.content ?: "Cloudflare answered ${response.status}",
            )
        }
        return parsed["result"] ?: JsonNull
    }

    private fun Buffer.part(boundary: String, name: String, filename: String?, type: String, content: ByteArray) {
        writeUtf8("--$boundary\r\n")
        writeUtf8("Content-Disposition: form-data; name=\"$name\"")
        filename?.let { writeUtf8("; filename=\"$it\"") }
        writeUtf8("\r\nContent-Type: $type\r\n\r\n")
        write(content)
        writeUtf8("\r\n")
    }

    companion object {
        /** The Worker's name in the user's account; its address is `https://<name>.<subdomain>.workers.dev`. */
        const val NAME = "mediasync-rendezvous"
        const val API = "https://api.cloudflare.com/client/v4/"

        private const val MODULE = "worker.js"
        private const val ACCOUNT_ID = "CF_ACCOUNT_ID"
        private const val TURN_KEY_ID = "TURN_KEY_ID"
        private const val TURN_KEY = "TURN_KEY_API_TOKEN"
        private const val HTTP_OK = 200
        private val SUCCESS = 200..299

        /** A Worker deployed before migrations had a tag: treated as needing them. */
        private const val NO_TAG = ""

        /** Authentication error / missing permission (10000), forbidden (10002, 9109). */
        private val PERMISSION_ERRORS = setOf(10_000, 10_002, 9_109)
        private const val NO_SUBDOMAIN = 10_007
        private const val SUBDOMAIN_TAKEN = 10_031
        private const val SUBDOMAIN_ATTEMPTS = 3
        private const val SUBDOMAIN_SUFFIX = 8
        private const val SUBDOMAIN_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}

/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.rendezvous

import io.github.danyk20.mediasync.core.model.DeviceId
import java.io.IOException
import java.net.URI
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The rendezvous Worker's API (docs/adr/0009-rendezvous-device-authentication.md §3), as a phone calls it: every
 * request signed by [signer]. Calls block; run them off the main thread.
 *
 * A refusal throws [RendezvousException] with the Worker's error code, which the app words its messages from. A clock
 * the Worker finds off is corrected once, and the request retried.
 */
class RendezvousClient(
    private val baseUrl: URI,
    private val signer: RendezvousSigner,
    private val http: HttpTransport = UrlConnectionTransport(),
) {
    /**
     * As the consumer: registers the pair with [producer] at the Worker, or updates it. [pushToken] is this phone's FCM
     * token for wake-ups: [PushToken.Keep] leaves the registered one, [PushToken.Remove] forgets it.
     */
    fun registerPair(producer: DeviceId, pushToken: PushToken = PushToken.Keep): PairRegistration {
        val body = buildJsonObject {
            put("producer", producer.hex)
            when (pushToken) {
                PushToken.Keep -> Unit
                PushToken.Remove -> put("fcmToken", JsonNull)
                is PushToken.Of -> put("fcmToken", pushToken.token)
            }
        }
        return call("PUT", "/v1/pairs/${pairId(signer.deviceId, producer)}", body, PairRegistration.serializer())
    }

    fun pairStatus(pairId: String): PairStatus = call("GET", "/v1/pairs/$pairId", null, PairStatus.serializer())

    /** Either phone: forgets the pair at the Worker, with all it holds for it. */
    fun unpair(pairId: String) {
        call("DELETE", "/v1/pairs/$pairId", null, JsonObject.serializer())
    }

    /** STUN always; TURN while the relay budget allows (PLAN §8.4, §8.5). */
    fun iceServers(pairId: String): IceServers =
        call("POST", "/v1/pairs/$pairId/ice-servers", JsonObject(emptyMap()), IceServers.serializer())

    /** As the producer: wakes the consumer through FCM (PLAN §10.4). */
    fun wake(pairId: String, payload: String? = null) {
        val body = buildJsonObject { payload?.let { put("payload", it) } }
        call("POST", "/v1/pairs/$pairId/wake", body, JsonObject.serializer())
    }

    /** During a relayed session: the cumulative bytes of [session] (16 random bytes, base64url) so far. */
    fun reportRelayUsage(pairId: String, session: String, relayedBytes: Long): RelayMeter {
        val body = buildJsonObject {
            put("session", session)
            put("relayedBytes", relayedBytes)
        }
        return call("POST", "/v1/pairs/$pairId/relay-usage", body, RelayMeter.serializer())
    }

    fun relayUsage(pairId: String): RelayMeter =
        call("GET", "/v1/pairs/$pairId/relay-usage", null, RelayMeter.serializer())

    private fun <T> call(method: String, path: String, body: JsonElement?, result: KSerializer<T>): T {
        val bytes = body?.let { JSON.encodeToString(JsonElement.serializer(), it).encodeToByteArray() }
        var response = send(method, path, bytes)
        val serverTime = response.headers[RendezvousSigner.SERVER_TIME.lowercase()]?.toLongOrNull()
        if (response.status == UNAUTHORIZED && errorCode(response) == CLOCK_SKEW && serverTime != null) {
            signer.adjustClock(serverTime)
            response = send(method, path, bytes)
        }
        if (response.status !in SUCCESS) throw failure(response)
        return JSON.decodeFromString(result, response.body.decodeToString())
    }

    private fun send(method: String, path: String, body: ByteArray?): HttpResponse {
        val url = baseUrl.resolve(path)
        val headers = signer.headers(method, url, body ?: ByteArray(0)) +
            (if (body != null) mapOf("Content-Type" to "application/json") else emptyMap())
        return http.execute(HttpRequest(method, url, headers, body))
    }

    private fun failure(response: HttpResponse): RendezvousException {
        val error = runCatching {
            JSON.parseToJsonElement(response.body.decodeToString()).jsonObject["error"]?.jsonObject
        }.getOrNull()
        return RendezvousException(
            code = error?.get("code")?.jsonPrimitive?.content ?: "http_${response.status}",
            status = response.status,
            message =
                error?.get("message")?.jsonPrimitive?.content ?: "The rendezvous Worker answered ${response.status}",
            retryAfterSeconds = response.headers["retry-after"]?.toLongOrNull(),
        )
    }

    private fun errorCode(response: HttpResponse): String? = runCatching {
        JSON.parseToJsonElement(response.body.decodeToString()).jsonObject["error"]?.jsonObject?.get("code")
            ?.let { (it as JsonPrimitive).content }
    }.getOrNull()

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        val SUCCESS = 200..299
        const val UNAUTHORIZED = 401
        const val CLOCK_SKEW = "auth_clock_skew"
    }
}

/** What happens to the consumer's FCM token when it registers a pair. */
sealed interface PushToken {
    data object Keep : PushToken

    data object Remove : PushToken

    data class Of(val token: String) : PushToken {
        /** Never the token: this may end up in logs. */
        override fun toString() = "PushToken.Of(…)"
    }
}

/** The Worker refused: [code] as in ADR 0009, e.g. `pair_not_found`, `rate_limited`, `wake_throttled`. */
class RendezvousException(
    val code: String,
    val status: Int,
    message: String,
    /** From `Retry-After`, for `rate_limited` and `wake_throttled`. */
    val retryAfterSeconds: Long? = null,
) : IOException("$code: $message")

@Serializable
data class PairRegistration(val pairId: String, val created: Boolean)

@Serializable
data class PairStatus(val pairId: String, val role: String, val pushTokenRegistered: Boolean, val peerOnline: Boolean)

@Serializable
data class IceServers(val iceServers: List<IceServer>, val ttlSeconds: Int, val relay: RelayAvailability)

@Serializable
data class IceServer(val urls: List<String>, val username: String? = null, val credential: String? = null) {
    /** Never the credential: this may end up in logs. */
    override fun toString() = "IceServer($urls)"
}

/** Whether TURN is offered; when not, why (`budget_exhausted`, `not_configured`, `unavailable`) and until when. */
@Serializable
data class RelayAvailability(val available: Boolean, val reason: String? = null, val resumesAt: String? = null)

/** The relay usage meter (PLAN §8.5); times are ISO 8601. */
@Serializable
data class RelayMeter(
    val period: Period,
    val usedBytes: Long,
    val authoritativeBytes: Long,
    val authoritativeAt: String? = null,
    val liveBytes: Long,
    val limitBytes: Long,
    val freeAllowanceBytes: Long,
    val relayAvailable: Boolean,
    @SerialName("pair") val pairShare: PairShare? = null,
) {
    @Serializable
    data class Period(val start: String, val end: String)

    @Serializable
    data class PairShare(val approxBytes: Long)
}

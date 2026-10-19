/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.rendezvous

import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Cloudflare didn't give a token for the sign-in: [error] is OAuth's error code (RFC 6749 §5.2), if it gave one. */
class SignInException(val error: String?, message: String) : IOException(message)

/**
 * Signing in with Cloudflare, for a token to deploy the Worker with (PLAN §8.4): OAuth 2.0's authorization code flow
 * with PKCE (RFC 7636), as the public client [clientId] the developer registered, which has no secret. The browser
 * comes back to [redirectUri], an https App Link. The access token is used for one deployment, like a pasted API
 * token, and kept nowhere, so no refresh token is asked for. [exchange] blocks; run it off the main thread.
 */
class CloudflareOAuth(
    private val clientId: String,
    private val redirectUri: String,
    private val http: HttpTransport = UrlConnectionTransport(),
    private val authorizeEndpoint: URI = URI(AUTHORIZE_URL),
    private val tokenEndpoint: URI = URI(TOKEN_URL),
    private val random: SecureRandom = SecureRandom(),
) {
    /** What a sign-in keeps while the browser is open: its PKCE verifier, and the state the answer must carry. */
    data class Pending(val verifier: String, val state: String)

    /** What the browser brought back. */
    sealed interface Answer {
        /** Cloudflare's authorization [code], to exchange for a token. */
        data class Code(val code: String) : Answer

        /** The user didn't allow it. */
        data object Denied : Answer

        /** Not the answer to this sign-in: its state differs, or it has none. */
        data object WrongState : Answer

        /** Cloudflare refused for another reason: its OAuth [error] and description. */
        data class Refused(val error: String, val description: String?) : Answer
    }

    /** A new sign-in: a fresh verifier and state. */
    fun begin(): Pending = Pending(verifier = randomString(VERIFIER_BYTES), state = randomString(STATE_BYTES))

    /** Cloudflare's sign-in and consent page for [pending], to open in the browser. */
    fun authorizeUrl(pending: Pending): String {
        val query = listOf(
            "response_type" to "code",
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "scope" to SCOPES.joinToString(" "),
            "state" to pending.state,
            "code_challenge" to challenge(pending.verifier),
            "code_challenge_method" to "S256",
        ).joinToString("&") { (name, value) -> "$name=${encode(value)}" }
        return "$authorizeEndpoint?$query"
    }

    /** Whether [link] goes to this client's redirect address: only those are answers to a sign-in. */
    fun isRedirect(link: String): Boolean {
        val uri = parse(link) ?: return false
        val expected = parse(redirectUri) ?: return false
        return uri.scheme.equals(expected.scheme, ignoreCase = true) &&
            uri.host.equals(expected.host, ignoreCase = true) &&
            uri.port == expected.port &&
            uri.rawPath.orEmpty().trimEnd('/') == expected.rawPath.orEmpty().trimEnd('/')
    }

    /** What [redirect], the address the browser came back to, says about [pending]. */
    fun answer(redirect: String, pending: Pending): Answer {
        val parameters = parse(redirect)?.takeIf { isRedirect(redirect) }?.rawQuery.orEmpty().split('&')
            .filter { '=' in it }
            .mapNotNull { pair -> decode(pair.substringAfter('='))?.let { pair.substringBefore('=') to it } }
            .toMap()
        val error = parameters["error"]
        val code = parameters["code"]
        return when {
            // An answer without this sign-in's state could be anyone's: nothing else in it is trusted.
            parameters["state"] != pending.state -> Answer.WrongState

            error == ACCESS_DENIED -> Answer.Denied

            error != null -> Answer.Refused(error, parameters["error_description"])

            code.isNullOrEmpty() -> Answer.Refused(INVALID_REQUEST, "No authorization code")

            else -> Answer.Code(code)
        }
    }

    /**
     * Exchanges [code] for an access token, with [pending]'s verifier. Throws [SignInException] when Cloudflare
     * refuses, and [IOException] when it can't be reached.
     */
    fun exchange(code: String, pending: Pending): String {
        val form = listOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "client_id" to clientId,
            "code_verifier" to pending.verifier,
        ).joinToString("&") { (name, value) -> "$name=${encode(value)}" }
        val response = http.execute(
            HttpRequest(
                "POST",
                tokenEndpoint,
                mapOf("Content-Type" to "application/x-www-form-urlencoded", "Accept" to "application/json"),
                form.encodeToByteArray(),
            ),
        )
        val json = runCatching { JSON.parseToJsonElement(response.body.decodeToString()).jsonObject }.getOrNull()
        val token = json?.text("access_token")
        if (response.status !in SUCCESS || token.isNullOrEmpty()) {
            val error = json?.text("error")
            val description = json?.text("error_description") ?: error
            throw SignInException(error, description ?: "Cloudflare answered ${response.status}")
        }
        return token
    }

    private fun randomString(bytes: Int): String = base64Url(ByteArray(bytes).also(random::nextBytes))

    private fun parse(text: String): URI? = try {
        URI(text)
    } catch (_: URISyntaxException) {
        null
    }

    private fun JsonObject.text(name: String): String? = runCatching { get(name)?.jsonPrimitive?.content }.getOrNull()

    companion object {
        /** Cloudflare's endpoints (developers.cloudflare.com/fundamentals/oauth/integrate-with-cloudflare). */
        const val AUTHORIZE_URL = "https://dash.cloudflare.com/oauth2/auth"
        const val TOKEN_URL = "https://dash.cloudflare.com/oauth2/token"

        /**
         * What MediaSync asks for (Cloudflare's `GET /oauth/scopes`): the user's accounts, editing Workers, and Calls
         * for the relay's TURN key. Without the last (it's optional on the client), the Worker runs without the relay.
         */
        val SCOPES = listOf("account-settings.read", "workers-scripts.write", "calls.write")

        /** RFC 7636's S256 code challenge for [verifier]. */
        fun challenge(verifier: String): String =
            base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        /** 32 bytes: a 43-character verifier, as RFC 7636 recommends. */
        private const val VERIFIER_BYTES = 32
        private const val STATE_BYTES = 16
        private const val ACCESS_DENIED = "access_denied"
        private const val INVALID_REQUEST = "invalid_request"
        private val SUCCESS = 200..299
        private val JSON = Json { ignoreUnknownKeys = true }

        private fun base64Url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        /** Form and query encoding, with spaces as `%20`. */
        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        /** `null` for a broken escape. */
        private fun decode(value: String): String? = try {
            URLDecoder.decode(value, "UTF-8")
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

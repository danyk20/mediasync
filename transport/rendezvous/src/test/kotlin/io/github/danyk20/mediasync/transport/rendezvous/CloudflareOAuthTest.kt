/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.transport.rendezvous

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldNotBeInstanceOf
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.security.SecureRandom
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Signing in with Cloudflare: PKCE, the sign-in page's address, the browser's answer, and the token exchange. */
class CloudflareOAuthTest {

    private val server = MockWebServer().apply { start() }
    private val oauth = CloudflareOAuth(
        clientId = "client-1",
        redirectUri = REDIRECT,
        tokenEndpoint = URI(server.url("/oauth2/token").toString()),
    )
    private val pending = CloudflareOAuth.Pending(verifier = RFC_VERIFIER, state = "state-1")

    @AfterEach
    fun tearDown() = server.close()

    @Test
    fun theChallengeIsRfc7636sS256() {
        // RFC 7636, appendix B.
        CloudflareOAuth.challenge(RFC_VERIFIER) shouldBe "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
    }

    @Test
    fun theVerifierIs32RandomBytesInBase64Url() {
        // The octets of RFC 7636, appendix B, give its verifier.
        val rfcOctets = intArrayOf(
            116, 24, 223, 180, 151, 153, 224, 37, 79, 250, 96, 125, 216, 173, 187, 186,
            22, 212, 37, 77, 105, 214, 191, 240, 91, 88, 5, 88, 83, 132, 141, 121,
        ).map { it.toByte() }
        val random = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) {
                bytes.indices.forEach { bytes[it] = rfcOctets[it % rfcOctets.size] }
            }
        }

        CloudflareOAuth("client-1", REDIRECT, random = random).begin().verifier shouldBe RFC_VERIFIER
    }

    @Test
    fun eachSignInHasItsOwnVerifierAndState() {
        val first = oauth.begin()
        val second = oauth.begin()

        first.verifier shouldMatch "[A-Za-z0-9_-]{43}"
        first.state shouldMatch "[A-Za-z0-9_-]{22}"
        second.verifier shouldNotBe first.verifier
        second.state shouldNotBe first.state
    }

    @Test
    fun theSignInPageAsksForTheScopesWithTheChallenge() {
        val url = oauth.authorizeUrl(pending)

        url shouldStartWith "https://dash.cloudflare.com/oauth2/auth?"
        url.substringAfter('?').contains('+') shouldBe false // Spaces as %20.
        query(url) shouldBe mapOf(
            "response_type" to "code",
            "client_id" to "client-1",
            "redirect_uri" to REDIRECT,
            "scope" to "account-settings.read workers-scripts.write calls.write",
            "state" to "state-1",
            "code_challenge" to "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            "code_challenge_method" to "S256",
        )
    }

    @Test
    fun onlyTheRedirectAddressIsAnAnswer() {
        oauth.isRedirect("$REDIRECT?code=abc&state=state-1") shouldBe true
        oauth.isRedirect("HTTPS://MediaSync.Example/oauth/cloudflare/?code=abc") shouldBe true
        oauth.isRedirect("https://mediasync.example/oauth/other?code=abc") shouldBe false
        oauth.isRedirect("https://evil.example/oauth/cloudflare?code=abc") shouldBe false
        oauth.isRedirect("http://mediasync.example/oauth/cloudflare?code=abc") shouldBe false
        oauth.isRedirect("not a link at all") shouldBe false
    }

    @Test
    fun theAnswerCarriesTheCode() {
        oauth.answer("$REDIRECT?code=a%2Fb%20c&state=state-1", pending) shouldBe CloudflareOAuth.Answer.Code("a/b c")
    }

    @Test
    fun anAnswerWithAnotherStateIsntTrusted() {
        oauth.answer("$REDIRECT?code=abc&state=state-2", pending) shouldBe CloudflareOAuth.Answer.WrongState
        oauth.answer("$REDIRECT?code=abc", pending) shouldBe CloudflareOAuth.Answer.WrongState
        oauth.answer("$REDIRECT?error=access_denied&state=other", pending) shouldBe CloudflareOAuth.Answer.WrongState
        oauth.answer("https://evil.example/oauth/cloudflare?code=abc&state=state-1", pending) shouldBe
            CloudflareOAuth.Answer.WrongState
        oauth.answer("$REDIRECT?code=abc&state=state-%ZZ", pending) shouldBe CloudflareOAuth.Answer.WrongState
    }

    @Test
    fun aDeniedSignInIsCancelled() {
        oauth.answer("$REDIRECT?error=access_denied&state=state-1", pending) shouldBe CloudflareOAuth.Answer.Denied
    }

    @Test
    fun otherErrorsSayWhy() {
        oauth.answer(
            "$REDIRECT?error=invalid_scope&error_description=Unknown%20scope%20calls.write&state=state-1",
            pending,
        ) shouldBe CloudflareOAuth.Answer.Refused("invalid_scope", "Unknown scope calls.write")
        oauth.answer("$REDIRECT?state=state-1", pending) shouldBe
            CloudflareOAuth.Answer.Refused("invalid_request", "No authorization code")
    }

    @Test
    fun theCodeIsExchangedForAnAccessTokenWithTheVerifier() {
        server.enqueue(
            json(200, """{"access_token":"cf-oauth-token","token_type":"bearer","expires_in":3600,"scope":"x"}"""),
        )

        oauth.exchange("code-1", pending) shouldBe "cf-oauth-token"

        val request = server.takeRequest()
        request.method shouldBe "POST"
        request.url.encodedPath shouldBe "/oauth2/token"
        request.headers["Content-Type"] shouldBe "application/x-www-form-urlencoded"
        request.headers["Authorization"] shouldBe null // A public client: no secret.
        query("?" + request.body!!.utf8()) shouldBe mapOf(
            "grant_type" to "authorization_code",
            "code" to "code-1",
            "redirect_uri" to REDIRECT,
            "client_id" to "client-1",
            "code_verifier" to RFC_VERIFIER,
        )
    }

    @Test
    fun aRefusedExchangeSaysWhy() {
        server.enqueue(json(400, """{"error":"invalid_grant","error_description":"The code has expired"}"""))

        val refusal = shouldThrow<SignInException> { oauth.exchange("code-1", pending) }

        refusal.error shouldBe "invalid_grant"
        refusal.message shouldBe "The code has expired"
    }

    @Test
    fun anAnswerWithoutATokenIsARefusal() {
        server.enqueue(json(200, """{"token_type":"bearer"}"""))

        shouldThrow<SignInException> { oauth.exchange("code-1", pending) }.error shouldBe null
    }

    @Test
    fun aCutConnectionIsNotARefusal() {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build())

        val failure = shouldThrow<IOException> { oauth.exchange("code-1", pending) }

        failure.shouldNotBeInstanceOf<SignInException>()
    }

    private fun json(status: Int, body: String) =
        MockResponse.Builder().code(status).addHeader("Content-Type", "application/json").body(body).build()

    private fun query(url: String): Map<String, String> = url.substringAfter('?').split('&').associate {
        it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

    private companion object {
        const val REDIRECT = "https://mediasync.example/oauth/cloudflare"
        const val RFC_VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    }
}

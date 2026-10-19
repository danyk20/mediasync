/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.feature.consumer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import io.github.danyk20.mediasync.core.diagnostics.Area
import io.github.danyk20.mediasync.core.diagnostics.Diagnostics
import io.github.danyk20.mediasync.sync.android.SetupFailure
import io.github.danyk20.mediasync.transport.rendezvous.CloudflareOAuth
import io.github.danyk20.mediasync.transport.rendezvous.SignInException
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What signing in with Cloudflare gave: a token to set up internet sync with, or why there's none. */
sealed interface SignInResult {
    data class Token(val token: String) : SignInResult

    data class Failed(val reason: SetupFailure, val detail: String? = null) : SignInResult
}

/**
 * Signing in with Cloudflare for the token that sets up internet sync (docs/PLAN.md §8.4), instead of pasting one.
 * Only a build with MediaSync's OAuth client has it (`CLOUDFLARE_OAUTH_CLIENT_ID` and `CLOUDFLARE_OAUTH_REDIRECT_URI`,
 * infra/rendezvous/README.md). The browser comes back through [CloudflareRedirectActivity], which hands the address
 * here for the screen to [take].
 */
@Singleton
class CloudflareSignIn internal constructor(private val oauth: CloudflareOAuth?) {

    @Inject
    constructor() : this(oauthOf(BuildConfig.CLOUDFLARE_OAUTH_CLIENT_ID, BuildConfig.CLOUDFLARE_OAUTH_REDIRECT_URI))

    private val redirect = AtomicReference<String?>(null)

    /** Whether this build can sign in with Cloudflare; without it, a pasted token sets up internet sync. */
    val available: Boolean get() = oauth != null

    /** A new sign-in, to keep until the browser comes back; `null` without the OAuth client. */
    fun begin(): CloudflareOAuth.Pending? = oauth?.begin()

    /** Cloudflare's sign-in page for [pending], to open in the browser. */
    fun authorizeUrl(pending: CloudflareOAuth.Pending): String? = oauth?.authorizeUrl(pending)

    /** The browser came back to [link]. Kept only if it's this app's redirect address. */
    fun receive(link: String) {
        if (oauth?.isRedirect(link) == true) redirect.set(link)
    }

    /** Where the browser came back to since the last call, if it did. */
    fun take(): String? = redirect.getAndSet(null)

    /** The access token that [redirect] gives for [pending], or why there's none. Never logs the code or token. */
    suspend fun token(redirect: String, pending: CloudflareOAuth.Pending): SignInResult = withContext(Dispatchers.IO) {
        val oauth = oauth ?: return@withContext SignInResult.Failed(SetupFailure.SIGN_IN_REFUSED)
        when (val answer = oauth.answer(redirect, pending)) {
            is CloudflareOAuth.Answer.Code -> try {
                SignInResult.Token(oauth.exchange(answer.code, pending))
            } catch (e: SignInException) {
                Diagnostics.log(Area.RECEIVE, "Internet: Cloudflare gave no token for the sign-in", e)
                SignInResult.Failed(SetupFailure.SIGN_IN_REFUSED, e.message)
            } catch (e: IOException) {
                Diagnostics.log(Area.RECEIVE, "Internet: Cloudflare couldn't be reached to sign in", e)
                SignInResult.Failed(SetupFailure.OFFLINE)
            }

            CloudflareOAuth.Answer.Denied -> SignInResult.Failed(SetupFailure.SIGN_IN_CANCELLED)

            CloudflareOAuth.Answer.WrongState -> {
                Diagnostics.log(Area.RECEIVE, "Internet: a sign-in answer with another state was ignored")
                SignInResult.Failed(SetupFailure.SIGN_IN_MISMATCH)
            }

            is CloudflareOAuth.Answer.Refused -> {
                Diagnostics.log(Area.RECEIVE, "Internet: Cloudflare refused the sign-in (${answer.error})")
                SignInResult.Failed(SetupFailure.SIGN_IN_REFUSED, answer.description ?: answer.error)
            }
        }
    }

    private companion object {
        /** The OAuth client this build was made with; none when either setting is empty. */
        fun oauthOf(clientId: String, redirectUri: String): CloudflareOAuth? =
            if (clientId.isBlank() || redirectUri.isBlank()) null else CloudflareOAuth(clientId, redirectUri)
    }
}

/**
 * Where the browser comes back to after signing in with Cloudflare: the redirect address, an https App Link that only
 * a build with the OAuth client declares. It hands the address over and returns to the app, which closes the
 * browser tab above it.
 */
@AndroidEntryPoint
class CloudflareRedirectActivity : ComponentActivity() {

    @Inject
    lateinit var signIn: CloudflareSignIn

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.dataString?.let(signIn::receive)
        packageManager.getLaunchIntentForPackage(packageName)?.let { app ->
            startActivity(app.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        finish()
    }
}

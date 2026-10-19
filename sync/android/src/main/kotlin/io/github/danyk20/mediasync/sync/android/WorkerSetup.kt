/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.sync.android

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.danyk20.mediasync.core.datastore.SettingsRepository
import io.github.danyk20.mediasync.core.diagnostics.Area
import io.github.danyk20.mediasync.core.diagnostics.Diagnostics
import io.github.danyk20.mediasync.transport.rendezvous.DeployException
import io.github.danyk20.mediasync.transport.rendezvous.WorkerDeployer
import io.github.danyk20.mediasync.transport.rendezvous.WorkerScript
import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Where setting up internet sync is (docs/PLAN.md §8.4), as the screen shows it. */
enum class SetupStep { ACCOUNT, RELAY, UPLOAD, SCHEDULE, ADDRESS, STARTING }

/** Why setting up internet sync didn't work. */
enum class SetupFailure {
    /** Cloudflare doesn't accept the token. */
    INVALID_TOKEN,

    /** The token may not edit Workers. */
    NOT_ALLOWED,

    /** Cloudflare can't be reached. */
    OFFLINE,

    /** The Worker was deployed but didn't answer in time; it usually does a little later. */
    NOT_STARTED,

    /** Cloudflare refused for another reason, given in the result's detail. */
    REFUSED,

    /** Signing in with Cloudflare was cancelled, or not allowed. */
    SIGN_IN_CANCELLED,

    /** The browser came back with an answer that wasn't for this sign-in (another state): it was ignored. */
    SIGN_IN_MISMATCH,

    /** Cloudflare gave no token for the sign-in, for the reason in the result's detail. */
    SIGN_IN_REFUSED,
}

sealed interface SetupResult {
    /** Internet sync is on, through the Worker at [url]; [relay]: with Cloudflare's relay. */
    data class Done(val url: String, val relay: Boolean) : SetupResult

    data class Failed(val reason: SetupFailure, val detail: String? = null) : SetupResult
}

/**
 * Sets up internet sync with the user's own free Cloudflare account (docs/PLAN.md §8.4, bring your own Cloudflare):
 * deploys the Worker this app carries with a token the user made for it, waits for it to answer, and turns internet
 * sync on with it. The token is used for this and kept nowhere.
 */
@Singleton
class WorkerSetup internal constructor(
    private val bundle: () -> WorkerScript?,
    private val deployer: (String) -> WorkerDeployer,
    private val settings: SettingsRepository,
    private val receiver: ConsumerReceiver,
    private val startTimeoutMillis: Long = START_TIMEOUT_MILLIS,
    private val pollMillis: Long = POLL_MILLIS,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        settings: SettingsRepository,
        receiver: ConsumerReceiver,
    ) : this({ bundleIn(context) }, { token -> WorkerDeployer(token) }, settings, receiver)

    private val script by lazy(bundle)

    /** Whether this build carries the Worker; without it, only a Worker's address can be entered. */
    fun available(): Boolean = script != null

    @Suppress("TooGenericExceptionCaught") // Whatever Cloudflare's answers or the phone throw, setup says so.
    suspend fun deploy(token: String, onStep: (SetupStep) -> Unit): SetupResult = withContext(Dispatchers.IO) {
        val script = script ?: return@withContext SetupResult.Failed(SetupFailure.REFUSED, "No Worker in this build")
        val key = tokenOf(token) ?: return@withContext SetupResult.Failed(SetupFailure.INVALID_TOKEN)
        try {
            deploy(script, deployer(key), onStep)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            // An answer or a fault this app doesn't expect: shown as a failure, never a crash.
            val error = Diagnostics.describe(e).replace(key, "<token>")
            Diagnostics.log(Area.RECEIVE, "Internet: setting up the Worker failed unexpectedly: $error")
            SetupResult.Failed(SetupFailure.REFUSED, e.javaClass.simpleName)
        }
    }

    private suspend fun deploy(script: WorkerScript, worker: WorkerDeployer, onStep: (SetupStep) -> Unit): SetupResult {
        val deployment = try {
            worker.deploy(script) { onStep(SetupStep.valueOf(it.name)) }
        } catch (e: DeployException) {
            Diagnostics.log(Area.RECEIVE, "Internet: setting up the Worker failed", e)
            return SetupResult.Failed(e.reason(), e.message)
        } catch (e: IOException) {
            Diagnostics.log(Area.RECEIVE, "Internet: Cloudflare couldn't be reached", e)
            return SetupResult.Failed(SetupFailure.OFFLINE)
        }
        onStep(SetupStep.STARTING)
        val live = withTimeoutOrNull(startTimeoutMillis) {
            while (!worker.isLive(deployment.url)) delay(pollMillis)
        }
        // Saved either way: a Worker that's slow to start is reached once it has.
        settings.setRendezvousUrl(deployment.url)
        receiver.refreshInternet()
        Diagnostics.log(
            Area.RECEIVE,
            "Internet: Worker set up (relay: ${deployment.relay}, answering: ${live != null})",
        )
        return if (live == null) {
            SetupResult.Failed(SetupFailure.NOT_STARTED)
        } else {
            SetupResult.Done(deployment.url, deployment.relay)
        }
    }

    private fun DeployException.reason(): SetupFailure {
        val code = code ?: return SetupFailure.REFUSED
        return when {
            step == WorkerDeployer.Step.ACCOUNT && code in INVALID_TOKEN_CODES -> SetupFailure.INVALID_TOKEN
            code in NOT_ALLOWED_CODES -> SetupFailure.NOT_ALLOWED
            else -> SetupFailure.REFUSED
        }
    }

    companion object {
        /**
         * Cloudflare's page to create an API token, filled in with what MediaSync needs: editing Workers, and
         * Realtime for the relay's TURN key.
         */
        val TOKEN_URL: String = run {
            val permissions = """[{"key":"workers_scripts","type":"edit"},{"key":"calls","type":"edit"}]"""
            "https://dash.cloudflare.com/profile/api-tokens?permissionGroupKeys=" +
                URLEncoder.encode(permissions, "UTF-8") + "&accountId=*&zoneId=all&name=MediaSync"
        }

        /**
         * [pasted] as an API token: without the spaces, line breaks and invisible characters that copying from a web
         * page can bring along, and without a "Bearer " before it. `null` if what's left can't be a token: an HTTP
         * header only takes visible ASCII, and Android throws (it doesn't fail the request) for anything else.
         */
        internal fun tokenOf(pasted: String): String? {
            val token = pasted.replace(BEARER, "").filterNot {
                it.isWhitespace() || it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()
            }
            return token.takeIf { it.isNotEmpty() && it.all { char -> char in '!'..'~' } }
        }

        private val BEARER = Regex("""^\s*bearer\s+""", RegexOption.IGNORE_CASE)

        /** Invalid token (1000, 9109), or not allowed to list accounts (10000). */
        private val INVALID_TOKEN_CODES = setOf(1_000, 9_109, 10_000)
        private val NOT_ALLOWED_CODES = setOf(10_000, 10_002)

        /** A new workers.dev address can take a few minutes to be found. */
        private const val START_TIMEOUT_MILLIS = 5 * 60_000L
        private const val POLL_MILLIS = 5_000L
        private const val MODULE = "rendezvous/worker.js"
        private const val MANIFEST = "rendezvous/worker.json"

        private fun bundleIn(context: Context): WorkerScript? = try {
            val module = context.assets.open(MODULE).use { it.readBytes() }
            val manifest = context.assets.open(MANIFEST).use { it.readBytes().decodeToString() }
            WorkerScript.of(module, manifest)
        } catch (_: IOException) {
            null // A build without the Worker.
        }
    }
}

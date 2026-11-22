/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import io.github.danyk20.mediasync.platform.desktop.IdentityLockedException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the app is listening for phones yet. */
enum class StartState { STARTING, WAITING_FOR_KEYCHAIN, RECEIVING, FAILED }

/**
 * Starts receiving, and waits if it can't yet: right after login the OS keychain may still be locked, and the device
 * identity's password is in it ([IdentityLockedException]). That is not an error and must never lead to a new
 * identity (every phone would have to pair again): try again every [retryMillis] until it works.
 * [start] is `receiver.hold("app")`; [pause] is injected so tests need no real time.
 */
class ReceiverStarter(
    private val start: () -> Unit,
    private val log: (String) -> Unit = {},
    private val retryMillis: Long = RETRY_MILLIS,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val _state = MutableStateFlow(StartState.STARTING)
    val state: StateFlow<StartState> = _state.asStateFlow()

    /** Returns once receiving started, or failed for a reason that waiting won't fix. Cancel it to give up. */
    @Suppress("TooGenericExceptionCaught") // Whatever stops it from listening is logged; the window still opens.
    suspend fun run() {
        while (true) {
            try {
                start()
                _state.value = StartState.RECEIVING
                return
            } catch (e: IdentityLockedException) {
                if (_state.value != StartState.WAITING_FOR_KEYCHAIN) log("Waiting for the keychain: ${e.message}")
                _state.value = StartState.WAITING_FOR_KEYCHAIN
                pause(retryMillis)
            } catch (e: Exception) {
                log("Not receiving: ${e.message}")
                _state.value = StartState.FAILED
                return
            }
        }
    }

    companion object {
        const val RETRY_MILLIS = 10_000L
    }
}

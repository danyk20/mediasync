/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.desktop

import io.github.danyk20.mediasync.core.database.PeerStore
import io.github.danyk20.mediasync.core.model.DeviceRole
import io.github.danyk20.mediasync.platform.desktop.DesktopLocalNetwork
import io.github.danyk20.mediasync.platform.desktop.DesktopServiceDiscovery
import io.github.danyk20.mediasync.sync.android.ConsumerReceiver
import io.github.danyk20.mediasync.transport.lan.IdentityProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The receiving role: listens for phones and announces itself over mDNS (also with the window closed), copies what
 * arrived into the backup folder, announces again when the network changes, and turns on *Start at login* when the
 * first sending phone pairs.
 */
@Suppress("LongParameterList") // Collaborators, one each.
class ReceivingPart(
    private val receiver: ConsumerReceiver,
    private val folderBackup: FolderBackupService,
    private val startAtLogin: DesktopStartAtLogin,
    private val peers: PeerStore,
    private val network: DesktopLocalNetwork,
    private val discovery: DesktopServiceDiscovery,
    private val log: (String) -> Unit,
) : RolePart {
    private val _state = MutableStateFlow<StartState?>(null)

    /** Whether it is listening yet; `null` while the part does not run. */
    val state: StateFlow<StartState?> = _state.asStateFlow()

    private var job: Job? = null

    override fun start(scope: CoroutineScope) {
        val starter = RoleStarter(start = { receiver.hold("app") }, log = log)
        job = scope.launch {
            val own = this
            launch { receiver.state.first { it.listening }.let { println("MediaSync: receiving on port ${it.port}") } }
            launch { starter.state.collect { _state.value = it } }
            launch { starter.run() }
            launch {
                val onChanged = {
                    own.launch(Dispatchers.IO) { discovery.reannounce() }
                    Unit
                }
                NetworkWatcher(addresses = network::localAddresses, onChanged = onChanged).run()
            }
            launch(Dispatchers.IO) {
                startAtLogin.watchProducers(
                    peers.observeAll().map { all ->
                        all.count { it.role == DeviceRole.PRODUCER }
                    },
                )
            }
        }
        folderBackup.start()
    }

    override suspend fun stop() {
        folderBackup.stop() // Before the database closes: a copy finishes first.
        job?.cancelAndJoin()
        job = null
        receiver.release("app") // Closes the servers and the mDNS advertisement.
        _state.value = null
    }
}

/**
 * The sending role: no receiver, no open port, no announcement, no backup copying. A sync runs when the user asks
 * (*Sync now*) through `ProducerSync`, and on the receiver's schedule ([SenderScheduler]). A sync signs with the device
 * identity, so the part first waits for it like the receiving part does ([RoleStarter]): the identity is not used
 * until it can be read.
 */
class SendingPart(
    /** Stops the sync running now, after the file it is sending: the user's sync, or the scheduler's. */
    private val cancelSync: () -> Unit,
    private val identity: IdentityProvider,
    private val scheduler: SenderScheduler,
    private val log: (String) -> Unit,
) : RolePart {
    private val _state = MutableStateFlow<StartState?>(null)

    /** Whether it is ready yet; `null` while the part does not run. */
    val state: StateFlow<StartState?> = _state.asStateFlow()

    private var job: Job? = null

    override fun start(scope: CoroutineScope) {
        log("Sending: syncs follow the receiver's schedule, or run when you press Sync now")
        val starter = RoleStarter(start = { identity.identity() }, log = log)
        job = scope.launch {
            launch { starter.state.collect { _state.value = it } }
            starter.run()
            if (starter.state.value == StartState.RUNNING) scheduler.run()
        }
    }

    /**
     * Cancels the scheduler first, so it cannot see the sync's cancelled result and mark the due time handled, then
     * stops the sync and waits for the part to end, for [STOP_MILLIS] at most: a sync that ignores the cancellation is
     * logged and left behind, so quitting never hangs.
     */
    override suspend fun stop() {
        val running = job
        running?.cancel()
        cancelSync()
        running?.let { part ->
            if (withTimeoutOrNull(STOP_MILLIS) { part.join() } == null) {
                log("Sending: the sync did not stop within ${STOP_MILLIS / MILLIS_PER_SECOND} s; left behind")
            }
        }
        job = null
        _state.value = null
    }

    private companion object {
        const val STOP_MILLIS = 5_000L
        const val MILLIS_PER_SECOND = 1_000L
    }
}

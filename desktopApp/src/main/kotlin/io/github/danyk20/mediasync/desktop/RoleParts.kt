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
import io.github.danyk20.mediasync.sync.android.ProducerSync
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
 * (*Sync now*) through [ProducerSync]; the schedule comes with M9.2b. A sync signs with the device identity, so the
 * part first waits for it like the receiving part does ([RoleStarter]): the identity is not used until it can be read.
 */
class SendingPart(
    private val sync: ProducerSync,
    private val identity: IdentityProvider,
    private val log: (String) -> Unit,
) : RolePart {
    private val _state = MutableStateFlow<StartState?>(null)

    /** Whether it is ready yet; `null` while the part does not run. */
    val state: StateFlow<StartState?> = _state.asStateFlow()

    private var job: Job? = null

    override fun start(scope: CoroutineScope) {
        log("Sending: syncs run when you press Sync now")
        val starter = RoleStarter(start = { identity.identity() }, log = log)
        job = scope.launch {
            launch { starter.state.collect { _state.value = it } }
            starter.run()
        }
    }

    /** Stops the wait and any sync, after the file it is sending. */
    override suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        sync.cancel()
        _state.value = null
    }
}

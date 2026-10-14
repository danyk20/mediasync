/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector.android

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.danyk20.mediasync.connector.AccountId
import io.github.danyk20.mediasync.connector.BackupConnectorFactory
import io.github.danyk20.mediasync.connector.BackupCoordinator
import io.github.danyk20.mediasync.connector.BackupOrchestrator
import io.github.danyk20.mediasync.connector.BackupState
import io.github.danyk20.mediasync.connector.ConnectorAccount
import io.github.danyk20.mediasync.connector.ConnectorRegistry
import io.github.danyk20.mediasync.connector.DeviceAppConnectorFactory
import io.github.danyk20.mediasync.core.database.BackupDao
import io.github.danyk20.mediasync.core.database.ReceivedItemDao
import io.github.danyk20.mediasync.core.datastore.SettingsRepository
import io.github.danyk20.mediasync.core.diagnostics.Area
import io.github.danyk20.mediasync.core.diagnostics.Diagnostics
import io.github.danyk20.mediasync.core.model.DeviceRole
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Whether an app is installed and enabled on this phone. */
fun interface InstalledApps {
    fun isInstalled(packageName: String): Boolean
}

/** Needs the package listed under `<queries>` in a manifest (Android 11+ package visibility). */
class PackageManagerApps @Inject constructor(@ApplicationContext private val context: Context) : InstalledApps {
    override fun isInstalled(packageName: String): Boolean = try {
        context.packageManager.getApplicationInfo(packageName, 0).enabled
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}

/** How one account's backups stand, for the receiving phone's screens. */
data class BackupOverview(
    val account: ConnectorAccount,
    /** Handed to another app on this phone, and still here. */
    val handedOver: Int = 0,
    /** Backed up by another app, which then removed them from this phone (docs/PLAN.md §9.3.1). */
    val removedByApp: Int = 0,
    /** Uploaded by MediaSync. */
    val backedUp: Int = 0,
    /** Backed up or handed over since midnight. */
    val today: Int = 0,
    /** Received, and not backed up yet. */
    val waiting: Int = 0,
)

/**
 * Cloud backup on the receiving phone (docs/PLAN.md §9): the connectors this app has, the accounts the user set up,
 * and the backups themselves, which start as soon as items arrive (§9.6).
 */
@Singleton
class CloudBackup internal constructor(
    private val registry: ConnectorRegistry,
    private val accounts: AccountStore,
    private val backups: BackupDao,
    private val received: ReceivedItemDao,
    private val settings: SettingsRepository,
    private val orchestrator: BackupOrchestrator,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    @Inject
    constructor(
        registry: ConnectorRegistry,
        accounts: AccountStore,
        backups: BackupDao,
        received: ReceivedItemDao,
        settings: SettingsRepository,
        orchestrator: BackupOrchestrator,
        clock: Clock,
    ) : this(
        registry,
        accounts,
        backups,
        received,
        settings,
        orchestrator,
        clock,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    /** The connectors where another app on this phone does the backup, such as the Google Photos app. */
    val deviceApps: List<DeviceAppConnectorFactory> get() = registry.deviceApps

    private val started = AtomicBoolean()

    /** Backs up while the app runs, on a receiving phone only. Call at start-up; later calls do nothing. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        val active = combine(settings.role, accounts.observe()) { role, all ->
            if (role == DeviceRole.CONSUMER) all else emptyList()
        }
        BackupCoordinator(registry, orchestrator).start(scope, active, received.observeCount().map { })
    }

    /** Every account, with how its backups stand. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun overviews(): Flow<List<BackupOverview>> = accounts.observe().flatMapLatest { all ->
        if (all.isEmpty()) flowOf(emptyList()) else combine(all.map(::overview)) { it.toList() }
    }

    /**
     * Starts backing up through [factory]: for another app's connector, once the user has set that app up. The account
     * is named after the connector, so setting it up again keeps its ledger.
     */
    suspend fun connect(factory: BackupConnectorFactory) {
        val descriptor = factory.descriptor
        accounts.add(
            ConnectorAccount(AccountId(descriptor.id.value), descriptor.id, descriptor.displayName, clock.millis()),
        )
        Diagnostics.log(Area.BACKUP, "Backup to ${descriptor.id} set up")
    }

    /** Stops backing up to [account]; its receipts stay, should it be set up again. */
    suspend fun disconnect(account: AccountId) {
        accounts.remove(account)
        Diagnostics.log(Area.BACKUP, "Backup to $account turned off")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun overview(account: ConnectorAccount): Flow<BackupOverview> {
        val id = account.id.value
        val today = midnights().flatMapLatest { backups.observeBackedUpSince(id, it) }
        return combine(
            backups.observeStateCounts(id),
            today,
            backups.observeWaiting(id),
        ) { counts, sinceMidnight, waiting ->
            val count = counts.associate { it.state to it.count }
            BackupOverview(
                account = account,
                handedOver = count[BackupState.HANDED_OVER.name] ?: 0,
                removedByApp = count[BackupState.REMOVED_BY_APP.name] ?: 0,
                backedUp = (count[BackupState.BACKED_UP.name] ?: 0) + (count[BackupState.VERIFIED.name] ?: 0),
                today = sinceMidnight,
                waiting = waiting,
            )
        }
    }

    /** The start of today in the phone's time zone, and again at each midnight. */
    private fun midnights(): Flow<Long> = flow {
        while (true) {
            val now = ZonedDateTime.now(clock)
            val midnight = now.toLocalDate().atStartOfDay(now.zone)
            emit(midnight.toInstant().toEpochMilli())
            delay(Duration.between(now, midnight.plusDays(1)).toMillis().coerceAtLeast(1))
        }
    }
}

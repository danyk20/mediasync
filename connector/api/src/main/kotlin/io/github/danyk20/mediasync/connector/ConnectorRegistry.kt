/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector

import java.time.Clock
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * Every kind of connector this app has (docs/PLAN.md §9.1). Each connector module registers its factory under its
 * id (Hilt `@IntoMap @ConnectorKey("…")`), so the app finds them at runtime, and a new cloud needs no changes here.
 */
class ConnectorRegistry(
    registered: Map<String, BackupConnectorFactory>,
    private val decorators: ConnectorDecorators = ConnectorDecorators(),
) {
    private val byId: Map<ConnectorId, BackupConnectorFactory> = registered.map { (key, factory) ->
        val id = factory.descriptor.id
        require(key == id.value) { "Registered as $key, but its id is $id" }
        require(factory.descriptor.delegated == (factory is DeviceAppConnectorFactory)) {
            "$id: a connector where another app backs up has a DeviceAppConnectorFactory, and only it"
        }
        id to factory
    }.toMap()

    /** Every kind of connector, by name. */
    val factories: List<BackupConnectorFactory> = byId.values.sortedBy { it.descriptor.displayName }

    /** The connectors where another app on this phone does the backup, such as the Google Photos app. */
    val deviceApps: List<DeviceAppConnectorFactory> = factories.filterIsInstance<DeviceAppConnectorFactory>()

    operator fun get(id: ConnectorId): BackupConnectorFactory? = byId[id]

    /**
     * A connector backing up to [account], wrapped in the shared decorators. `null` if this app has no connector of
     * its kind, such as one a newer version added before the app was downgraded.
     */
    fun connectorFor(account: ConnectorAccount): BackupConnector? =
        byId[account.connectorId]?.create(account)?.let(decorators::wrap)
}

/**
 * Wraps every connector in what all of them need (docs/PLAN.md §9.1), outermost first: logging, retries, the
 * service's rate limit, then its daily allowance. Rate and allowance only apply when the descriptor declares them.
 */
class ConnectorDecorators(
    private val clock: Clock = Clock.systemUTC(),
    private val retry: RetryPolicy = RetryPolicy(),
    private val random: Random = Random.Default,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val nanoTime: () -> Long = System::nanoTime,
) {
    fun wrap(connector: BackupConnector): BackupConnector {
        val limits = connector.descriptor.limits
        var wrapped = connector
        limits.requestsPerDay?.let { wrapped = QuotaTrackingConnector(wrapped, it, limits.quotaZone, clock) }
        limits.requestsPerSecond?.let { rate ->
            wrapped = RateLimitedConnector(wrapped, rate, nanoTime = nanoTime, sleep = sleep)
        }
        wrapped = RetryingConnector(wrapped, retry, random, sleep)
        return InstrumentedConnector(wrapped, nanoTime)
    }
}

/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector

import io.github.danyk20.mediasync.connector.testing.FakeConnector
import io.github.danyk20.mediasync.connector.testing.FakeConnectorFactory
import io.github.danyk20.mediasync.connector.testing.FakeDeviceAppFactory
import io.github.danyk20.mediasync.connector.testing.account
import io.github.danyk20.mediasync.connector.testing.descriptor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test

class ConnectorRegistryTest {

    private val cloud = FakeConnectorFactory(descriptor("test-cloud"))
    private val app = FakeDeviceAppFactory()

    @Test
    fun connectorsAreFoundByTheIdTheyRegisteredUnder() {
        val registry = ConnectorRegistry(mapOf("test-cloud" to cloud, "test-app" to app))

        registry[ConnectorId("test-cloud")] shouldBeSameInstanceAs cloud
        registry[ConnectorId("unknown")].shouldBeNull()
        registry.factories.map { it.descriptor.displayName } shouldBe listOf("Test cloud", "Test cloud")
        registry.deviceApps shouldBe listOf(app)
    }

    @Test
    fun connectorsComeDecoratedForTheirAccount() {
        val registry = ConnectorRegistry(mapOf("test-cloud" to cloud))

        val connector = registry.connectorFor(account("test-cloud"))

        connector.shouldBeInstanceOf<InstrumentedConnector>().undecorated().shouldBeInstanceOf<FakeConnector>()
        cloud.created shouldBe listOf(account("test-cloud"))
        registry.connectorFor(account("from-a-newer-version")).shouldBeNull()
    }

    @Test
    fun aRegistrationMustMatchItsConnector() {
        shouldThrow<IllegalArgumentException> { ConnectorRegistry(mapOf("other-name" to cloud)) }
        val delegatedWithoutProfile = FakeConnectorFactory(
            descriptor("test-app", setOf(Capability.DELEGATED_TO_DEVICE_APP)),
        )
        shouldThrow<IllegalArgumentException> { ConnectorRegistry(mapOf("test-app" to delegatedWithoutProfile)) }
    }

    @Test
    fun simpleConnectorsNeedNoSettingsOrSignIn() {
        cloud.settingsSchema shouldBeSameInstanceAs SettingsSchema.Empty
        cloud.authenticator() shouldBeSameInstanceAs ConnectorAuthenticator.None
    }
}

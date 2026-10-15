/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SettingsSchemaTest {

    private val schema = SettingsSchema(
        listOf(
            SettingField.Choice("quality", listOf("original", "storage-saver"), default = "original"),
            SettingField.Toggle("wifi-only", on = true),
            SettingField.Text("server", required = true, pattern = "https://.+"),
            SettingField.Text("password", secret = true),
        ),
    )

    @Test
    fun fieldsStartWithTheirDefaults() {
        schema.defaults() shouldBe mapOf("quality" to "original", "wifi-only" to "true")
        SettingsSchema.Empty.defaults() shouldBe emptyMap()
    }

    @Test
    fun validValuesHaveNoProblems() {
        schema.problems(mapOf("server" to "https://photos.example", "quality" to "storage-saver")).shouldBeEmpty()
        val withEmptyPassword = mapOf("server" to "https://photos.example", "wifi-only" to "false", "password" to "")
        schema.problems(withEmptyPassword).shouldBeEmpty()
    }

    @Test
    fun eachProblemIsNamedByItsKey() {
        schema.problems(
            mapOf("quality" to "best", "wifi-only" to "yes", "server" to "http://photos.example", "albums" to "on"),
        ) shouldBe mapOf(
            "quality" to SettingProblem.INVALID,
            "wifi-only" to SettingProblem.INVALID,
            "server" to SettingProblem.INVALID,
            "albums" to SettingProblem.UNKNOWN,
        )
        schema.problems(emptyMap()) shouldBe mapOf("server" to SettingProblem.MISSING)
    }

    @Test
    fun keysAndDefaultsMustMakeSense() {
        shouldThrow<IllegalArgumentException> {
            SettingsSchema(listOf(SettingField.Toggle("a"), SettingField.Text("a")))
        }
        shouldThrow<IllegalArgumentException> { SettingField.Choice("quality", listOf("original"), default = "best") }
    }
}

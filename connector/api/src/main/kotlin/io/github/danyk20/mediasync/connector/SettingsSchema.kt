/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.connector

/**
 * What a connector lets the user set, described as data (docs/PLAN.md §9.1): one generic settings screen shows
 * every connector's fields, so simple connectors need no screens of their own. Values are kept as text in
 * [ConnectorAccount.settings], by field key.
 */
class SettingsSchema(val fields: List<SettingField>) {
    init {
        require(fields.map { it.key }.toSet().size == fields.size) { "Two settings with the same key" }
    }

    /** The value each field starts with, where it has one. */
    fun defaults(): Map<String, String> = fields.mapNotNull { field -> field.default?.let { field.key to it } }.toMap()

    /** What's wrong with [values], by key; empty when they can be saved. A missing value takes the default. */
    fun problems(values: Map<String, String>): Map<String, SettingProblem> {
        val known = fields.associateBy { it.key }
        val unknown = values.keys.filter { it !in known }.associateWith { SettingProblem.UNKNOWN }
        val wrong = fields.mapNotNull { field -> field.problem(values[field.key])?.let { field.key to it } }.toMap()
        return unknown + wrong
    }

    companion object {
        val Empty = SettingsSchema(emptyList())
    }
}

/** One setting. Its label and explanation are app strings named after its [key]. */
sealed interface SettingField {
    val key: String
    val default: String?

    /** What's wrong with [value] (`null`: not given), or `null` if it's fine. */
    fun problem(value: String?): SettingProblem?

    /** On or off: `true` or `false`. */
    data class Toggle(override val key: String, val on: Boolean = false) : SettingField {
        override val default: String get() = on.toString()

        override fun problem(value: String?): SettingProblem? =
            SettingProblem.INVALID.takeUnless { value == null || value == "true" || value == "false" }
    }

    /** One of a few [options], such as photo quality. */
    data class Choice(override val key: String, val options: List<String>, override val default: String) :
        SettingField {
        init {
            require(default in options) { "$key: the default isn't one of the options" }
        }

        override fun problem(value: String?): SettingProblem? =
            SettingProblem.INVALID.takeUnless { value == null || value in options }
    }

    /**
     * Free text, such as a server address. A [pattern] must match all of it. A [secret] (a password or key) is never
     * shown again or logged.
     */
    data class Text(
        override val key: String,
        val required: Boolean = false,
        val pattern: String? = null,
        val secret: Boolean = false,
    ) : SettingField {
        override val default: String? get() = null

        private val regex: Regex? by lazy { pattern?.let(::Regex) }

        override fun problem(value: String?): SettingProblem? = when {
            value.isNullOrEmpty() -> SettingProblem.MISSING.takeIf { required }
            regex?.matches(value) == false -> SettingProblem.INVALID
            else -> null
        }
    }
}

enum class SettingProblem {
    /** A required value is missing. */
    MISSING,

    /** Not a value this field takes. */
    INVALID,

    /** A value for a field the connector doesn't have. */
    UNKNOWN,
}

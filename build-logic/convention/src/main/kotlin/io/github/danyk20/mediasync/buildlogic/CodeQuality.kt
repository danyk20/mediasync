/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.buildlogic

import com.diffplug.gradle.spotless.SpotlessExtension
import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType

const val LICENSE_HEADER = """/*
 * Copyright ${'$'}YEAR The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
"""

/** Name of the Kover variant CI reports on: the debug variant for Android, the main source set for JVM modules. */
const val KOVER_CI_VARIANT = "ci"

/** Formatting (ktlint) and the Apache-2.0 license header on every Kotlin source file. */
internal fun Project.configureSpotless() {
    pluginManager.apply(libs.pluginId("spotless"))
    extensions.configure<SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            ktlint(libs.version("ktlint"))
            licenseHeader(LICENSE_HEADER)
        }
        kotlinGradle {
            target("*.kts")
            ktlint(libs.version("ktlint"))
        }
    }
}

/** Static analysis. Detekt runs on its own pinned Kotlin compiler, independent of the project's Kotlin version. */
internal fun Project.configureDetekt() {
    pluginManager.apply(libs.pluginId("detekt"))
    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        parallel = true
        config.setFrom(rootDir.resolve("config/detekt/detekt.yml"))
    }
    val detektKotlin = libs.version("detektKotlin")
    configurations.matching { it.name == "detekt" }.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") useVersion(detektKotlin)
        }
    }
    tasks.withType<Detekt>().configureEach {
        jvmTarget = JVM_TARGET.target
        reports {
            html.required.set(true)
            sarif.required.set(true)
            xml.required.set(false)
            txt.required.set(false)
            md.required.set(false)
        }
    }
}

/** Registers this module's tests in the shared `ci` coverage variant aggregated at the root. */
internal fun Project.configureKover(sourceVariant: String) {
    pluginManager.apply(libs.pluginId("kover"))
    extensions.configure<KoverProjectExtension> {
        currentProject {
            createVariant(KOVER_CI_VARIANT) {
                add(sourceVariant)
            }
        }
    }
}

internal fun Project.configureCodeQuality(koverSourceVariant: String) {
    configureSpotless()
    configureDetekt()
    configureKover(koverSourceVariant)
}

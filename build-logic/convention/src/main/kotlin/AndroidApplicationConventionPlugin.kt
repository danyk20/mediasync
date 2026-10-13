/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
import app.cash.licensee.LicenseeExtension
import com.android.build.api.dsl.ApplicationExtension
import io.github.danyk20.mediasync.buildlogic.configureCodeQuality
import io.github.danyk20.mediasync.buildlogic.configureJava
import io.github.danyk20.mediasync.buildlogic.configureKotlin
import io.github.danyk20.mediasync.buildlogic.configureLint
import io.github.danyk20.mediasync.buildlogic.configureManagedDevices
import io.github.danyk20.mediasync.buildlogic.configureUnitTests
import io.github.danyk20.mediasync.buildlogic.libs
import io.github.danyk20.mediasync.buildlogic.pluginId
import io.github.danyk20.mediasync.buildlogic.version
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(libs.pluginId("android-application"))
        pluginManager.apply(libs.pluginId("licensee"))

        extensions.configure<ApplicationExtension> {
            compileSdk = libs.version("compileSdk").toInt()
            defaultConfig {
                minSdk = libs.version("minSdk").toInt()
                targetSdk = libs.version("targetSdk").toInt()
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                // Every instrumented test starts from a clean app (no stored role, no pairings).
                testInstrumentationRunnerArguments["clearPackageData"] = "true"
            }
            compileOptions.configureJava()
            lint.configureLint()
            testOptions {
                unitTests.configureUnitTests()
                animationsDisabled = true
                execution = "ANDROIDX_TEST_ORCHESTRATOR"
                configureManagedDevices()
            }
        }

        // Every license on the release runtime classpath must be explicitly allowed. Add new ones only
        // after checking they're compatible with distributing an Apache-2.0 app.
        extensions.configure<LicenseeExtension> {
            allow("Apache-2.0")
            allow("BSD-3-Clause") // protobuf, bundled by DataStore
        }

        configureKotlin()
        configureCodeQuality(koverSourceVariant = "debug")
    }
}

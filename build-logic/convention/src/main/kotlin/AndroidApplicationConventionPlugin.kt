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
            // Also check the pure Kotlin modules the app runs, against its minSdk: the JDK they compile
            // against has APIs that older Android versions lack (docs/PLAN.md §12).
            lint.checkDependencies = true
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
            allowUrl("https://opensource.org/license/bsd-3-clause/") {
                because("BSD-3-Clause, as WebRTC (io.github.webrtc-sdk) names it")
            }
            // Not open source: Google's Android SDK terms, under which Google Play services' client libraries come, which
            // Firebase Cloud Messaging needs for wake-ups (docs/PLAN.md §10.4): play-services-base, -basement,
            // -cloud-messaging, -stats, -tasks, firebase-iid-interop and firebase-measurement-connector.
            allowUrl("https://developer.android.com/studio/terms.html") {
                because("Google Play services for Firebase Cloud Messaging (wake-ups); not open source")
            }
        }

        configureKotlin()
        configureCodeQuality(koverSourceVariant = "debug")
    }
}

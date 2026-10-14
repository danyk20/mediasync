/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
import app.cash.licensee.LicenseeExtension
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.ManagedVirtualDevice
import io.github.danyk20.mediasync.buildlogic.configureCodeQuality
import io.github.danyk20.mediasync.buildlogic.configureJava
import io.github.danyk20.mediasync.buildlogic.configureKotlin
import io.github.danyk20.mediasync.buildlogic.configureLint
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
                managedDevices {
                    // Oldest supported Android (minSdk) and the current target.
                    val devices = listOf(
                        ManagedDeviceSpec("api29", "Pixel 2", 29),
                        ManagedDeviceSpec("api31", "Pixel 4", 31),
                        ManagedDeviceSpec("api34", "Pixel 6", 34),
                        ManagedDeviceSpec("api36", "Pixel 6", 36),
                    )
                    // Emulators only run at native speed on the host's own CPU architecture:
                    // x86_64 on CI (Linux runners), arm64 on Apple-silicon Macs.
                    val hostAbi = if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) {
                        "arm64-v8a"
                    } else {
                        "x86_64"
                    }
                    devices.forEach { spec ->
                        allDevices.create(spec.name, ManagedVirtualDevice::class.java) {
                            device = spec.device
                            apiLevel = spec.apiLevel
                            systemImageSource = "aosp"
                            testedAbi = hostAbi
                        }
                    }
                    groups.create("ci") {
                        targetDevices.add(allDevices.getByName("api29"))
                        targetDevices.add(allDevices.getByName("api36"))
                    }
                    groups.create("nightly") {
                        devices.forEach { targetDevices.add(allDevices.getByName(it.name)) }
                    }
                }
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

    private data class ManagedDeviceSpec(val name: String, val device: String, val apiLevel: Int)
}

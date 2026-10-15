/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.DeviceTestBuilder
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import io.github.danyk20.mediasync.buildlogic.configureCodeQuality
import io.github.danyk20.mediasync.buildlogic.configureJava
import io.github.danyk20.mediasync.buildlogic.configureKotlin
import io.github.danyk20.mediasync.buildlogic.configureLint
import io.github.danyk20.mediasync.buildlogic.configureManagedDevices
import io.github.danyk20.mediasync.buildlogic.configureUnitTests
import io.github.danyk20.mediasync.buildlogic.library
import io.github.danyk20.mediasync.buildlogic.libs
import io.github.danyk20.mediasync.buildlogic.pluginId
import io.github.danyk20.mediasync.buildlogic.version
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(libs.pluginId("android-library"))

        extensions.configure<LibraryExtension> {
            compileSdk = libs.version("compileSdk").toInt()
            defaultConfig {
                minSdk = libs.version("minSdk").toInt()
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            compileOptions.configureJava()
            lint.configureLint()
            lint.targetSdk = libs.version("targetSdk").toInt()
            testOptions.targetSdk = libs.version("targetSdk").toInt()
            testOptions.unitTests.configureUnitTests()
            testOptions.configureManagedDevices()
        }
        // A module without device tests would still build a test APK and start an emulator for nothing: on CI,
        // that was 13 of 18 modules and about a third of the instrumented job.
        val hasDeviceTests = file("src/androidTest").exists()
        extensions.configure<LibraryAndroidComponentsExtension> {
            beforeVariants { variant ->
                variant.deviceTests[DeviceTestBuilder.ANDROID_TEST_TYPE]?.enable = hasDeviceTests
            }
        }

        dependencies {
            "testImplementation"(libs.library("junit4"))
            "testImplementation"(libs.library("kotest-assertions"))
            "testImplementation"(libs.library("kotlinx-coroutines-test"))
            "testImplementation"(libs.library("turbine"))
        }

        configureKotlin()
        configureCodeQuality(koverSourceVariant = "debug")
    }
}

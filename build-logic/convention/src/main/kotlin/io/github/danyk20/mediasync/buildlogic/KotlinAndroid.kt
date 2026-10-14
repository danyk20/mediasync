/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.buildlogic

import com.android.build.api.dsl.CompileOptions
import com.android.build.api.dsl.Lint
import com.android.build.api.dsl.ManagedVirtualDevice
import com.android.build.api.dsl.TestOptions
import com.android.build.api.dsl.UnitTestOptions
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

val JAVA_VERSION = JavaVersion.VERSION_17
val JVM_TARGET = JvmTarget.JVM_17

internal fun CompileOptions.configureJava() {
    sourceCompatibility = JAVA_VERSION
    targetCompatibility = JAVA_VERSION
}

internal fun Lint.configureLint() {
    warningsAsErrors = true
    abortOnError = true
    // Version freshness is handled by Dependabot; targetSdk is a deliberate plan decision (docs/PLAN.md §12).
    disable += setOf(
        "AndroidGradlePluginVersion",
        "GradleDependency",
        "NewerVersionAvailable",
        "OldTargetApi",
    )
}

internal fun UnitTestOptions.configureUnitTests() {
    // Needed by Robolectric and Compose UI tests running on the JVM.
    isIncludeAndroidResources = true
    all { test ->
        test.configureTestLogging()
        // Hilt generates unit-test sources in every Android module, including ones whose tests all run
        // on devices (e.g. core:crypto); Gradle would otherwise flag them as "sources but no tests".
        test.failOnNoDiscoveredTests.set(false)
        // Robolectric's Android 16 (API 36) runtime reads FileDescriptor internals, which JDK 17+ hides by default.
        test.jvmArgs(
            "--add-opens=java.base/java.io=ALL-UNNAMED",
            "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
        )
    }
}

internal fun Project.configureKotlin() {
    tasks.withType<KotlinCompilationTask<*>>().configureEach {
        val options = compilerOptions
        if (options is KotlinJvmCompilerOptions) {
            options.jvmTarget.set(JVM_TARGET)
        }
        compilerOptions.allWarningsAsErrors.set(true)
    }
}

internal fun Test.configureTestLogging() {
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

/**
 * Emulators for instrumented tests (Gradle Managed Devices). `ciGroup…` runs on every PR: the oldest
 * supported Android (minSdk) and the current target. `nightlyGroup…` adds the versions in between.
 */
internal fun TestOptions.configureManagedDevices() {
    val devices = listOf(
        ManagedDeviceSpec("api29", "Pixel 2", 29),
        ManagedDeviceSpec("api31", "Pixel 4", 31),
        ManagedDeviceSpec("api34", "Pixel 6", 34),
        ManagedDeviceSpec("api36", "Pixel 6", 36),
    )
    // Emulators only run at native speed on the host's own CPU architecture:
    // x86_64 on CI (Linux runners), arm64 on Apple-silicon Macs.
    val hostAbi = if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "arm64-v8a" else "x86_64"
    managedDevices {
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

private data class ManagedDeviceSpec(val name: String, val device: String, val apiLevel: Int)

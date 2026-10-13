/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.danyk20.mediasync.buildlogic

import com.android.build.api.dsl.CompileOptions
import com.android.build.api.dsl.Lint
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

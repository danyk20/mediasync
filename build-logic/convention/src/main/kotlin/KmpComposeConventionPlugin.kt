/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension
import io.github.danyk20.mediasync.buildlogic.configureCodeQuality
import io.github.danyk20.mediasync.buildlogic.configureDetektAllSourceSets
import io.github.danyk20.mediasync.buildlogic.configureKotlin
import io.github.danyk20.mediasync.buildlogic.configureLint
import io.github.danyk20.mediasync.buildlogic.configureTestLogging
import io.github.danyk20.mediasync.buildlogic.library
import io.github.danyk20.mediasync.buildlogic.libs
import io.github.danyk20.mediasync.buildlogic.pluginId
import io.github.danyk20.mediasync.buildlogic.version
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * A Compose Multiplatform UI module: shared by the Android app and the desktop app.
 *
 * Code lives in `src/commonMain`, Android-only code in `src/androidMain`, desktop-only code in `src/desktopMain`,
 * strings and drawables in `src/commonMain/composeResources`, Robolectric and Roborazzi tests in `src/androidHostTest`.
 * It is not `mediasync.kmp.library`: that one compiles its JVM target against Java 8's API for Android, and Compose's
 * desktop artifacts need Java 17.
 */
class KmpComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(libs.pluginId("kotlin-multiplatform"))
        pluginManager.apply(libs.pluginId("android-kotlin-multiplatform-library"))
        // Lints the Android main code too (without it, only the host tests are analysed), and lets the app's lint
        // check it with checkDependencies.
        pluginManager.apply(libs.pluginId("android-lint"))
        pluginManager.apply(libs.pluginId("kotlin-compose"))
        pluginManager.apply(libs.pluginId("compose-multiplatform"))
        pluginManager.apply(libs.pluginId("roborazzi"))

        val kotlin = extensions.getByType<KotlinMultiplatformExtension>()
        kotlin.apply {
            jvm("desktop")
            (this as org.gradle.api.plugins.ExtensionAware).extensions
                .getByType<KotlinMultiplatformAndroidLibraryExtension>().apply {
                    compileSdk = libs.version("compileSdk").toInt()
                    minSdk = libs.version("minSdk").toInt()
                    androidResources.enable = true
                    withHostTest { isIncludeAndroidResources = true }
                    lint { configureLint() }
                }
            sourceSets.apply {
                getByName("commonMain").dependencies {
                    implementation(libs.library("compose-multiplatform-runtime"))
                    implementation(libs.library("compose-multiplatform-foundation"))
                    implementation(libs.library("compose-multiplatform-ui"))
                    implementation(libs.library("compose-multiplatform-material3"))
                    implementation(libs.library("compose-multiplatform-resources"))
                    implementation(libs.library("compose-multiplatform-ui-tooling-preview"))
                }
                getByName("androidMain").dependencies {
                    implementation(project.dependencies.platform(libs.library("androidx-compose-bom")))
                }
                getByName("androidHostTest").dependencies {
                    implementation(project.dependencies.platform(libs.library("androidx-compose-bom")))
                    implementation(libs.library("androidx-compose-ui-test-junit4"))
                    implementation(libs.library("androidx-compose-ui-test-manifest"))
                    implementation(libs.library("robolectric"))
                    implementation(libs.library("roborazzi"))
                    implementation(libs.library("roborazzi-compose"))
                    implementation(libs.library("roborazzi-junit-rule"))
                    implementation(libs.library("junit4"))
                    implementation(libs.library("kotest-assertions"))
                    implementation(libs.library("kotlinx-coroutines-test"))
                    implementation(libs.library("turbine"))
                }
            }
        }
        tasks.withType<Test>().configureEach {
            configureTestLogging()
            failOnNoDiscoveredTests.set(false)
            // Robolectric's Android 16 (API 36) runtime reads FileDescriptor internals, which JDK 17+ hides by default.
            jvmArgs(
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
            )
        }
        configureKotlin()
        configureCodeQuality(koverSourceVariant = "android")
        // CI (.github/workflows/ci.yml) runs the Android unit-test and lint tasks by these names for every module.
        // This module has no build variants, so the names point at its host-test and lint tasks.
        tasks.register("testDebugUnitTest") { dependsOn("testAndroidHostTest") }
        // As in the Android modules (alignLifecycle): Compose Multiplatform brings an older Lifecycle, whose lint checks
        // AGP 9.5 can't load (ObsoleteLintCustomCheck).
        dependencies.constraints.add("androidMainImplementation", libs.library("androidx-lifecycle-runtime"))
        tasks.register("lintDebug") { dependsOn("lintAndroidMain") }
        configureDetektAllSourceSets()
    }
}

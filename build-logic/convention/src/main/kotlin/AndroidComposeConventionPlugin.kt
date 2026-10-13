/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import io.github.danyk20.mediasync.buildlogic.library
import io.github.danyk20.mediasync.buildlogic.libs
import io.github.danyk20.mediasync.buildlogic.pluginId
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Jetpack Compose for an Android application or library, plus JVM UI tests:
 * Robolectric for behaviour and Roborazzi for screenshot regressions.
 */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(libs.pluginId("kotlin-compose"))
        pluginManager.apply(libs.pluginId("roborazzi"))

        pluginManager.withPlugin(libs.pluginId("android-application")) {
            extensions.configure<ApplicationExtension> { buildFeatures.compose = true }
        }
        pluginManager.withPlugin(libs.pluginId("android-library")) {
            extensions.configure<LibraryExtension> { buildFeatures.compose = true }
        }

        dependencies {
            val bom = platform(libs.library("androidx-compose-bom"))
            "implementation"(bom)
            "implementation"(libs.library("androidx-compose-foundation"))
            "implementation"(libs.library("androidx-compose-material3"))
            "implementation"(libs.library("androidx-compose-ui"))
            "implementation"(libs.library("androidx-compose-ui-tooling-preview"))
            "debugImplementation"(libs.library("androidx-compose-ui-tooling"))
            "debugImplementation"(libs.library("androidx-compose-ui-test-manifest"))

            "testImplementation"(bom)
            "testImplementation"(libs.library("androidx-compose-ui-test-junit4"))
            "testImplementation"(libs.library("robolectric"))
            "testImplementation"(libs.library("roborazzi"))
            "testImplementation"(libs.library("roborazzi-compose"))
            "testImplementation"(libs.library("roborazzi-junit-rule"))

            "androidTestImplementation"(bom)
            "androidTestImplementation"(libs.library("androidx-compose-ui-test-junit4"))
        }
    }
}

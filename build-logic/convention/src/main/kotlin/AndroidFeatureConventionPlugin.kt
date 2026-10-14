/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
import io.github.danyk20.mediasync.buildlogic.library
import io.github.danyk20.mediasync.buildlogic.libs
import io.github.danyk20.mediasync.buildlogic.pluginId
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.project

/** A feature module: Compose UI screens and their ViewModels, built on the design system. */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(libs.pluginId("mediasync-android-library"))
        pluginManager.apply(libs.pluginId("mediasync-android-compose"))
        pluginManager.apply(libs.pluginId("mediasync-hilt"))

        dependencies {
            "implementation"(project(":core:designsystem"))
            "implementation"(project(":core:model"))
            "implementation"(libs.library("androidx-activity-compose"))
            "implementation"(libs.library("androidx-hilt-lifecycle-viewmodel-compose"))
            "implementation"(libs.library("androidx-lifecycle-runtime-compose"))
            "implementation"(libs.library("androidx-lifecycle-viewmodel-compose"))

            "testImplementation"(project(":core:testing"))
            "testImplementation"(libs.library("mockk"))
        }
    }
}

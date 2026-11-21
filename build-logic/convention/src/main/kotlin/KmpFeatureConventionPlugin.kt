/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
import io.github.danyk20.mediasync.buildlogic.library
import io.github.danyk20.mediasync.buildlogic.libs
import io.github.danyk20.mediasync.buildlogic.pluginId
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * A multiplatform feature module: Compose screens and their ViewModels, shared by the Android and desktop apps.
 * The counterpart of `mediasync.android.feature`, on top of `mediasync.kmp.compose`.
 */
class KmpFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply(libs.pluginId("mediasync-kmp-compose"))
            pluginManager.apply(libs.pluginId("mediasync-di"))

            extensions.getByType<KotlinMultiplatformExtension>().sourceSets.apply {
                getByName("commonMain").dependencies {
                    implementation(project(":core:designsystem"))
                    implementation(project(":core:model"))
                    implementation(libs.library("metrox-viewmodel-compose"))
                    implementation(libs.library("jetbrains-lifecycle-runtime-compose"))
                    implementation(libs.library("jetbrains-lifecycle-viewmodel-compose"))
                }
                getByName("androidHostTest").dependencies {
                    implementation(project(":core:testing"))
                    implementation(libs.library("mockk"))
                }
            }
        }
    }
}

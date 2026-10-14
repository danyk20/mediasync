/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
import io.github.danyk20.mediasync.buildlogic.JAVA_VERSION
import io.github.danyk20.mediasync.buildlogic.configureCodeQuality
import io.github.danyk20.mediasync.buildlogic.configureKotlin
import io.github.danyk20.mediasync.buildlogic.configureTestLogging
import io.github.danyk20.mediasync.buildlogic.library
import io.github.danyk20.mediasync.buildlogic.libs
import io.github.danyk20.mediasync.buildlogic.pluginId
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

/** A pure Kotlin/JVM module: no Android dependency, tests run in milliseconds on JUnit 6 and Kotest. */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(libs.pluginId("kotlin-jvm"))

        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JAVA_VERSION
            targetCompatibility = JAVA_VERSION
        }

        dependencies {
            "testImplementation"(platform(libs.library("junit-bom")))
            "testImplementation"(libs.library("junit-jupiter"))
            "testRuntimeOnly"(libs.library("junit-platform-launcher"))
            "testImplementation"(libs.library("kotest-assertions"))
            "testImplementation"(libs.library("kotest-property"))
            "testImplementation"(libs.library("kotlinx-coroutines-test"))
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            configureTestLogging()
        }

        configureKotlin()
        configureCodeQuality(koverSourceVariant = "jvm")
    }
}

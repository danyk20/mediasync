/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
import com.android.build.api.dsl.Lint
import io.github.danyk20.mediasync.buildlogic.JAVA_VERSION
import io.github.danyk20.mediasync.buildlogic.configureCodeQuality
import io.github.danyk20.mediasync.buildlogic.configureKotlin
import io.github.danyk20.mediasync.buildlogic.configureLint
import io.github.danyk20.mediasync.buildlogic.configureTestLogging
import io.github.danyk20.mediasync.buildlogic.library
import io.github.danyk20.mediasync.buildlogic.libs
import io.github.danyk20.mediasync.buildlogic.pluginId
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

private const val ANDROID_JAVA_RELEASE = 8

/** A pure Kotlin/JVM module: no Android dependency, tests run in milliseconds on JUnit 6 and Kotest. */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(libs.pluginId("kotlin-jvm"))
        // Lets the app's lint check this module's code against Android's minSdk (checkDependencies).
        pluginManager.apply(libs.pluginId("android-lint"))
        extensions.configure<Lint> { configureLint() }

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
        // The app runs this code on Android 10 (minSdk 29), whose Java library is Java 8's. Compiling it against
        // that API turns a newer JDK method (such as `URLEncoder.encode(String, Charset)`, Android 13+) into a
        // build error instead of a crash on older phones. Tests run on the JVM and keep the current JDK.
        tasks.named<KotlinJvmCompile>("compileKotlin") {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8)
                freeCompilerArgs.add("-Xjdk-release=1.8")
            }
        }
        tasks.named<JavaCompile>("compileJava") { options.release.set(ANDROID_JAVA_RELEASE) }
        configureCodeQuality(koverSourceVariant = "jvm")
    }
}

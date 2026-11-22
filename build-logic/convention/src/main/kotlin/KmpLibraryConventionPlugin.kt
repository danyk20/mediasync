/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
import com.android.build.api.dsl.Lint
import io.github.danyk20.mediasync.buildlogic.MediaSyncKmpExtension
import io.github.danyk20.mediasync.buildlogic.configureCodeQuality
import io.github.danyk20.mediasync.buildlogic.configureDetektAllSourceSets
import io.github.danyk20.mediasync.buildlogic.configureKotlin
import io.github.danyk20.mediasync.buildlogic.configureLint
import io.github.danyk20.mediasync.buildlogic.configureTestLogging
import io.github.danyk20.mediasync.buildlogic.library
import io.github.danyk20.mediasync.buildlogic.libs
import io.github.danyk20.mediasync.buildlogic.pluginId
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

/**
 * A Kotlin Multiplatform module without Android code: shared by the Android app, the desktop app and (once a module
 * switches on [MediaSyncKmpExtension.iosTargets]) the iOS app.
 *
 * Code lives in `src/commonMain`, JVM-only code in `src/jvmMain`. Tests that use no JVM API live in `src/commonTest`
 * (`kotlin.test`, Kotest; they run on every target), the rest in `src/jvmTest` (JUnit 6 and Kotest). Test names must
 * not contain `,` or `.`: Kotlin/Native rejects them.
 * Android modules use the `jvm` target, the same way they used the plain JVM modules before.
 */
class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(libs.pluginId("kotlin-multiplatform"))
        // Lets the app's lint check this module's code against Android's minSdk (checkDependencies).
        pluginManager.apply(libs.pluginId("android-lint"))
        extensions.configure<Lint> { configureLint() }

        val kotlin = extensions.getByType(KotlinMultiplatformExtension::class.java)
        extensions.create<MediaSyncKmpExtension>("mediasync", kotlin)
        kotlin.apply {
            jvm()
            sourceSets.named("commonTest") {
                dependencies {
                    implementation(libs.library("kotlin-test"))
                    implementation(libs.library("kotest-assertions"))
                    implementation(libs.library("kotest-property"))
                    implementation(libs.library("kotlinx-coroutines-test"))
                }
            }
            sourceSets.named("jvmTest") {
                dependencies {
                    implementation(project.dependencies.platform(libs.library("junit-bom")))
                    implementation(libs.library("junit-jupiter"))
                    runtimeOnly(libs.library("junit-platform-launcher"))
                    implementation(libs.library("kotest-assertions"))
                    implementation(libs.library("kotest-property"))
                    implementation(libs.library("kotlinx-coroutines-test"))
                }
            }
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            configureTestLogging()
        }

        configureKotlin()
        // After configureKotlin, which sets every compilation to Java 17. The app runs this code on Android 10
        // (minSdk 29), whose Java library is Java 8's: a newer JDK method becomes a build error instead of a crash on
        // older phones. Tests run on the JVM and keep the current JDK.
        tasks.named<KotlinJvmCompile>("compileKotlinJvm") {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8)
                freeCompilerArgs.add("-Xjdk-release=1.8")
            }
        }
        configureCodeQuality(koverSourceVariant = "jvm")
        configureDetektAllSourceSets()
    }
}

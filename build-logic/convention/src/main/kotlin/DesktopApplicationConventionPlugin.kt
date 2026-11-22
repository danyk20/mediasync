/*
 * Copyright 2026 The MediaSync Authors
 * SPDX-License-Identifier: Apache-2.0
 */
import app.cash.licensee.LicenseeExtension
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

/**
 * The desktop app (docs/PLAN.md §9, M8): a Kotlin/JVM Compose Desktop application on Java 17 or newer (Compose's
 * desktop artifacts need it), with Metro, tests on JUnit 6 and the license check of everything it ships.
 */
class DesktopApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(libs.pluginId("kotlin-jvm"))
        pluginManager.apply(libs.pluginId("kotlin-compose"))
        pluginManager.apply(libs.pluginId("compose-multiplatform"))
        pluginManager.apply(libs.pluginId("mediasync-di"))
        pluginManager.apply(libs.pluginId("licensee"))

        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JAVA_VERSION
            targetCompatibility = JAVA_VERSION
        }

        dependencies {
            "testImplementation"(platform(libs.library("junit-bom")))
            "testImplementation"(libs.library("junit-jupiter"))
            "testRuntimeOnly"(libs.library("junit-platform-launcher"))
            "testImplementation"(libs.library("kotest-assertions"))
            "testImplementation"(libs.library("kotlinx-coroutines-test"))
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            configureTestLogging()
        }

        // The same list as :platform:desktop (the desktop app ships its classpath), plus what Compose Desktop and the
        // shared screens bring.
        extensions.configure<LicenseeExtension> {
            allow("Apache-2.0") // also one of JNA's two licenses (the other is LGPL-2.1, not allowed)
            allow("MIT") // secret-service, slf4j
            allow("BSD-2-Clause") // jkeychain (macOS Keychain)
            allow("BSD-3-Clause") // protobuf, bundled by DataStore
            allowUrl("https://www.bouncycastle.org/licence.html") {
                because("The Bouncy Castle Licence is the MIT license")
            }
            allowUrl("https://github.com/hypfvieh/dbus-java/blob/master/LICENSE") {
                because("dbus-java (Linux Secret Service) is MIT")
            }
            allowDependency("com.github.javakeyring", "java-keyring", "1.0.4") {
                because("Its pom names the license only 'BSD-3'; the project's LICENSE is BSD-3-Clause")
            }
        }

        configureKotlin()
        configureCodeQuality(koverSourceVariant = "jvm")
    }
}

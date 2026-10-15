import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.mediasync.jvm.library)
}

// The kit is test code that every connector module's tests extend (docs/PLAN.md §9.5): it never ships in the app.
dependencies {
    api(projects.connector.api)
    // JUnit 4, so connectors in Android modules run it with Robolectric like their other JVM tests.
    api(libs.junit4)
    api(libs.kotest.assertions)
    api(libs.kotlinx.coroutines.test)
    // Sample photos and videos with a date and a place inside: the golden media set (docs/PLAN.md §6.5).
    implementation(testFixtures(projects.core.metadata))

    testImplementation(testFixtures(projects.connector.api))
}

// Never on a phone, so compiled like tests rather than for Android's Java 8 API: Kotest's assertions, which the kit
// is made of, inline code built for newer JVMs.
tasks.named<KotlinJvmCompile>("compileKotlin") {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.set(freeCompilerArgs.get().filterNot { it.startsWith("-Xjdk-release") })
    }
}
tasks.named<JavaCompile>("compileJava") { options.release.set(JavaVersion.VERSION_17.majorVersion.toInt()) }

// Part of what decides that a connector is safe to use: held to the stricter bar.
kover {
    reports {
        variant("ci") {
            verify {
                rule("connector:contract-tests line coverage") {
                    minBound(85)
                }
            }
        }
    }
}

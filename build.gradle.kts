plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.licensee) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.kover)
    alias(libs.plugins.spotless)
}

// Modules format themselves (build-logic convention); this covers the root scripts and build-logic.
spotless {
    kotlin {
        target("build-logic/**/*.kt")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get())
        licenseHeader("/*\n * Copyright \$YEAR The MediaSync Authors\n * SPDX-License-Identifier: Apache-2.0\n */\n")
    }
    kotlinGradle {
        target("*.kts", "build-logic/**/*.kts")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get())
    }
}

// Aggregated coverage over every module: `./gradlew :koverXmlReportCi :koverVerifyCi`.
dependencies {
    kover(projects.app)
    kover(projects.core.datastore)
    kover(projects.core.designsystem)
    kover(projects.core.model)
    kover(projects.feature.consumer)
    kover(projects.feature.onboarding)
    kover(projects.feature.producer)
    kover(projects.sync.engine)
    kover(projects.sync.protocol)
    kover(projects.transport.lan)
}

kover {
    currentProject {
        createVariant("ci") {}
    }
    reports {
        filters {
            excludes {
                classes(
                    "*.BuildConfig",
                    "*.ComposableSingletons*",
                    "*_Factory",
                    "*_Factory\$*",
                    "*_HiltModules*",
                    "*_MembersInjector",
                    "*Hilt_*",
                    "*.MainActivity",
                    "*.MediaSyncApplication",
                    "dagger.hilt.*",
                    "hilt_aggregated_deps.*",
                )
                annotatedBy("androidx.compose.ui.tooling.preview.Preview", "dagger.Module")
            }
        }
        variant("ci") {
            verify {
                rule("Overall line coverage") {
                    minBound(70)
                }
            }
        }
    }
}

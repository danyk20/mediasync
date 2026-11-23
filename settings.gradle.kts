pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

apply(from = "gradle/patched-build-libraries.settings.gradle.kts")

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "mediasync"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")
include(":connector:android")
include(":connector:api")
include(":connector:api-testing")
include(":connector:contract-tests")
include(":connector:folder")
include(":connector:google-photos-app")
include(":core:crypto")
include(":core:database")
include(":core:datastore")
include(":core:diagnostics")
include(":core:designsystem")
include(":core:media")
include(":core:metadata")
include(":core:metadata-testing")
include(":core:model")
include(":core:testing")
include(":core:util")
include(":desktopApp")
include(":feature:backup")
include(":feature:consumer")
include(":feature:diagnostics")
include(":feature:navigation")
include(":feature:onboarding")
include(":feature:pairing")
include(":feature:quickaccess")
include(":feature:producer")
include(":feature:share")
include(":platform:desktop")
include(":sync:android")
include(":sync:engine")
include(":sync:engine-testing")
include(":sync:protocol")
include(":sync:runtime")
include(":transport:api")
include(":transport:lan")
include(":transport:lan-testing")
include(":transport:rendezvous")
include(":transport:rendezvous-testing")
include(":transport:webrtc")

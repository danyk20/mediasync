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
include(":connector:contract-tests")
include(":core:crypto")
include(":core:database")
include(":core:datastore")
include(":core:diagnostics")
include(":core:designsystem")
include(":core:media")
include(":core:metadata")
include(":core:model")
include(":core:testing")
include(":feature:consumer")
include(":feature:diagnostics")
include(":feature:onboarding")
include(":feature:pairing")
include(":feature:producer")
include(":sync:android")
include(":sync:engine")
include(":sync:protocol")
include(":transport:api")
include(":transport:lan")

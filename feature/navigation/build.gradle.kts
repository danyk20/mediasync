plugins {
    alias(libs.plugins.mediasync.kmp.feature)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "io.github.danyk20.mediasync.feature.navigation"
    }
    sourceSets {
        commonMain.dependencies {
            // The screens each destination shows.
            implementation(projects.feature.backup)
            implementation(projects.feature.consumer)
            implementation(projects.feature.diagnostics)
            implementation(projects.feature.onboarding)
            implementation(projects.feature.pairing)
            implementation(projects.feature.producer)
            implementation(libs.jetbrains.navigation3.ui)
            implementation(libs.jetbrains.lifecycle.viewmodel.navigation3)
            implementation(libs.kotlinx.serialization.core)
        }
        androidHostTest.dependencies {
            // The guard test lists every Destination subclass by reflection.
            implementation(libs.kotlin.reflect)
        }
    }
}

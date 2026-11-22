plugins {
    alias(libs.plugins.mediasync.kmp.feature)
}

kotlin {
    android {
        namespace = "io.github.danyk20.mediasync.feature.diagnostics"
    }
    sourceSets {
        commonMain.dependencies {
            // DiagnosticsReport's Fact, ProducerSync, TestFileState, ... live here, in package sync.android.
            implementation(projects.sync.runtime)
            implementation(projects.core.datastore)
            implementation(projects.core.database)
            implementation(projects.core.diagnostics)
        }
        androidMain.dependencies {
            // The Android side of DiagnosticsDevice: DiagnosticsReport and TestFiles.
            implementation(projects.sync.android)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
        }
        androidHostTest.dependencies {
            implementation(projects.sync.android)
            implementation(libs.androidx.test.core)
        }
    }
}

compose.resources {
    packageOfResClass = "io.github.danyk20.mediasync.feature.diagnostics.generated.resources"
}

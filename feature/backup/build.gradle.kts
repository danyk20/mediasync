plugins {
    alias(libs.plugins.mediasync.kmp.feature)
}

kotlin {
    android {
        namespace = "io.github.danyk20.mediasync.feature.backup"
    }
    sourceSets {
        commonMain.dependencies {
            // ConnectorId, DeviceAppProfile, BackupOverview, ... live here (BackupOverview in package connector.android).
            implementation(projects.connector.api)
        }
        androidMain.dependencies {
            // The Android side of BackupDevice: CloudBackup and the package manager.
            implementation(projects.connector.android)
            implementation(libs.androidx.core.ktx)
        }
        androidHostTest.dependencies {
            implementation(projects.connector.android)
            implementation(libs.androidx.test.core)
            implementation(project.dependencies.testFixtures(projects.connector.api))
        }
    }
}

compose.resources {
    packageOfResClass = "io.github.danyk20.mediasync.feature.backup.generated.resources"
}

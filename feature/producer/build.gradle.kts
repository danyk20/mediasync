plugins {
    alias(libs.plugins.mediasync.kmp.feature)
}

kotlin {
    android {
        namespace = "io.github.danyk20.mediasync.feature.producer"
    }
    sourceSets {
        commonMain.dependencies {
            // ProducerSync, LeftOutItems, SyncRecord, ... live here, in package sync.android.
            implementation(projects.sync.runtime)
            implementation(projects.core.datastore)
            implementation(projects.core.database)
        }
        androidMain.dependencies {
            // The Android side of ProducerDevice: the work scheduler, the sync history, permissions.
            implementation(projects.sync.android)
            implementation(projects.core.media)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
        }
        androidHostTest.dependencies {
            implementation(projects.sync.android)
            implementation(projects.core.media)
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.test.core)
            implementation(project.dependencies.testFixtures(projects.sync.engine))
        }
    }
}

compose.resources {
    packageOfResClass = "io.github.danyk20.mediasync.feature.producer.generated.resources"
}

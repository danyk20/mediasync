plugins {
    alias(libs.plugins.mediasync.kmp.feature)
}

kotlin {
    android {
        namespace = "io.github.danyk20.mediasync.feature.pairing"
    }
    sourceSets {
        commonMain.dependencies {
            // The pairing and sync types (ConsumerReceiver, ProducerSync, ...) live here, in package sync.android.
            implementation(projects.sync.runtime)
            implementation(libs.zxing.core)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.compose)
        }
    }
}

compose.resources {
    packageOfResClass = "io.github.danyk20.mediasync.feature.pairing.generated.resources"
}

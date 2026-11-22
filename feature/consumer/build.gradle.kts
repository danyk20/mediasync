plugins {
    alias(libs.plugins.mediasync.kmp.feature)
}

kotlin {
    android {
        namespace = "io.github.danyk20.mediasync.feature.consumer"
    }
    sourceSets {
        commonMain.dependencies {
            // ConsumerReceiver, RelayUsage, SetupResult, ... live here, in package sync.android.
            implementation(projects.sync.runtime)
            implementation(projects.core.datastore)
            implementation(projects.core.database)
            implementation(projects.core.diagnostics)
            implementation(projects.transport.rendezvous)
        }
        androidMain.dependencies {
            // The Android side of ConsumerDevice and InternetSetup, and the Cloudflare sign-in's redirect activity.
            implementation(projects.sync.android)
            implementation(projects.connector.android)
            implementation(projects.core.media)
            implementation(libs.metrox.android)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.browser)
            implementation(libs.androidx.core.ktx)
        }
        androidHostTest.dependencies {
            implementation(projects.sync.android)
            implementation(projects.connector.android)
            implementation(projects.core.media)
            implementation(project.dependencies.testFixtures(projects.connector.api))
        }
    }
}

compose.resources {
    packageOfResClass = "io.github.danyk20.mediasync.feature.consumer.generated.resources"
}

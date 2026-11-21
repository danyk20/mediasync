plugins {
    alias(libs.plugins.mediasync.kmp.feature)
}

kotlin {
    android {
        namespace = "io.github.danyk20.mediasync.feature.onboarding"
    }
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.datastore)
        }
    }
}

compose.resources {
    packageOfResClass = "io.github.danyk20.mediasync.feature.onboarding.generated.resources"
}

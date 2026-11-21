plugins {
    alias(libs.plugins.mediasync.kmp.compose)
}

kotlin {
    android {
        namespace = "io.github.danyk20.mediasync.core.designsystem"
    }
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
        }
        getByName("androidHostTest").dependencies {
            implementation(projects.core.testing)
        }
    }
}

compose.resources {
    packageOfResClass = "io.github.danyk20.mediasync.core.designsystem.generated.resources"
    publicResClass = true
}

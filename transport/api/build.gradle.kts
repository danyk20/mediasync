plugins {
    alias(libs.plugins.mediasync.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(libs.okio)
        }
    }
}

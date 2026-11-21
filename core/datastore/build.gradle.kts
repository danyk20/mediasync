plugins {
    alias(libs.plugins.mediasync.kmp.library)
    alias(libs.plugins.mediasync.di)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(libs.kotlinx.coroutines.core)
            // DataStore<Preferences> is in the repository's constructor; Path is in the factory's signature.
            api(libs.androidx.datastore.preferences.core)
            api(libs.okio)
        }
    }
}

plugins {
    alias(libs.plugins.mediasync.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.sync.engine)
            // The fakes guard their state with the shared Lock.
            implementation(projects.core.util)
            api(libs.okio)
            api(libs.kotlinx.coroutines.core)
        }
    }
}

// In-memory fakes shared with the transport integration tests and the engine's session tests on every target.
// Test code: never ships in the app.
mediasync { iosTargets() }

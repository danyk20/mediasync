plugins {
    alias(libs.plugins.mediasync.kmp.library)
}

kotlin {
    // Lock is an expect class, which the compiler still marks Beta (warnings are errors here).
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
        // The fakes guard their state with the engine's internal Lock (EngineInternalApi): test support only.
        optIn.add("io.github.danyk20.mediasync.sync.engine.EngineInternalApi")
    }
    sourceSets {
        commonMain.dependencies {
            api(projects.sync.engine)
            api(libs.okio)
            api(libs.kotlinx.coroutines.core)
        }
    }
}

// In-memory fakes shared with the transport integration tests and the engine's session tests on every target.
// Test code: never ships in the app.
mediasync { iosTargets() }

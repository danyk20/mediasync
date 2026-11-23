plugins {
    alias(libs.plugins.mediasync.kmp.library)
}

kotlin {
    // Lock is an expect class, which the compiler still marks Beta (warnings are errors here).
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    sourceSets {
        commonMain.dependencies {
            // ioDispatcher is a CoroutineDispatcher in the public API.
            api(libs.kotlinx.coroutines.core)
        }
    }
}

mediasync { iosTargets() }

plugins {
    alias(libs.plugins.mediasync.kmp.library)
}

kotlin {
    // Lock is an expect class, which the compiler still marks Beta (warnings are errors here).
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
        // The engine's own code uses its internal Lock; sync:engine-testing uses it too (EngineInternalApi).
        optIn.add("io.github.danyk20.mediasync.sync.engine.EngineInternalApi")
    }
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(projects.sync.protocol)
            api(projects.transport.api)
            // The engine's API exposes ByteString, Source and BufferedSource: a direct dependency, not a transitive one.
            api(libs.okio)
            // The sessions run their reader and sender work on coroutines (Dispatchers.IO).
            api(libs.kotlinx.coroutines.core)
            implementation(projects.core.metadata)
            implementation(projects.core.diagnostics)
        }
        commonTest.dependencies {
            // In-memory fakes: the sessions' tests run on every target, so they use the common fakes.
            implementation(projects.sync.engineTesting)
        }
        jvmTest.dependencies {
            implementation(projects.core.metadataTesting)
        }
    }
}

mediasync { iosTargets() }

// Sync logic decides what is sent and stored: held to a stricter bar than the project-wide 70 %.
kover {
    reports {
        variant("ci") {
            verify {
                rule("sync:engine line coverage") {
                    minBound(85)
                }
            }
        }
    }
}

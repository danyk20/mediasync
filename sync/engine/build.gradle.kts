plugins {
    alias(libs.plugins.mediasync.kmp.library)
}

kotlin {
    // Lock is an expect class, which the compiler still marks Beta (warnings are errors here). Only this module uses it.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(projects.sync.protocol)
            api(projects.transport.api)
            // The engine's API exposes ByteString, Source and BufferedSource: a direct dependency, not a transitive one.
            api(libs.okio)
        }
        jvmMain.dependencies {
            // Used only by ProducerSession and ParallelSync, which stay on the JVM until they're converted.
            implementation(projects.core.metadata)
            implementation(projects.core.diagnostics)
        }
        jvmTest.dependencies {
            implementation(projects.core.metadataTesting)
            // In-memory fakes shared with the transport integration tests.
            implementation(projects.sync.engineTesting)
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

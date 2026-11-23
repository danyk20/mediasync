plugins {
    alias(libs.plugins.mediasync.kmp.library)
}

kotlin {
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
            // The lock, the I/O dispatcher and the clocks: shared with the runtime and the fakes.
            implementation(projects.core.util)
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

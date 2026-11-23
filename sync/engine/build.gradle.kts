plugins {
    alias(libs.plugins.mediasync.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(projects.sync.protocol)
            api(projects.transport.api)
        }
        jvmMain.dependencies {
            // Plain-JVM or JVM-only for now: they move to commonMain with the PRs that convert them.
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

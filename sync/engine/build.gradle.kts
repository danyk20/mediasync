plugins {
    alias(libs.plugins.mediasync.jvm.library)
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.metadata)
    implementation(projects.core.diagnostics)
    api(projects.sync.protocol)
    api(projects.transport.api)

    testImplementation(projects.core.metadataTesting)
    // In-memory fakes shared with the transport integration tests.
    testImplementation(projects.sync.engineTesting)
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

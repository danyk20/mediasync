plugins {
    alias(libs.plugins.mediasync.jvm.library)
    `java-test-fixtures`
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.metadata)
    api(projects.sync.protocol)
    api(projects.transport.api)

    // In-memory fakes shared with the transport integration tests.
    testFixturesApi(libs.okio)
    testImplementation(testFixtures(projects.core.metadata))
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

plugins {
    alias(libs.plugins.mediasync.jvm.library)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(projects.core.diagnostics)

    // In-memory connectors and ledger, shared with the contract-test kit and the connector modules' tests.
    testImplementation(projects.connector.apiTesting)
}

// Decides what counts as backed up, and so what may be removed from the phone: held to the stricter bar.
kover {
    reports {
        variant("ci") {
            verify {
                rule("connector:api line coverage") {
                    minBound(85)
                }
            }
        }
    }
}

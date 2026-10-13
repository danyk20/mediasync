plugins {
    alias(libs.plugins.mediasync.jvm.library)
    `java-test-fixtures`
}

dependencies {
    api(projects.transport.api)

    // Test-only: generates the self-signed identity certificates Android Keystore makes on devices.
    testFixturesImplementation(libs.bouncycastle.bcpkix)
    testImplementation(testFixtures(projects.sync.engine))
}

// Authentication and encryption code: held to the same stricter bar as the sync modules.
kover {
    reports {
        variant("ci") {
            verify {
                rule("transport:lan line coverage") {
                    minBound(85)
                }
            }
        }
    }
}

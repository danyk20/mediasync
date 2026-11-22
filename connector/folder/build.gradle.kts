plugins {
    alias(libs.plugins.mediasync.jvm.library)
}

// A plain folder, on this computer or a NAS share: the desktop app's first backup target (docs/PLAN.md §9, M8).
// Pure Java file APIs, so no Android and no Metro here: the desktop app registers the factory in its own graph.
dependencies {
    api(projects.connector.api)

    testImplementation(projects.connector.contractTests)
    testImplementation(testFixtures(projects.connector.api))
}

// The contract-test kit is JUnit 4, so the tests of this module (kit and own) run on JUnit 4.
tasks.withType<Test>().configureEach { useJUnit() }

// Writes the only copy a user may then delete from the phone: held to the stricter bar.
kover {
    reports {
        variant("ci") {
            verify {
                rule("connector:folder line coverage") {
                    minBound(85)
                }
            }
        }
    }
}

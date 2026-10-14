plugins {
    alias(libs.plugins.mediasync.jvm.library)
    `java-test-fixtures`
}

dependencies {
    api(projects.core.model)

    // An independent reader: removed details must not be found by anything but our own writer (docs/PLAN.md §6.5).
    testImplementation(libs.metadata.extractor)
    // The golden media set (golden/make_golden.py) is in testFixtures, shared with the sync engine's tests.
}

// Decides what leaves the phone: held to a stricter bar than the project-wide 70 %.
kover {
    reports {
        variant("ci") {
            verify {
                rule("core:metadata line coverage") {
                    minBound(85)
                }
            }
        }
    }
}

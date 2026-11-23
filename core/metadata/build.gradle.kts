plugins {
    alias(libs.plugins.mediasync.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            // Redaction.Edited.open takes and returns an Okio Source; zlib for PNG text chunks.
            api(libs.okio)
        }
        jvmTest.dependencies {
            // An independent reader: removed details must not be found by anything but our own writer (docs/PLAN.md §6.5).
            implementation(libs.metadata.extractor)
            // The golden media set (core/metadata/golden/make_golden.py) is in :core:metadata-testing, shared with the sync engine's tests.
            implementation(projects.core.metadataTesting)
        }
    }
}

mediasync { iosTargets() }

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

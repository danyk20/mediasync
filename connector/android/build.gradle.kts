plugins {
    alias(libs.plugins.mediasync.android.library)
    alias(libs.plugins.mediasync.hilt)
}

android {
    namespace = "io.github.danyk20.mediasync.connector.android"
}

dependencies {
    api(projects.connector.api)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.core.diagnostics)
    implementation(projects.core.model)
    implementation(libs.androidx.core.ktx)

    testImplementation(testFixtures(projects.connector.api))
    testImplementation(projects.core.testing)
    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
}

// Keeps the backup ledger: held to the same bar as the pure connector code. Code Hilt generates doesn't count, as in
// the project-wide report.
kover {
    reports {
        filters {
            excludes {
                classes("*_Factory", "*_Provide*Factory", "*HiltWrapper_*", "hilt_aggregated_deps.*")
                annotatedBy("dagger.Module")
            }
        }
        variant("ci") {
            verify {
                rule("connector:android line coverage") {
                    minBound(85)
                }
            }
        }
    }
}

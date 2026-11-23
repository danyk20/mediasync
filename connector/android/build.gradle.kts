plugins {
    alias(libs.plugins.mediasync.android.library)
    alias(libs.plugins.mediasync.di)
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

    testImplementation(projects.connector.apiTesting)
    testImplementation(projects.core.testing)
    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
}

// Keeps the backup ledger: held to the same bar as the pure connector code. Code Metro generates doesn't count, as in
// the project-wide report.
kover {
    reports {
        filters {
            excludes {
                classes("*MetroFactory*", "*\$\$Metro*", "metro.hints.*")
                annotatedBy("dev.zacsweers.metro.BindingContainer")
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

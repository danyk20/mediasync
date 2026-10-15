plugins {
    alias(libs.plugins.mediasync.android.feature)
}

android {
    namespace = "io.github.danyk20.mediasync.feature.consumer"
}

dependencies {
    implementation(projects.connector.android)
    implementation(projects.core.datastore)
    implementation(projects.sync.android)

    testImplementation(testFixtures(projects.connector.api))
}

plugins {
    alias(libs.plugins.mediasync.android.feature)
}

android {
    namespace = "io.github.danyk20.mediasync.feature.backup"
}

dependencies {
    implementation(projects.connector.android)

    testImplementation(testFixtures(projects.connector.api))
}

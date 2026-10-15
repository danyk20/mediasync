plugins {
    alias(libs.plugins.mediasync.android.feature)
}

android {
    namespace = "io.github.danyk20.mediasync.feature.share"
}

dependencies {
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.core.media)
    implementation(projects.sync.android)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.androidx.test.core)
}

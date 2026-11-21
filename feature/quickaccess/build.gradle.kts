plugins {
    alias(libs.plugins.mediasync.android.library)
    alias(libs.plugins.mediasync.di)
}

android {
    namespace = "io.github.danyk20.mediasync.feature.quickaccess"
}

dependencies {
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.core.model)
    implementation(projects.sync.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.metrox.android)

    testImplementation(projects.core.testing)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

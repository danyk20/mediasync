plugins {
    alias(libs.plugins.mediasync.android.library)
    alias(libs.plugins.mediasync.hilt)
}

android {
    namespace = "io.github.danyk20.mediasync.core.media"
}

dependencies {
    api(projects.sync.engine)
    implementation(projects.core.database)
    implementation(projects.core.metadata)
    implementation(projects.core.datastore)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(testFixtures(projects.core.metadata))

    androidTestImplementation(testFixtures(projects.core.metadata))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.room.runtime)
    androidTestImplementation(libs.kotest.assertions)
}

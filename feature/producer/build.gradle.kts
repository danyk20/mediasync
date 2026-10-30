plugins {
    alias(libs.plugins.mediasync.android.feature)
}

android {
    namespace = "io.github.danyk20.mediasync.feature.producer"
}

dependencies {
    implementation(projects.sync.android)
    implementation(projects.core.media)
    implementation(projects.core.datastore)

    testImplementation(libs.androidx.room.runtime)
    testImplementation(testFixtures(projects.sync.engine))
}

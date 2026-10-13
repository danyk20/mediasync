plugins {
    alias(libs.plugins.mediasync.android.library)
    alias(libs.plugins.mediasync.hilt)
}

android {
    namespace = "io.github.danyk20.mediasync.sync.android"
}

dependencies {
    api(projects.sync.engine)
    api(projects.core.database)
    implementation(projects.core.crypto)
    implementation(projects.core.media)
    implementation(projects.transport.lan)

    testImplementation(testFixtures(projects.sync.engine))
    testImplementation(testFixtures(projects.transport.lan))
    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotest.assertions)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

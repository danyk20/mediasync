plugins {
    alias(libs.plugins.mediasync.android.library)
    alias(libs.plugins.mediasync.hilt)
}

android {
    namespace = "io.github.danyk20.mediasync.core.crypto"
}

dependencies {
    api(projects.transport.lan)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotest.assertions)
}

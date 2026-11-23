plugins {
    alias(libs.plugins.mediasync.android.library)
}

android {
    namespace = "io.github.danyk20.mediasync.transport.webrtc"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    api(projects.transport.rendezvous)
    implementation(libs.webrtc)

    androidTestImplementation(projects.sync.engineTesting)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotest.assertions)
}

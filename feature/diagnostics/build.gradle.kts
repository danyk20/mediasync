plugins {
    alias(libs.plugins.mediasync.android.feature)
}

android {
    namespace = "io.github.danyk20.mediasync.feature.diagnostics"
}

dependencies {
    implementation(projects.sync.android)
    implementation(libs.androidx.core.ktx)
}

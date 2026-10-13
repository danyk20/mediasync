plugins {
    alias(libs.plugins.mediasync.android.library)
    alias(libs.plugins.mediasync.android.compose)
}

android {
    namespace = "io.github.danyk20.mediasync.core.designsystem"
}

dependencies {
    testImplementation(projects.core.testing)
}

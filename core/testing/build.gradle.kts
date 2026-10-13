plugins {
    alias(libs.plugins.mediasync.android.library)
}

android {
    namespace = "io.github.danyk20.mediasync.core.testing"
}

dependencies {
    api(projects.core.datastore)
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui.test.junit4)
    api(libs.junit4)
    api(libs.kotlinx.coroutines.test)
    api(libs.roborazzi)
    api(libs.roborazzi.compose)
}

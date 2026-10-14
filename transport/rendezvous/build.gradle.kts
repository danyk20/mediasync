plugins {
    alias(libs.plugins.mediasync.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(projects.core.model)
    implementation(libs.okio)
    implementation(libs.kotlinx.serialization.json)
}

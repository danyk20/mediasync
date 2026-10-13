plugins {
    alias(libs.plugins.mediasync.jvm.library)
}

dependencies {
    api(projects.core.model)
    api(libs.okio)
}

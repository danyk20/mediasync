plugins {
    alias(libs.plugins.mediasync.jvm.library)
}

// In-memory fakes shared with the transport integration tests. Test code: never ships in the app.
dependencies {
    api(projects.sync.engine)
    api(libs.okio)
}

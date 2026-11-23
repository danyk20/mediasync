plugins {
    alias(libs.plugins.mediasync.jvm.library)
}

// A fake Worker, shared with the device tests of internet sync. Test code: never ships in the app.
dependencies {
    api(projects.transport.rendezvous)
    api(libs.okhttp.mockwebserver)
}

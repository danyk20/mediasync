plugins {
    alias(libs.plugins.mediasync.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(projects.core.model)
    api(projects.transport.api)
    implementation(libs.okio)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(projects.sync.engineTesting)
    // A fake Worker, shared with the device tests of internet sync.
    testImplementation(projects.transport.rendezvousTesting)
}

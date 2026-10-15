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

    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(testFixtures(projects.sync.engine))
}

plugins {
    alias(libs.plugins.mediasync.jvm.library)
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
}

dependencies {
    api(projects.core.model)
    api(projects.transport.api)
    implementation(libs.okio)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    // A fake Worker, shared with the device tests of internet sync.
    testFixturesApi(libs.okhttp.mockwebserver)
    testImplementation(testFixtures(projects.sync.engine))
}

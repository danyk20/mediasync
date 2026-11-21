plugins {
    alias(libs.plugins.mediasync.jvm.library)
}

dependencies {
    api(projects.sync.engine)
    api(projects.core.model)
    api(projects.transport.api)
    api(libs.kotlinx.coroutines.core)
    api(libs.okio)

    testImplementation(testFixtures(projects.sync.engine))
}

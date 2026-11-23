plugins {
    alias(libs.plugins.mediasync.jvm.library)
}

dependencies {
    api(projects.sync.engine)
    api(projects.core.model)
    api(projects.transport.api)
    api(projects.transport.lan)
    api(libs.kotlinx.coroutines.core)
    api(libs.okio)
    implementation(projects.core.diagnostics)
    implementation(projects.core.metadata)

    testImplementation(projects.sync.engineTesting)
}

plugins {
    alias(libs.plugins.mediasync.android.library)
    alias(libs.plugins.mediasync.di)
}

android {
    namespace = "io.github.danyk20.mediasync.sync.android"
}

// The migration tests read the exported schemas as assets, on the JVM too.
androidComponents {
    onVariants { variant ->
        variant.hostTests.values.forEach {
            it.sources.assets?.addStaticSourceDirectory("${rootProject.projectDir}/core/database/schemas")
        }
    }
}

dependencies {
    api(projects.sync.runtime)
    api(projects.sync.engine)
    api(projects.core.diagnostics)
    api(projects.core.database)
    implementation(projects.core.crypto)
    implementation(projects.core.metadata)
    implementation(projects.core.datastore)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.metrox.android)
    implementation(projects.core.media)
    implementation(projects.transport.lan)
    implementation(projects.transport.webrtc)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    testImplementation(projects.sync.engineTesting)
    testImplementation(projects.core.metadataTesting)
    testImplementation(projects.transport.lanTesting)
    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.mockk)
    testImplementation(projects.core.testing)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotest.assertions)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(projects.sync.engineTesting)
    androidTestImplementation(projects.transport.rendezvousTesting)
}

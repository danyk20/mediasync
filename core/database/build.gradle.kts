plugins {
    alias(libs.plugins.mediasync.android.library)
    alias(libs.plugins.mediasync.hilt)
    alias(libs.plugins.room)
}

android {
    namespace = "io.github.danyk20.mediasync.core.database"
}

// The migration tests read the exported schemas as assets, on the JVM too.
androidComponents {
    onVariants { variant ->
        variant.hostTests.values.forEach { it.sources.assets?.addStaticSourceDirectory("schemas") }
    }
}

room {
    // Exported schemas are committed; MigrationTestHelper checks every migration against them.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(projects.sync.engine)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation(testFixtures(projects.sync.engine))
}

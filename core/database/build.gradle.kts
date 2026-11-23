plugins {
    alias(libs.plugins.mediasync.kmp.library)
    alias(libs.plugins.mediasync.di)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.androidx.room.runtime)
        }
        jvmMain.dependencies {
            api(projects.sync.engine)
            api(projects.sync.runtime)
        }
        jvmTest.dependencies {
            implementation(libs.androidx.room.testing)
            implementation(libs.androidx.sqlite.bundled)
            implementation(projects.sync.engineTesting)
        }
    }
}

dependencies {
    add("kspJvm", libs.androidx.room.compiler)
}

room {
    // Exported schemas are committed; MigrationTestHelper checks every migration against them.
    schemaDirectory("$projectDir/schemas")
}

// Room's generated DAO implementations call Room's own restricted API, as they do in every Room module.
extensions.configure<com.android.build.api.dsl.Lint> {
    disable += "RestrictedApi"
}

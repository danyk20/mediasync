plugins {
    alias(libs.plugins.mediasync.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.sync.engine)
            api(projects.core.model)
            api(projects.transport.api)
            api(libs.kotlinx.coroutines.core)
            api(libs.okio)
            implementation(projects.core.diagnostics)
            implementation(projects.core.metadata)
            implementation(projects.core.util)
        }
        jvmMain.dependencies {
            // The receiving side and the JVM ports use LanServer/LanClient/IdentityProvider; modules that import
            // transport.lan through the runtime keep it.
            api(projects.transport.lan)
        }
        commonTest.dependencies {
            implementation(projects.sync.engineTesting)
        }
    }
}

mediasync { iosTargets() }

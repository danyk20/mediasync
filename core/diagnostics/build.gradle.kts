plugins {
    alias(libs.plugins.mediasync.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // FileDiagnosticLog takes an Okio FileSystem/Path, a kotlin.time.Clock and a kotlinx-datetime TimeZone.
            api(libs.okio)
            api(libs.kotlinx.datetime)
        }
        iosMain.dependencies {
            // The iOS writer: a coroutine behind a Channel (the JVM one is a plain executor).
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

mediasync { iosTargets() }

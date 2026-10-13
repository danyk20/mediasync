plugins {
    alias(libs.plugins.mediasync.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.okio)
    implementation(libs.kotlinx.serialization.protobuf)
}

// The wire protocol is core sync logic: held to a stricter bar than the project-wide 70 %.
kover {
    reports {
        variant("ci") {
            verify {
                rule("sync:protocol line coverage") {
                    minBound(85)
                }
            }
        }
    }
}

plugins {
    alias(libs.plugins.mediasync.android.application)
    alias(libs.plugins.mediasync.android.compose)
    alias(libs.plugins.mediasync.hilt)
    alias(libs.plugins.kotlin.serialization)
}

// Single source of truth for the version: version.txt, maintained by release-please.
val appVersion: String = providers
    .fileContents(rootProject.layout.projectDirectory.file("version.txt"))
    .asText.get().trim()

/** 1.2.3 -> 1_002_003. Always at least 1, which Android requires. */
fun versionCodeOf(version: String): Int {
    val (major, minor, patch) = version.split(".").map { it.toInt() }
    return (major * 1_000_000 + minor * 1_000 + patch).coerceAtLeast(1)
}

// Release signing comes from CI secrets (docs/RELEASING.md). Without them, release builds stay unsigned.
val releaseKeystore = providers.environmentVariable("MEDIASYNC_KEYSTORE_FILE")

android {
    namespace = "io.github.danyk20.mediasync"

    defaultConfig {
        applicationId = "io.github.danyk20.mediasync"
        versionName = appVersion
        versionCode = versionCodeOf(appVersion)
    }

    signingConfigs {
        if (releaseKeystore.isPresent) {
            create("release") {
                storeFile = file(releaseKeystore.get())
                storePassword = providers.environmentVariable("MEDIASYNC_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("MEDIASYNC_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("MEDIASYNC_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
}

dependencies {
    implementation(projects.core.datastore)
    implementation(projects.core.designsystem)
    implementation(projects.core.model)
    implementation(projects.feature.consumer)
    implementation(projects.feature.onboarding)
    implementation(projects.feature.producer)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.kotlinx.serialization.core)

    testImplementation(projects.core.testing)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.turbine)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestUtil(libs.androidx.test.orchestrator)
}

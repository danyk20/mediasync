plugins {
    alias(libs.plugins.mediasync.android.application)
    alias(libs.plugins.mediasync.android.compose)
    alias(libs.plugins.mediasync.hilt)
    alias(libs.plugins.kotlin.serialization)
}

// Firebase, for waking the receiving phone over the internet (docs/PLAN.md §10.4), when the project's
// google-services.json is there; a build without it works the same, without wake-ups.
if (file("google-services.json").exists()) {
    pluginManager.apply(libs.plugins.google.services.get().pluginId)
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

    // WebRTC brings native code for every phone CPU, about 12 MB each. Play's App Bundle gives each phone its own;
    // the release workflow (-Pmediasync.abiSplits) also builds one APK per CPU for downloads, beside the universal one.
    splits {
        abi {
            isEnable = providers.gradleProperty("mediasync.abiSplits").isPresent
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    // The languages MediaSync is translated into (docs/TRANSLATING.md). Android 13+ lists them under the app's own
    // language setting; other languages that libraries bring are left out.
    androidResources {
        generateLocaleConfig = true
        localeFilters += listOf("en", "cs", "de", "es", "fr", "it", "pt-rBR", "ru", "sk", "uk", "en-rXA", "ar-rXB")
    }

    buildTypes {
        // Pseudo-locales (en-XA: longer, accented; ar-XB: right to left) to check layouts without translations.
        debug {
            isPseudoLocalesEnabled = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
}

// The Google Play store graphics (docs/play) are rendered by a test that runs only with -Pmediasync.storeGraphics.
tasks.withType<Test>().configureEach {
    systemProperty("mediasync.storeGraphics", providers.gradleProperty("mediasync.storeGraphics").isPresent)
    systemProperty("mediasync.largeFont", providers.gradleProperty("mediasync.largeFont").isPresent)
}

dependencies {
    implementation(projects.connector.android)
    implementation(projects.connector.googlePhotosApp)
    implementation(projects.core.datastore)
    implementation(projects.core.designsystem)
    implementation(projects.core.model)
    implementation(projects.feature.backup)
    implementation(projects.feature.consumer)
    implementation(projects.feature.diagnostics)
    implementation(projects.feature.onboarding)
    implementation(projects.feature.pairing)
    implementation(projects.feature.producer)
    implementation(projects.feature.quickaccess)
    implementation(projects.feature.share)
    implementation(projects.sync.android)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.work.runtime)

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

plugins {
    alias(libs.plugins.mediasync.desktop.application)
}

// The desktop app (docs/PLAN.md §9, M8): receives photos and videos from paired phones. The window, the tray and the
// installers come in steps; `:desktopApp:run` starts the app from Gradle.
compose.desktop {
    application {
        mainClass = "io.github.danyk20.mediasync.desktop.MainKt"
    }
}

dependencies {
    implementation(projects.connector.api)
    implementation(projects.connector.folder)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.core.designsystem)
    implementation(projects.core.diagnostics)
    implementation(projects.core.model)
    implementation(projects.feature.backup)
    implementation(projects.feature.consumer)
    implementation(projects.feature.diagnostics)
    implementation(projects.feature.navigation)
    implementation(projects.feature.onboarding)
    implementation(projects.feature.pairing)
    implementation(projects.feature.producer)
    implementation(projects.platform.desktop)
    implementation(projects.sync.runtime)

    implementation(compose.desktop.currentOs)
    implementation(libs.androidx.datastore.preferences.core)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.metrox.viewmodel.compose)

    testImplementation(libs.compose.multiplatform.ui.test)
}

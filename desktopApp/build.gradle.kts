import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.mediasync.desktop.application)
}

// The desktop app (docs/PLAN.md §9, M8): receives photos and videos from paired phones. `:desktopApp:run` starts the
// app from Gradle; the installers (docs/desktop.md) are built by the Release workflow, unsigned (no paid certificates).

// 0.10.2 -> 0.10.2; 1.2.3-rc1 -> 1.2.3. Windows MSI wants MAJOR.MINOR.BUILD, all numbers.
val appVersion: String = rootProject.file("version.txt").readText().trim().substringBefore('-')

// The version the app shows (Settings, Diagnostics, the update check) is its jar's Implementation-Version: the full
// version.txt, so 0.10.2 stays 0.10.2 even where the installer's own number differs (macOS).
tasks.withType<Jar>().configureEach {
    manifest { attributes("Implementation-Version" to rootProject.file("version.txt").readText().trim()) }
}

compose.desktop {
    application {
        mainClass = "io.github.danyk20.mediasync.desktop.MainKt"

        // The Java modules of the installed app's trimmed runtime (jlink). The first line is what
        // `./gradlew :desktopApp:suggestRuntimeModules` finds; the second is what libraries load by reflection, which
        // that check can't see: java.naming for JmDNS, jdk.crypto.ec for the P-256 keys and TLS 1.3, java.management
        // for the libraries' MBeans. A missing module only fails at run time: add it here.
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "MediaSync"
            packageVersion = appVersion
            vendor = "The MediaSync Authors"
            description = "Receives photos and videos from your phones and backs them up on this computer."
            copyright = "Copyright 2026 The MediaSync Authors"
            // No licenseFile: jpackage turns it into a license agreement inside the DMG, which current macOS can no
            // longer show — Finder then calls the disk image "corrupted". Apache-2.0 needs no click-through.
            modules("java.instrument", "java.sql", "jdk.security.auth", "jdk.unsupported")
            modules("java.naming", "jdk.crypto.ec", "java.management")

            macOS {
                bundleID = "io.github.danyk20.mediasync.desktop"
                iconFile.set(project.file("packaging/icon.icns"))
                // jpackage refuses a version starting with 0 on macOS: 0.10.2 is 1.10.2 there (only the DMG's number).
                packageVersion = appVersion.replaceFirst(Regex("^0\\."), "1.")
                // macOS asks for Local Network access when the app first talks to phones on the Wi-Fi.
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSLocalNetworkUsageDescription</key>
                        <string>MediaSync receives photos and videos from your phones on this network.</string>
                        <key>NSBonjourServices</key>
                        <array>
                            <string>_mediasync._tcp</string>
                        </array>
                    """.trimIndent()
                }
            }
            windows {
                iconFile.set(project.file("packaging/icon.ico"))
                perUserInstall = true
                menuGroup = "MediaSync"
                shortcut = true
                dirChooser = true
                // Never change: it is how Windows recognizes a new version as an upgrade of the installed one.
                upgradeUuid = "6f5b2c1e-8d4a-4e7b-9a31-52c0f7d0b6a4"
            }
            linux {
                iconFile.set(project.file("packaging/icon.png"))
                menuGroup = "Utility"
                appCategory = "Utility"
                packageName = "mediasync"
                debMaintainer = "The MediaSync Authors"
                rpmLicenseType = "Apache-2.0"
            }
        }
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
    implementation(libs.compose.multiplatform.material3)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.metrox.viewmodel.compose)

    testImplementation(libs.compose.multiplatform.ui.test)
}

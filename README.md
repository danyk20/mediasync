# MediaSync

[![CI](https://github.com/danyk20/mediasync/actions/workflows/ci.yml/badge.svg)](https://github.com/danyk20/mediasync/actions/workflows/ci.yml)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

MediaSync moves photos and videos from your phones (**producers**) to one receiving device (the **consumer**), as fast as the network allows and with all metadata kept: capture time, location and camera details.

On the consumer, a **connector app** takes over. By default that's Google Photos, which backs up the received files and later frees the space. MediaSync never sees your Google account.

> **Status:** early development (milestone M0, foundations). The app builds and runs, but syncing arrives in M1. See the [development plan](docs/PLAN.md).

## Planned for 1.0

- Pair phones by scanning a QR code; end-to-end encrypted, no accounts
- Fast local Wi-Fi sync, plus an optional **Turbo** mode over Wi-Fi Direct
- Optional internet sync through an encrypted relay, with a usage meter
- Runs in the background only while charging, on a schedule (default 03:00)
- Never sends the same photo twice, even after the consumer has freed space
- Android 10 and newer

## Building

Requirements: the Android SDK (platform 37) and an internet connection on the first build. You don't need to install a JDK yourself: Gradle downloads JDK 21 automatically ([`gradle/gradle-daemon-jvm.properties`](gradle/gradle-daemon-jvm.properties)).

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`.

## Common tasks

| Task | Command |
|---|---|
| Unit, Robolectric and screenshot tests | `./gradlew testDebugUnitTest :sync:protocol:test :core:model:test` |
| Instrumented tests on emulators (API 29 + 36) | `./gradlew ciGroupDebugAndroidTest` |
| Formatting, static analysis, lint, licenses | `./gradlew spotlessCheck detekt lintDebug licenseeDebug` |
| Auto-format | `./gradlew spotlessApply` |
| Coverage report and gates | `./gradlew koverXmlReportCi koverVerifyCi` |
| Re-record screenshots after an intended UI change | `./gradlew testDebugUnitTest -Proborazzi.test.verify=false -Proborazzi.test.record=true` |

## Project layout

```
app/                Application module: navigation, DI graph
core/model          Pure Kotlin domain types
core/designsystem   Theme and shared Compose components
core/datastore      Persistent settings
core/testing        Test fakes and helpers
feature/*           One module per screen group (onboarding, producer, consumer)
sync/protocol       Wire protocol (pure Kotlin)
build-logic/        Gradle convention plugins shared by all modules
docs/               Plan, architecture and decision records
```

See [docs/architecture.md](docs/architecture.md) for the module rules.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Security issues: see [SECURITY.md](SECURITY.md).

## License

Copyright 2026 The MediaSync Authors. Licensed under the [Apache License 2.0](LICENSE).

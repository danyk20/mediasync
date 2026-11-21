# 0010. MediaSync on macOS, Windows, Linux and iOS

**Status:** Accepted · 2026-11-21 · Supersedes the "iOS or desktop clients" non-goal (PLAN §2.3)

## Context

MediaSync is Android-only. Households also have computers (an always-on place with plenty of disk) and iPhones (more photos to collect). [ADR 0004](0004-pure-kotlin-sync-core.md) kept the sync core in plain Kotlin/JVM for this day: ten modules have no Android code and run on a desktop JVM as they are, including TLS 1.3 with certificate pinning (JSSE). They do not compile for iOS: they use `java.time`, `java.security`, `javax.crypto`, `java.util.concurrent`, `java.util.zip` and JSSE.

What the platforms allow (researched November 2026):

- **Desktop:** a receiver can stay on all night. Google Drive for desktop stopped backing up to Google Photos on 10 August 2026, so a desktop needs its own backup target.
- **iOS:** an app cannot receive at a fixed time in the background: `BGProcessingTask` runs when the system decides. Sending works while the app is open and in those background windows. There is no Wi-Fi Direct.
- **Apple Developer account:** not available for now. iOS is sideloaded (free Apple ID, 7-day signing, no push notifications); macOS builds are not notarized.

## Decision

1. **Roles.** Desktop apps (macOS, Windows, Linux) can send and receive. The iOS app only sends.
2. **One codebase.** Kotlin Multiplatform: shared code in `commonMain`; Android, desktop (JVM) and iOS implement the ports that `sync:android` already defines (`ServiceDiscovery`, `LanSockets`, `DeviceConditions`, `MediaSource`, `MediaSink`, …).
   - UI: Compose Multiplatform with Navigation 3. Strings move to Compose resources, same XML and languages.
   - Dependency injection: Metro replaces Hilt (compile-time, all targets).
   - Storage: Room and DataStore, both multiplatform.
3. **The wire protocol does not change.** Device ID = SHA-256 of the self-signed P-256 certificate; the same pairing link and QR code. New platforms pair with existing phones.
4. **Desktop runs the JVM code as it is** (JSSE TLS, OkHttp). Only iOS gets native implementations: Network.framework TLS with the same pinning, PhotoKit, the Keychain, `BGTaskScheduler`.
5. **Backup on desktop** starts with a folder connector (a local folder, a NAS share, or a folder another app syncs).
6. **Distribution without paid accounts:** desktop installers (`.dmg`, `.msi`, `.deb`, `.rpm`) and an unsigned iOS `.ipa` on GitHub Releases. TestFlight and notarization can follow if a developer account is added later; no code changes are needed for that.
7. **Not on the new platforms for now:** Turbo (no Wi-Fi Direct on Apple; Windows later), push wake-ups on iOS, internet sync on desktop and iOS (later, WebRTC).

## Consequences

- Shared modules use the `mediasync.kmp.library` convention: `src/commonMain`, `src/jvmMain`, tests in `src/jvmTest`. Android modules use the `jvm` target exactly as they used the plain JVM modules. A module switches on iOS with `mediasync { iosTargets() }` once all its dependencies can.
- The Android app must not change behaviour while the groundwork moves code (PLAN §15, M7).
- iOS users must refresh the sideloaded app every 7 days; overnight sync on iPhone is best effort.
- macOS and Windows show "unidentified developer" warnings until the builds are signed; the install guide explains them.

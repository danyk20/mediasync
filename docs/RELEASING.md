# Releasing

Releases are automated with [release-please](https://github.com/googleapis/release-please). Version numbers and the changelog come from the Conventional Commit titles of merged pull requests.

Until the Play Store launch (M6, docs/PLAN.md §15) versions stay below 1.0: the first release is 0.1.0 (`initial-version`), and `feat` pull requests bump the minor version (`bump-minor-pre-major`). Version 1.0.0 is chosen deliberately, with a `Release-As: 1.0.0` footer on the launch pull request.

## How a release happens

1. Pull requests are merged into `main`.
2. The **Release** workflow keeps a release pull request open, titled `chore(main): release x.y.z`. It updates `version.txt` and `CHANGELOG.md`.
3. Merging that pull request creates the tag `vx.y.z` and a GitHub Release. The same workflow then:
   - builds the signed APK and AAB: a universal APK (`mediasync-vx.y.z.apk`), which runs on every phone, and one per CPU (`-arm64-v8a`, `-armeabi-v7a`, `-x86_64`), smaller because they hold only that CPU's native code (WebRTC's, about 12 MB each). Most phones want `arm64-v8a`;
   - writes SHA-256 checksums;
   - creates a build provenance attestation;
   - attaches everything to the release.
4. If the `release` environment has a Google Play key, the App Bundle also goes to a Play testing track, internal testing by default. Setup: [docs/play/README.md §7](play/README.md#7-uploading-each-release-from-github-optional).

`versionCode` is derived from the version: `1.2.3` → `1002003`.

## If the build fails after the release was created

The tag and the GitHub Release already exist then, without files. Fix the workflow on `main`, then build that release again: **Actions → Release → Run workflow**, with the tag (e.g. `v0.2.0`). It builds the tagged code, waits for your approval like a normal release, and attaches the files to the existing release.

## One-time setup: signing key

Signing secrets live in a GitHub **environment** called `release`, which requires your approval before each release job runs.

1. Run the script on your own computer, giving the path where the keystore should live. Use the same folder as your other apps' keys, but a **separate file**: GitHub stores the whole file, so a shared file would expose every app's key to this project's CI.
   ```bash
   scripts/create-release-keystore.sh /path/to/keys/mediasync-release.jks
   ```
   The script creates the key, or reuses the file if it already exists. It checks the password, then stores the four secrets in the `release` environment: `MEDIASYNC_KEYSTORE_BASE64`, `MEDIASYNC_KEYSTORE_PASSWORD`, `MEDIASYNC_KEY_ALIAS`, `MEDIASYNC_KEY_PASSWORD`. The password is never written to disk or passed on a command line.
2. Save the password in your password manager, and back up the keystore file to a second safe place.
3. Later, for Google Play: enrol in Play App Signing with **this same key** ("Use my own key"). GitHub and Play builds then share one certificate, so users can switch between them (docs/PLAN.md §14.4).

**Losing this key means existing installs can no longer be updated.** Keep two backups.

## Optional: CI on release pull requests

Pull requests opened by the built-in `GITHUB_TOKEN` don't trigger other workflows, so CI doesn't run on release-please's pull request. To get CI there, create a fine-grained personal access token for this repository only (Contents: write, Pull requests: write) and store it as the secret `RELEASE_PLEASE_TOKEN`. Without it, merge the release pull request as an administrator. It only changes `version.txt` and `CHANGELOG.md`.

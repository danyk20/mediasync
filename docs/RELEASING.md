# Releasing

Releases are automated with [release-please](https://github.com/googleapis/release-please). Version numbers and the changelog come from the Conventional Commit titles of merged pull requests.

## How a release happens

1. Pull requests are merged into `main`.
2. The **Release** workflow keeps a release pull request open, titled `chore(main): release x.y.z`. It updates `version.txt` and `CHANGELOG.md`.
3. Merging that pull request creates the tag `vx.y.z` and a GitHub Release. The same workflow then:
   - builds the signed APK and AAB;
   - writes SHA-256 checksums;
   - creates a build provenance attestation;
   - attaches everything to the release.
4. Google Play upload is added in milestone M6 (docs/PLAN.md §14.4).

`versionCode` is derived from the version: `1.2.3` → `1002003`.

## One-time setup: signing key

Signing secrets live in a GitHub **environment** called `release`, which requires your approval before each release job runs.

1. Create the key on your own computer and store the file somewhere safe (for example, a password manager):
   ```bash
   scripts/create-release-keystore.sh
   ```
   The script asks for a password and prints the commands for step 2. The key never leaves your machine except as an encrypted GitHub secret.
2. Add the four secrets to the `release` environment:
   `MEDIASYNC_KEYSTORE_BASE64`, `MEDIASYNC_KEYSTORE_PASSWORD`, `MEDIASYNC_KEY_ALIAS`, `MEDIASYNC_KEY_PASSWORD`.
3. Later, for Google Play: enrol in Play App Signing with **this same key** ("Use my own key"). GitHub and Play builds then share one certificate, so users can switch between them (docs/PLAN.md §14.4).

**Losing this key means existing installs can no longer be updated.** Keep two backups.

## Optional: CI on release pull requests

Pull requests opened by the built-in `GITHUB_TOKEN` don't trigger other workflows, so CI doesn't run on release-please's pull request. To get CI there, create a fine-grained personal access token for this repository only (Contents: write, Pull requests: write) and store it as the secret `RELEASE_PLEASE_TOKEN`. Without it, merge the release pull request as an administrator. It only changes `version.txt` and `CHANGELOG.md`.

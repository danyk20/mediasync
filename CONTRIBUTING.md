# Contributing to MediaSync

Thanks for helping! This page explains how to set up the project and what a pull request needs.

## Setup

1. Install the Android SDK (platform 37). Android Studio or the command-line tools both work.
2. Clone the repo and run `./gradlew assembleDebug`. Gradle downloads JDK 21 on its own.
3. Optional: install [Lefthook](https://github.com/evilmartians/lefthook) and run `lefthook install` to check formatting before each commit.

## Before you open a pull request

Run the same checks CI runs:

```bash
./gradlew spotlessCheck detekt lintDebug licenseeDebug
./gradlew testDebugUnitTest :sync:protocol:test :core:model:test koverVerifyCi
```

- **Formatting:** `./gradlew spotlessApply` fixes formatting and adds the license header to new files.
- **Tests:** new code comes with tests. Pure logic goes in a JVM module with fast unit tests. UI gets a Robolectric test and, for visible changes, a screenshot test.
- **Screenshots:** if you changed the UI on purpose, re-record with
  `./gradlew testDebugUnitTest -Proborazzi.test.verify=false -Proborazzi.test.record=true`
  and commit the updated PNGs so reviewers can see the difference.
- **Coverage:** CI fails below 70 % overall, and below 85 % for sync modules.

## Pull requests

- Keep each PR focused on one change.
- The PR title follows [Conventional Commits](https://www.conventionalcommits.org/), for example `feat: show pairing QR code` or `fix(protocol): reject empty offers`. Titles drive the changelog and version numbers.
- All CI checks must pass, and a code owner must approve. PRs are squash-merged.

## Architecture decisions

Significant decisions are recorded in [docs/adr](docs/adr). If your change alters one, add or update an ADR in the same PR.

## License

By contributing, you agree that your contributions are licensed under the [Apache License 2.0](LICENSE), the same license as the project.

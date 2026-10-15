#!/usr/bin/env bash
# Installs the emulator image for one API level and creates its Gradle Managed Device, before the device tests.
# Every module sets up the same emulator, and two installing one image at once can fail ("System Image ... does
# not exist for this architecture"), so CI installs it once first. The download from Google sometimes arrives
# broken ("Archive is not a ZIP archive"): it's retried from scratch, up to three times.
#
# Usage: scripts/install-emulator-image.sh <api level>   e.g. 36
set -euo pipefail

API="$1"
SDK="${ANDROID_HOME:?ANDROID_HOME must point to the Android SDK}"

for attempt in 1 2 3; do
  if ./gradlew ":app:api${API}Setup" --max-workers=1 \
      -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect; then
    exit 0
  fi
  echo "::warning::Setting up the API ${API} emulator failed (attempt ${attempt} of 3)."
  # Drop what the failed download left, so the next attempt fetches the image again.
  rm -rf "${SDK}/.temp" "${SDK}/.downloadIntermediates"
  sleep $((attempt * 15))
done
exit 1

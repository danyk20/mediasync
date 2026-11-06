#!/usr/bin/env bash
# Downloads the emulator and the system image for one API level, before the device tests. Gradle Managed Devices
# would download them itself, but the download from Google sometimes arrives broken ("Archive is not a ZIP
# archive"): here it's retried from scratch, up to three times. Gradle then only creates the emulator and its
# snapshot, while it compiles the code.
#
# Usage: scripts/install-emulator-image.sh <api level>   e.g. 36
set -euo pipefail

API="$1"
SDK="${ANDROID_HOME:?ANDROID_HOME must point to the Android SDK}"
SDKMANAGER="${SDK}/cmdline-tools/latest/bin/sdkmanager"
# The image the managed devices use (build-logic, KotlinAndroid.kt): AOSP, 64-bit x86 on CI's runners.
IMAGE="system-images;android-${API};default;x86_64"

for attempt in 1 2 3; do
  # The runners have the SDK licenses accepted; `yes` answers in case a new one comes up.
  if (yes || true) | "$SDKMANAGER" --install emulator "$IMAGE"; then
    exit 0
  fi
  echo "::warning::Downloading the API ${API} emulator image failed (attempt ${attempt} of 3)."
  # Drop what the failed download left, so the next attempt fetches the image again.
  rm -rf "${SDK}/.temp" "${SDK}/.downloadIntermediates"
  sleep $((attempt * 15))
done
exit 1

#!/usr/bin/env bash
# Copyright 2026 The MediaSync Authors
# SPDX-License-Identifier: Apache-2.0
#
# ./gradlew with its arguments, on CI: run again, up to 3 times, when it failed only because a download failed.
#
# A repository that doesn't answer for a moment (Maven Central, Google's Maven, the Gradle Plugin Portal) once failed
# a nightly run before any test ran: "Plugin [id: 'io.gitlab.arturbosch.detekt', …] was not found". Gradle retries a
# single request itself (gradle.properties); this covers an outage that outlasts those retries. Any other failure, a
# failed test or check among them, fails at once: nothing is hidden by running it again.
set -uo pipefail

ATTEMPTS=3
WAIT_SECONDS=${GRADLEW_RETRY_WAIT_SECONDS:-60} # Each wait longer: 60 s, then 120 s.
log=$(mktemp)
trap 'rm -f "$log"' EXIT

# What Gradle says when it couldn't download something.
DOWNLOAD_FAILED='was not found in any of the following sources|Could not resolve|Could not (GET|HEAD|download)|Received status code 5[0-9][0-9]|Read timed out|Connect timed out|Connection reset|Remote host terminated the handshake|No cached version of .* available for offline mode'
# What Gradle says when a test or a check failed: never run again for these.
CHECK_FAILED='There were failing tests|tests completed, [0-9]+ failed|Verification failed|Lint found errors|detekt found|spotless.*violations|Rule violated'

for attempt in $(seq 1 "$ATTEMPTS"); do
  ./gradlew "$@" 2>&1 | tee "$log"
  status=${PIPESTATUS[0]}
  if [ "$status" -eq 0 ]; then
    exit 0
  fi
  if ! grep -Eq "$DOWNLOAD_FAILED" "$log" || grep -Eq "$CHECK_FAILED" "$log"; then
    exit "$status"
  fi
  if [ "$attempt" -lt "$ATTEMPTS" ]; then
    wait=$((attempt * WAIT_SECONDS))
    echo "::warning::A download failed (attempt $attempt of $ATTEMPTS); running Gradle again in ${wait} s."
    sleep "$wait"
  fi
done
echo "::error::Downloads still failed after $ATTEMPTS attempts."
exit "$status"

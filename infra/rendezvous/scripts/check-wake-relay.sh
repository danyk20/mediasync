#!/usr/bin/env bash
# Copyright 2026 The MediaSync Authors
# SPDX-License-Identifier: Apache-2.0
#
# Checks that the developer's wake relay (PLAN §10.4) works end to end: it signs in to Google with its FCM service
# account, and FCM answers. A made-up token is refused as unknown (410 push_token_unregistered), which only a working
# relay answers; a broken or revoked service account, a relay that's down or an FCM outage answers otherwise.
#
# Usage: scripts/check-wake-relay.sh [relay URL]  (default: the WAKE_RELAY_URL households' Workers use, in wrangler.toml)
set -euo pipefail
cd "$(dirname "$0")/.."

relay="${1:-$(sed -n 's/^WAKE_RELAY_URL = "\(https:[^"]*\)"$/\1/p' wrangler.toml | head -n 1)}"
if [ -z "$relay" ]; then
    echo "No wake relay URL in wrangler.toml" >&2
    exit 1
fi
answer=$(mktemp)
trap 'rm -f "$answer"' EXIT
# A secret just set takes a few seconds to reach the relay.
for attempt in 1 2 3 4 5 6; do
    status=$(curl -sS -m 20 -o "$answer" -w '%{http_code}' -X POST "$relay/v1/relay/wake" \
        -H 'content-type: application/json' \
        -d '{"token":"mediasync-check-not-a-real-token","data":{"type":"wake-check"},"collapseKey":"mediasync-check"}' \
        || echo 000)
    if [ "$status" = 410 ]; then
        echo "The wake relay at $relay reaches FCM."
        exit 0
    fi
    [ "$attempt" -lt 6 ] && sleep 10
done
echo "::error::The wake relay at $relay answered HTTP $status: $(head -c 500 "$answer")"
exit 1

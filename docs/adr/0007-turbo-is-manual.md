# 0007. Turbo transfer (Wi-Fi Direct) is manual only

**Status:** Accepted · 2026-10-13

## Context

A direct Wi-Fi link between two phones is usually the fastest path. But on many phones it interrupts normal Wi-Fi, and on Android 10–12 it needs the location permission.

## Decision

Automatic sync uses only the normal Wi-Fi network, plus the internet if allowed. **Turbo transfer** is a button the user presses for big backlogs. Its group credentials are derived from the pairing secret, so no system pairing dialog appears. If Turbo fails, the transfer falls back to normal Wi-Fi.

## Consequences

- Everyday sync never disturbs the phone's connectivity.
- The Wi-Fi Direct permission is only requested the first time someone uses Turbo.

# 0002. Connector apps on the consumer do the backup (v1.0)

**Status:** Accepted · 2026-10-13

## Context

Since March 2025, the Google Photos Library API can't read a user's library. Third-party apps only see what they uploaded themselves, and API uploads count toward the user's storage. The planned consumer is a Pixel 2 XL, which gets free unlimited Storage saver backup, but only for uploads made by the Google Photos app on that phone.

## Decision

In v1.0, MediaSync's responsibility ends when a received file is complete in one folder on the consumer (`Pictures/MediaSync/`). A *connector app* on the consumer handles everything after that: upload, then deletion. Google Photos is the default. MediaSync describes connector apps with a small profile (folder layout, setup guidance, cleanup shortcut).

An in-app uploading connector framework (Google Photos API, Immich, WebDAV, S3, …) follows in v1.1, behind interfaces designed now.

## Consequences

- Free unlimited backup on Pixel 2–5. No OAuth verification or API quota stands in the way of 1.0.
- MediaSync can't confirm per item that the connector app has uploaded a file. Cleanup uses the connector app's own tools, which only delete backed-up items.
- Files vanish from the consumer after cleanup, so producers must remember what they sent (PLAN.md §5.6).

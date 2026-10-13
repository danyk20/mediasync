# 0006. Metadata kept by default; privacy removal at the source

**Status:** Accepted · 2026-10-13

## Context

Backed-up photos must keep their capture time, location and other details. Android hides photo location from apps unless the app asks for it. Some users want to share photos without metadata.

## Decision

- Files are transferred byte for byte, and location is read unredacted (`ACCESS_MEDIA_LOCATION`, `setRequireOriginal`).
- Details stored outside the file (filename, album, capture date, favourite) travel in a sidecar.
- Files with no internal date get one inserted losslessly, so the backup service files them on the right day.
- A *Photo details* setting (*Keep all* / *Remove location* / *Remove all*) is applied **on the producer before sending**, so removed details never leave the phone. Removal is lossless and streaming.

## Consequences

- No speed cost in the default mode.
- A custom lossless metadata editor for JPEG, PNG, WebP and MP4 is needed, with HEIC as a later spike. It's covered by a golden media set checked with an independent reader.

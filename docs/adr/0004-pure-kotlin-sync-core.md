# 0004. Pure Kotlin sync core

**Status:** Accepted · 2026-10-13

## Context

The sync protocol, engine and metadata handling are where correctness matters most: data loss and duplicate transfers are the worst possible bugs. They need fast, thorough tests, including property-based tests and fuzzing.

## Decision

`sync:*`, `core:model`, and later `core:metadata`, `transport:api` and `connector:api`, are plain Kotlin/JVM modules with no Android dependency. Android code (MediaStore, the keystore, WorkManager, Wi-Fi P2P) sits behind interfaces at the edges.

## Consequences

- Tests run on the JVM in milliseconds, with higher coverage gates (85 %).
- A desktop or NAS consumer can reuse the core later (Kotlin Multiplatform).
- Some Android types need small adapters at the boundary.

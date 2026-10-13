# 0001. One-way, append-only sync

**Status:** Accepted · 2026-10-13

## Context

Producers are everyday phones. The consumer collects their photos and videos and hands them to a backup service. Two-way sync would need conflict resolution and deletion propagation, and a deletion on one phone could destroy the only copy elsewhere.

## Decision

Sync goes only from producer to consumer and only adds files. Deleting a file on a producer never deletes it on the consumer. An edited photo is new content and is sent again as a new version; nothing is overwritten.

## Consequences

- No conflict handling is needed, and an accidental deletion on a phone can't spread.
- Both sides keep a ledger of what was sent and received, so nothing is transferred twice (PLAN.md §5.6).
- Edited photos produce a second copy at the backup service.

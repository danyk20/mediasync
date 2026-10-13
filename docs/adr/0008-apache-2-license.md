# 0008. Apache-2.0 license

**Status:** Accepted · 2026-10-13

## Context

The repository is public. The app links Google Play services libraries (Firebase, ML Kit), which are not open source.

## Decision

The project is licensed under Apache-2.0, and contributions are accepted under the same license. Every source file carries an SPDX header, enforced by Spotless. Licensee only allows dependencies with Apache-compatible licenses.

## Consequences

- Simple to reuse, with an explicit patent grant, and no conflict with proprietary Google libraries.
- Others may build closed-source derivatives. This was accepted knowingly; see PLAN.md §18 for the comparison with GPL-3.0.

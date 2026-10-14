# Architecture decision records

Each record captures one significant decision: the context, what was decided, and what follows from it. Records are never rewritten. A changed decision gets a new record that supersedes the old one.

| # | Decision | Status |
|---|---|---|
| [0001](0001-one-way-append-only-sync.md) | One-way, append-only sync | Accepted |
| [0002](0002-connector-apps-do-the-backup.md) | Connector apps on the consumer do the backup (v1.0) | Accepted |
| [0003](0003-serverless-rendezvous-and-metered-relay.md) | Serverless rendezvous, WebRTC, metered relay for internet sync | Accepted |
| [0004](0004-pure-kotlin-sync-core.md) | Pure Kotlin sync core | Accepted |
| [0005](0005-min-sdk-29.md) | Android 10 (API 29) as minimum | Accepted |
| [0006](0006-metadata-kept-privacy-at-source.md) | Metadata kept by default; privacy removal at the source | Accepted |
| [0007](0007-turbo-is-manual.md) | Turbo transfer (Wi-Fi Direct) is manual only | Accepted |
| [0008](0008-apache-2-license.md) | Apache-2.0 license | Accepted |
| [0009](0009-rendezvous-device-authentication.md) | Rendezvous Worker: device authentication, pair mailboxes and API | Accepted |

New records copy the structure of an existing one: Status, Context, Decision, Consequences.

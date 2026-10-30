# 0009. Rendezvous Worker: device authentication, pair mailboxes and API

**Status:** Accepted · 2026-10-15

## Context

Internet sync (M5) needs the rendezvous Worker from [ADR 0003](0003-serverless-rendezvous-and-metered-relay.md): a mailbox for WebRTC signaling, short-lived TURN credentials, FCM wake-ups and a relay usage meter (PLAN §8.4, §8.5, §10.4). The Worker is on the public internet, so it must know which phone is asking, without user accounts and without any secret shipped in the app.

Every phone already has an identity: an EC P-256 key in the Android Keystore (sign-only, `SHA-256` digest) and a self-signed certificate. Its **device ID** is the lowercase hex SHA-256 of the DER-encoded certificate, the pin that paired phones store (`DeviceId`, `X509Certificate.deviceId()`, PLAN §11). The consumer decides who pairs with it.

This record specifies what the Android side must implement; the Worker in `infra/rendezvous/` implements the server side.

## Decision

### 1. Every request is signed with the device's identity key

Each request (except `GET /v1/health`) carries four headers:

| Header | Value |
|---|---|
| `X-MediaSync-Certificate` | The device's certificate, DER, base64url without padding |
| `X-MediaSync-Timestamp` | Current time, Unix seconds, decimal |
| `X-MediaSync-Nonce` | 16 fresh random bytes, base64url without padding (22 characters) |
| `X-MediaSync-Signature` | `SHA256withECDSA` signature over the canonical string, **DER-encoded** (what Java's `Signature` produces), base64url without padding |

The **canonical string** is these eight lines joined with `\n` (no trailing newline), UTF-8 encoded:

```
MEDIASYNC-AUTH-V1
<METHOD, upper case>
<host as in the URL, lower case, with :port only if not the default>
<path exactly as sent, e.g. /v1/pairs/0bad…333/relay-usage>
<query string without "?", or an empty line>
<the X-MediaSync-Timestamp value>
<the X-MediaSync-Nonce value>
<lowercase hex SHA-256 of the exact body bytes; of the empty string when there is no body>
```

The Worker hashes the certificate to get the device ID, takes the public key from it (it must be P-256), and verifies the signature. It doesn't check the certificate's own signature or dates: identity is the certificate hash plus proof of the key. Requests are refused (HTTP 401) when:

| `error.code` | Cause |
|---|---|
| `auth_missing` | A header is missing |
| `auth_malformed` | Bad base64url, nonce not 16 bytes, certificate not DER X.509 or over 4 KiB, key not P-256, signature not DER |
| `auth_clock_skew` | Timestamp more than 300 s from the Worker's clock. The response's `X-MediaSync-Server-Time` header has the Worker's time (Unix seconds); the app may retry once with a corrected timestamp |
| `auth_bad_signature` | The signature doesn't match |
| `auth_replayed` | This device already used this nonce (nonces are remembered for 600 s) |

`infra/rendezvous/test/fixtures/golden-request.json` is a request signed by OpenSSL, with its canonical string; the Android tests should reproduce the canonical string and the device ID from it.

### 2. Pair IDs are derived, and the consumer registers the pair

The mailbox ID of a pair is

```
pairId = hex(SHA-256("mediasync-pair-v1" || consumerId || producerId))
```

over the ASCII label and the raw 32-byte device IDs (known answer: consumer `11…11`, producer `22…22` → `f684b5739a33d77bf1ab7936fc3e76551e03e0044387ebb9a66fc18da5fac8df`). Both phones compute it from IDs they already have after pairing; nothing new goes into the QR code. It isn't secret: only the two member devices can use the mailbox.

After pairing, the **consumer** registers the pair (`PUT /v1/pairs/{pairId}`). The Worker only accepts it when `pairId` is derived with the signer as consumer, so only the consumer can create a pair, and the consumer is the authority, as on the LAN. Pairs made before M5 are registered the same way the next time the consumer is online. Unpairing on either phone sends `DELETE /v1/pairs/{pairId}`, which deletes everything the Worker holds for the pair.

Each TURN credential is tagged with the first 32 hex characters of the pair ID (Cloudflare's `customIdentifier`), which the meter uses for the per-pair breakdown.

### 3. API

Base URL: the Worker's address (e.g. `https://mediasync-rendezvous.<account>.workers.dev`), configurable in the app. JSON bodies, at most 16 KiB. Errors are `{"error":{"code":"…","message":"…"}}`; the app branches on `code`.

| Request | Who | Body → response |
|---|---|---|
| `PUT /v1/pairs/{pairId}` | consumer | `{"producer":"<device ID>","fcmToken":"<token>"\|null}` (`fcmToken` optional: omitted keeps, `null` removes) → `201`/`200 {"pairId","created"}`. Errors: `pair_id_mismatch`, `bad_request` |
| `GET /v1/pairs/{pairId}` | member | → `{"pairId","role":"consumer"\|"producer","pushTokenRegistered","peerOnline"}` |
| `DELETE /v1/pairs/{pairId}` | member | → `{"deleted":true}`; open mailbox sockets close with code 4003 |
| `GET /v1/pairs/{pairId}/signal` + `Upgrade: websocket` | member | The signaling mailbox (§4) |
| `POST /v1/pairs/{pairId}/ice-servers` | member | → `{"iceServers":[…],"ttlSeconds":3600,"relay":{"available":true}}`, or STUN only with `"relay":{"available":false,"reason":"budget_exhausted"\|"not_configured"\|"unavailable","resumesAt"?}` |
| `POST /v1/pairs/{pairId}/wake` | producer | `{"payload"?:"<base64url ≤ 2048>"}` → `{"sent":true}`. Errors: `no_push_token` (409), `wake_throttled` (429, `Retry-After`; one per pair per 20 s), `push_token_unregistered` (410, token forgotten), `push_failed` (502, FCM didn't take it), `wake_relay_failed` (502, a household's Worker couldn't use the wake relay; the message says why, e.g. `HTTP 404 (Cloudflare error 1042)`), `push_not_configured` (503) |
| `POST /v1/pairs/{pairId}/wake-check` | consumer | `{}` → `{"sent":true}`. Sends the consumer `{"type":"wake-check"}` the way a wake-up goes, which wakes nothing: the phone checks wake-ups reach it, when it's set up and once a day. Errors as for `wake`; `wake_throttled` is one per pair per minute |
| `POST /v1/pairs/{pairId}/relay-usage` | member | `{"session":"<base64url of 16 random bytes>","relayedBytes":<n>}` → the meter (§5) |
| `GET /v1/pairs/{pairId}/relay-usage` | member | → the meter (§5) |
| `PUT /v1/relay/settings` | admin device | `{"budgetGb","periodStartDay","allowPaid","paidCapUsd"}` → the settings. `not_admin` (403) unless the device ID is in the Worker secret `RELAY_ADMIN_DEVICE_IDS` |

Common errors: `pair_not_found` (404: not registered), `not_a_member`, `not_consumer`, `not_producer` (403), `rate_limited` (429, `Retry-After: 60`; per address 120/min before authentication, per device 60/min, ICE servers 10/min per device), `payload_too_large` (413).

### 4. Signaling mailbox

- One WebSocket per device; a new connection replaces the old one (close code 4001).
- **Binary frames** are the phones' messages: SDP and ICE candidates encrypted with their pair key (Tink AEAD), at most 64 KiB each. The Worker relays them unread to the other device, or queues them while it's offline (up to 64 messages or 256 KiB, for 10 minutes) and delivers them, oldest first, when it connects. Delivery is best effort.
- **Text frames** from the Worker are JSON: `{"type":"ready","peerOnline":bool,"queued":n}` right after connecting, `{"type":"peer","online":bool}` when the other device comes or goes, and `{"type":"error","code":"binary_only"|"message_too_large"|"mailbox_full"|"unpaired"}`.
- The phone sends the text frame `ping` every 30–60 s and gets `pong`; the runtime answers it without waking the mailbox (hibernation).
- More than 120 messages a minute closes the socket (code 1008).

How the phones agree on the pair key is part of the Android work and invisible to the Worker. Suggested: the consumer generates 32 random bytes and sends them inside the pinned TLS session (at pairing, or at the next LAN session for older pairs), and both derive the AEAD key from it with HKDF.

### 5. Relay usage meter and budget guard (PLAN §8.5)

- During a relayed session (the selected candidate pair's local candidate has type `relay`), each phone reports every minute the cumulative `bytesSent + bytesReceived` of that candidate pair, with one random `session` value per peer connection. Repeated or late reports are harmless.
- Every 15 minutes a cron trigger reads the period's egress from Cloudflare's analytics (`callsTurnUsageAdaptiveGroups`, one summed query for the account, plus a breakdown by credential tag).
- The meter: `{"period":{"start","end"},"usedBytes","authoritativeBytes","authoritativeAt","liveBytes","limitBytes","freeAllowanceBytes","relayAvailable","settings":{…},"pair":{"approxBytes"}}`. `usedBytes` = analytics + live reports since the analytics were read (the last 10 minutes before that count again, to cover analytics delay). One report can add at most 10 GB.
- At `limitBytes` (default 950 GB; with paid usage on, plus the cap's worth at $0.05/GB) the Worker stops issuing TURN credentials until `period.end`. Credentials live 1 hour, which bounds any overshoot. GB means 10⁹ bytes.

## Consequences

- No accounts and no secret in the app: a phone's existing keystore key is its credential, and the pair ID is computed, not exchanged.
- Signing each request costs one keystore operation; requests are rare (credentials hourly, reports every minute while relaying).
- Phones need a roughly correct clock (±5 minutes).
- The Worker learns which device IDs are paired, and when they talk. It never sees media, signaling contents or IP addresses beyond what Cloudflare itself handles.
- Anyone can create device keys and register their own pair, so a public deployment's relay allowance is shared by whoever uses it; the budget guard and rate limits bound the damage. Whether other families share the allowance is still open (PLAN §18).

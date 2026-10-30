# Rendezvous Worker

The Cloudflare Worker that internet sync needs (docs/PLAN.md §8.4, §8.5, §10.4): it relays the phones' encrypted WebRTC signaling, mints 1-hour TURN credentials while the relay budget allows, wakes the consumer through FCM, and meters relay usage. It never sees media and can't read the signaling. The API and the device authentication scheme are specified in [ADR 0009](../../docs/adr/0009-rendezvous-device-authentication.md).

## Layout

| Path | What |
|---|---|
| `src/index.ts` | Entry point: HTTP handler and the 15-minute cron trigger |
| `src/router.ts`, `src/handlers/` | Routes; rate limit → read body → verify signature → handler |
| `src/auth.ts`, `src/der.ts`, `src/nonces.ts` | Signed requests (ADR 0009 §1) and the replay check |
| `src/pair-mailbox.ts` | Durable Object per pair: membership, FCM token, signaling WebSockets (hibernation API) |
| `src/turn.ts`, `src/fcm.ts` | Cloudflare TURN credentials; FCM HTTP v1 with a service-account JWT |
| `src/meter/` | Durable Object for the relay meter and budget guard, the analytics query, billing periods |
| `test/` | Vitest in the Workers runtime; outbound calls are mocked, nothing reaches the network |

## Working on it

Node.js 22 or newer.

```bash
npm ci                 # exact versions from package-lock.json
npm run typecheck      # tsc, strict
npm test               # Vitest inside workerd (@cloudflare/vitest-plugin)
npm run check:headers  # license header in every source file
npm run check:types    # worker-configuration.d.ts matches wrangler.toml (regenerate with `npm run types`)
npm run bundle         # what a deploy would upload, without uploading (no account needed)
```

The tests print a warning about missing required secrets; that's expected, they get test values from `vitest.config.ts`. CI runs all of the above in the `Rendezvous Worker` job.

`npm run dev` starts the Worker locally (`wrangler dev`). Put test values for the secrets in a `.dev.vars` file (ignored by git).

## Deploying

Nothing deploys until you set up the accounts below. `.github/workflows/rendezvous-deploy.yml` deploys `--env staging` on pushes to `main`, for testing; while the GitHub environment has no Cloudflare credentials it skips the deploy with a notice. There's no production deploy: households run their own Worker, which the receiving phone deploys into their Cloudflare account with the settings of `--env production` (`npm run bundle:app`).

### 1. Accounts and keys

| What | Where | Becomes |
|---|---|---|
| Cloudflare account (Free plan is enough) | dash.cloudflare.com | Account ID → `CF_ACCOUNT_ID` |
| TURN key | Dashboard → Realtime → TURN Server → Create | Key ID → `TURN_KEY_ID`, API token → `TURN_KEY_API_TOKEN` |
| Analytics token | My Profile → API Tokens → Create, permission *Account → Account Analytics → Read* | `CF_ANALYTICS_TOKEN` |
| Firebase project with Cloud Messaging | console.firebase.google.com (the same project the app's `google-services.json` comes from) | Project settings → Service accounts → Generate new private key: the whole JSON file → `FCM_SERVICE_ACCOUNT` |
| Deploy token for CI | My Profile → API Tokens → template *Edit Cloudflare Workers* | GitHub secret `CLOUDFLARE_API_TOKEN` |

### 2. Worker secrets

Set the five secrets for each environment before its first deploy (`wrangler deploy` refuses to deploy without them; `wrangler secret put` offers to create the Worker if it doesn't exist yet). Either in the dashboard (Workers & Pages → the Worker → Settings → Variables and Secrets), or:

```bash
npx wrangler login
for name in CF_ACCOUNT_ID CF_ANALYTICS_TOKEN TURN_KEY_ID TURN_KEY_API_TOKEN; do
  npx wrangler secret put "$name" --env staging
done
npx wrangler secret put FCM_SERVICE_ACCOUNT --env staging < service-account.json
```

Repeat with `--env production`. Staging and production may share the TURN key and Firebase project. The relay allowance belongs to the Cloudflare account, so both Workers' meters count the whole account's usage.

Optional: `RELAY_ADMIN_DEVICE_IDS`, comma-separated device IDs allowed to change the relay budget from the app (`PUT /v1/relay/settings`). Without it, the budget comes from the vars in `wrangler.toml`.

### 3. GitHub environments

In the repository settings, create the environment `rendezvous-staging` with the secrets `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID`, for the testing Worker. There's no production environment: households' Workers are deployed from the app into their own accounts.

### 4. The wake relay (once, in the developer's account)

Households' Workers have no FCM service account, so they hand wake-ups to the developer's wake relay: this Worker,
deployed as `--env wake-relay` with the service account of the app's Firebase project (docs/PLAN.md §10.4). It only
answers `POST /v1/relay/wake`, stores and logs nothing, and allows ten wake-ups a minute per phone.

1. **Firebase:** in the app's project, *Project settings → Service accounts → Generate new private key*. Keep the JSON
   file private: it can send messages to the app's users.
2. **Cloudflare:** an API token for the developer's own account with *Workers Scripts: Edit*, and the account ID.
3. **GitHub:** create the environment `rendezvous-wake-relay` with the secrets `CLOUDFLARE_API_TOKEN`,
   `CLOUDFLARE_ACCOUNT_ID` and `FCM_SERVICE_ACCOUNT` (the whole JSON file).
4. **Actions → Rendezvous deploy → Run workflow**, target `wake-relay`. The log shows its address,
   `https://mediasync-wake-relay.<subdomain>.workers.dev`.
5. Set that address as `WAKE_RELAY_URL` in `[env.production.vars]`, run `npm run bundle:app`, and commit: the app's
   Worker carries it from then on, and households' Workers deployed (or redeployed) from it can be woken.

The deployment checks the relay reaches FCM (`scripts/check-wake-relay.sh`), and so does CI every night. A household's
Worker reaches the relay with `fetch()`, which Cloudflare refuses between Workers on the same zone (error 1042), as a
household Worker in the relay's own account is: the `global_fetch_strictly_public` compatibility flag in
`wrangler.toml` lets it through, and `npm run bundle:app` refuses to bundle a relay-using Worker without it.

### 5. Sign in with Cloudflare (once, in the developer's account)

Without this, the receiving phone deploys a household's Worker with an API token the user creates on Cloudflare's
token page and pastes. With MediaSync's own OAuth client, *Internet sync* offers **Sign in with Cloudflare** first and
folds the token steps under *Or use an API token*. It's OAuth 2.0's authorization code flow with PKCE (S256), as a
public client without a secret (`CloudflareOAuth` in `transport/rendezvous`). The access token goes to the same
deployment as a pasted token, once, and is kept nowhere; no refresh token is asked for. A build without the two
settings in step 5 hides the button and works as before.

The app asks for these scopes (`CloudflareOAuth.SCOPES`, names from `GET /client/v4/oauth/scopes`):

| Scope | For | On the client |
|---|---|---|
| `account-settings.read` | finding the user's account (`GET /accounts`) | required |
| `workers-scripts.write` | uploading the Worker with its secrets, cron trigger and workers.dev address | required |
| `calls.write` | the relay's TURN key (`POST /accounts/{id}/calls/turn_keys`) | optional: without it the Worker runs without the relay, and the app says how to add it |

1. **A domain whose DNS you control.** A `github.io` address can't be verified. The examples use `mediasync.example`;
   the redirect address is an https App Link on it, such as `https://mediasync.example/oauth/cloudflare`.
2. **Register the client.** Dashboard → *Manage Account → OAuth clients → Create* (or
   `POST /client/v4/accounts/{account_id}/oauth_clients` with a token that has *OAuth Clients Write*):
   - name `MediaSync`, a logo, and the client URL `https://mediasync.example` (on the domain you verify);
   - a mobile/public client: authorization code with PKCE, token endpoint auth method `none`, no refresh tokens;
   - the scopes above, with `calls.write` optional;
   - the redirect URI `https://mediasync.example/oauth/cloudflare`.

   Note the client ID. The endpoints the app uses are `https://dash.cloudflare.com/oauth2/auth` and
   `https://dash.cloudflare.com/oauth2/token` (developers.cloudflare.com, *Integrate your OAuth client*).
3. **Verify the domain.** Add the DNS `TXT` record the dashboard shows. Its value is the whole text, including the
   `cloudflare_oauth_client_publisher=` prefix. Cloudflare checks for up to two days; to check again, `PATCH` the client
   with its unchanged `client_uri`.
4. **App Links.** Serve `https://mediasync.example/.well-known/assetlinks.json` as `application/json`, without
   redirects:

   ```json
   [{
     "relation": ["delegate_permission/common.handle_all_urls"],
     "target": {
       "namespace": "android_app",
       "package_name": "io.github.danyk20.mediasync",
       "sha256_cert_fingerprints": [
         "5F:3C:B3:5A:35:F9:4F:2D:B3:65:0E:F6:36:69:AE:E5:FA:B2:15:F8:36:ED:D2:4B:CE:20:DB:54:55:3E:FA:D2"
       ]
     }
   }]
   ```

   That's the release certificate (CN=MediaSync). Play App Signing uses the same key (the upload was our own key), so
   GitHub and Play builds share it. To test a debug build, add the debug key's fingerprint too
   (`./gradlew :app:signingReport`). A short page at the redirect address that says to go back to MediaSync helps if
   a browser shows it instead of opening the app. After installing, `adb shell pm get-app-links io.github.danyk20.mediasync`
   should list the domain as `verified`.
5. **Build with it.** Set `CLOUDFLARE_OAUTH_CLIENT_ID` and `CLOUDFLARE_OAUTH_REDIRECT_URI` as Gradle properties
   (`~/.gradle/gradle.properties`, or `-P`) or environment variables. Release builds take them from the repository
   variables of the same names (*Settings → Secrets and variables → Actions → Variables*). The build checks that the redirect is an https address without a query, and adds the App Link
   (`feature/consumer/src/cloudflareSignIn/AndroidManifest.xml`) only then.
6. **Try it while the client is private.** Until it's public, only members of the developer's Cloudflare account can
   authorize it, which is enough for a test sign-in from a debug build.
7. **Make it public** in the dashboard, or with `PATCH .../oauth_clients/{client_id}` and `{"visibility":"public"}`.
   This needs the name, logo, client URL and scopes, and the verified domain. **It's permanent: a public client can't
   be made private again, and the verified domain stays locked to it.** Do it only once a test sign-in has worked.

## Configuration

Vars in `wrangler.toml`, repeated per environment:

| Var | Default | Meaning (PLAN §8.5) |
|---|---|---|
| `RELAY_BUDGET_GB` | `950` | Stop issuing TURN credentials at this usage |
| `RELAY_PERIOD_START_DAY` | `1` | Day of the month (UTC, 1–28) the billing period starts |
| `RELAY_ALLOW_PAID` | `false` | Allow relay usage beyond the free allowance |
| `RELAY_PAID_CAP_USD` | `0` | Monthly cost cap when paid usage is allowed (required then) |
| `RELAY_FREE_ALLOWANCE_GB`, `RELAY_PRICE_USD_PER_GB` | `1000`, `0.05` | Cloudflare's free allowance and price |
| `TURN_CREDENTIAL_TTL_SECONDS` | `3600` | TURN credential lifetime (at most 1 h) |

Rate limits (`[[ratelimits]]`): 120 requests per minute per client address before authentication, 60 per device, 10 ICE-server requests per device.

## Privacy

- Workers Logs is off; the Worker logs only configuration and upstream errors, never addresses, headers or IDs. The client address is used only as a rate-limit key inside Cloudflare.
- Stored per pair: the two device IDs, the consumer's FCM token, signaling messages for at most 10 minutes, and request nonces for 10 minutes. Unpairing deletes all of it.
- Stored for the meter: analytics totals per pair tag and live byte counts.

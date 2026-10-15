# Writing a connector

A connector backs up what the receiving phone received to one kind of place: a cloud service, a server, or another app on the phone. The design is in [PLAN.md §9](PLAN.md#9-cloud-backup-connectors). Adding one means adding a Gradle module, and nothing else changes.

## The pieces

| Module | What it has |
|---|---|
| `:connector:api` | The interfaces (`BackupConnectorFactory`, `BackupConnector`, `DeviceAppConnector`), descriptors and limits, the settings schema, the registry, the shared decorators, and the pipeline (`BackupOrchestrator`, `BackupCoordinator`). Pure Kotlin. Its `testFixtures` have in-memory fakes. |
| `:connector:contract-tests` | `BackupConnectorContractTest`: the checks every connector passes. |
| `:connector:android` | The Hilt registration (`@ConnectorKey`), the backup ledger in the app's database, the accounts, and `CloudBackup`, which starts backups as items arrive. |

## Checklist

1. **Create the module** `connector/<name>`, with the Android library and Hilt convention plugins, and `api(projects.connector.android)`.
2. **Describe the connector** in a `ConnectorDescriptor`:
   - an id (`lowercase-with-dashes`), and the service's name;
   - its capabilities: `VIDEO`, `VERIFY` if it can check that a copy is still there, `HASH_LOOKUP`, `METADATA_FROM_FILE_ONLY` if the service only reads dates and places from the file (date repair then runs first), or `DELEGATED_TO_DEVICE_APP` if another app on the phone does the upload;
   - its limits: largest photo and video, requests per second and per day, and the time zone its day ends in. The shared decorators enforce them, so the connector doesn't.
3. **Implement** `BackupConnectorFactory` and `BackupConnector`. For another app on the phone, implement `DeviceAppConnectorFactory` (with the app's `DeviceAppProfile`) and `DeviceAppConnector` instead.
   - `upload()` must be idempotent per content id, and gets an item that is already preprocessed: back up its bytes as they are.
   - Report failures as `UploadResult.Failed` with a `ConnectorError` that says whether to retry (`Transient`, `RateLimited`), or not (`QuotaExceeded`, `SignInNeeded`). Don't throw, and don't retry yourself: the decorators do.
   - Messages never name a file, a place or an account: they reach the diagnostic log.
   - `verify()` answers for every receipt. Without `VERIFY`, it answers `UNSUPPORTED`.
4. **Settings and sign-in:** declare a `SettingsSchema` (the generic settings screen comes with v1.1) and a `ConnectorAuthenticator` whose kind matches the descriptor.
5. **Register** the factory in the module's Hilt module:

   ```kotlin
   @Binds @IntoMap @ConnectorKey("my-cloud")
   abstract fun bindFactory(factory: MyCloudConnectorFactory): BackupConnectorFactory
   ```

6. **Test** with the kit: subclass `BackupConnectorContractTest` with a fake service (MockWebServer, or an in-memory fake), and tell it how to see what reached the service and how to make the service fail. Checks your fake can't support are skipped, not passed. Add `testImplementation(projects.connector.contractTests)`.
7. **Add the module** to `:app`'s dependencies.

## What every connector gets for free

- **Pipeline:** what arrived → preprocessing (photo details, date repair, quality; not for another app's connector, whose stored file is what gets uploaded) → limits → upload → a receipt in the ledger. A run stops at the first failure and the next arrival tries again.
- **Decorators**, outermost first: logging and metrics (`InstrumentedConnector`, which also turns a thrown exception into a failed run), retries with backoff and jitter (`RetryingConnector`), the rate limit (`RateLimitedConnector`), and the daily allowance (`QuotaTrackingConnector`).
- **Another app's cleanup:** for a `DeviceAppConnector`, whatever `removedFromPhone()` reports after `folderChanges()` settles becomes *backed up and removed from this phone*.

## Connectors so far

| Module | Connector | Kind |
|---|---|---|
| `:connector:google-photos-app` | `google-photos-app`: the Google Photos app on the receiving phone (PLAN.md §9.3.1) | Another app on the phone (`DeviceAppConnector`) |

# Publishing on Google Play

New personal developer accounts get production access only after a **closed test with at least 12 testers opted in for 14 days in a row** (docs/PLAN.md §14.4). This guide gets MediaSync into that closed test. Steps marked *Play Console* are done there by the account owner.

Texts to paste are in [listing.md](listing.md), and the graphics in [graphics/](graphics/).

## 1. Create the app (Play Console → Create app)

| Field | Answer |
|---|---|
| App name | `MediaSync: Photo Sync & Backup` |
| Default language | English (United States) |
| App or game | App |
| Free or paid | Free |
| Declarations | Accept the Developer Program Policies and US export laws |

## 2. App signing: use the release key (Test and release → App integrity)

GitHub releases and Play must share one certificate, so users can move between them without reinstalling (§14.4). So don't let Google generate a key.

1. Choose **Use a different key**, then **Export and upload a key from Java keystore**.
2. Download the PEPK tool and the encryption key that Play offers.
3. Run the command the page shows. It looks like this, with your keystore (alias `mediasync`):

   ```bash
   java -jar pepk.jar --keystore=mediasync-release.jks --alias=mediasync --output=mediasync-key.zip --include-cert --rsa-aes-encryption --encryption-key-path=encryption_public_key.pem
   ```

4. Upload `mediasync-key.zip`, then delete it.

The same key also signs uploads, so the App Bundle from each GitHub release can be uploaded as it is.

## 3. App content (Policy and programs → App content)

**Privacy policy:** `https://github.com/danyk20/mediasync/blob/main/docs/PRIVACY.md`

**Ads:** No, the app contains no ads.

**App access:** All functionality is available without special access. Reviewers need two phones; add this note:

> No login. Install MediaSync on two phones: choose "This phone receives" on one and "This phone sends" on the other, then scan the receiving phone's QR code with the sending phone. Photos then sync over the shared Wi-Fi (Sync now).

**Content rating** (IARC questionnaire):
- Category: All other app types.
- No violence, sexual content, profanity, drugs, gambling or user-generated content shared with other people.
- Does the app share the user's location with other users? **No.** Photos, and the places in them, go only to the user's own paired phones.
- Does it let users interact or exchange content with other people? **No.** Only between the user's own devices.

**Target audience:** 18 and over. This keeps the app outside the Families policy. It isn't designed for children.

**News app:** No. **Government app:** No. **Financial features:** None. **Health:** None.

### Data safety

These answers follow [Google's definitions](https://support.google.com/googleplay/answer/11416267). Data isn't "collected" when it stays on the device, or when it's sent end-to-end encrypted so that no one but sender and recipient can read it, the developer included.

- **Does your app collect or share any of the required user data types?** Yes, for one data type:

  | Data type | Collected | Shared | Processed ephemerally | Required or optional | Purpose |
  |---|---|---|---|---|---|
  | Device or other IDs | Yes | No | No | Optional (internet sync with wake-ups) | App functionality |

  This is the Firebase installation ID. Firebase Cloud Messaging uses it to wake the receiving phone (PRIVACY.md). It's sent to Google by the Firebase SDK, and through the developer's wake relay, which doesn't store it.

- **Photos and videos, and their location:** not collected. They go only to the user's own paired phone, end-to-end encrypted. The relay (Cloudflare TURN) and the user's Worker can't read them.
- **Is all of the user data collected by your app encrypted in transit?** Yes.
- **Do you provide a way for users to request that their data be deleted?** The app keeps no account or data on the developer's side. The installation ID is deleted with the app. Answer according to the options Play shows; if it insists, point to the privacy policy.
- **Independent security review:** No.

*Double-check before submitting:* if Play's form changed, the reasoning above still applies. Only the installation ID leaves the user's devices readable by anyone else.

### Sensitive permissions

**Photo and video permissions** (`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`). Play asks why the app needs broad access, rather than the photo picker:

> MediaSync is a photo and video sync and backup app. Its core function is to copy every photo and video in the folders the user chooses (for example the camera folder) to the user's other phone, automatically and in the background, including photos taken after setup. The photo picker can't provide ongoing access to new photos for unattended nightly syncing.

**Foreground service** (`FOREGROUND_SERVICE_DATA_SYNC`). Play asks for each type, with a short video:

- **Type:** Data sync.
- **Description:**

  > Transfers photos and videos between the user's own paired phones: when the user taps "Sync now" or "Turbo", during the nightly sync while charging, and while the receiving phone receives. The transfer must continue when the user leaves the app or the screen turns off; a notification shows its progress and can stop it.
- **Video:** a 30-second screen recording.
  1. Tap *Sync now* on the sending phone.
  2. Show the ongoing notification with its progress.
  3. Leave the app, and show the transfer continuing and finishing.

  Upload it to YouTube as *Unlisted* and paste the link.

## 4. Store listing (Grow users → Store presence → Main store listing)

Paste the texts from [listing.md](listing.md) and upload the graphics: the icon, the feature graphic, and the phone screenshots, in order.

## 5. Closed test (Test and release → Testing → Closed testing)

1. **Create track:** keep the default *Closed testing - Alpha*, or name it `Beta`.
2. **Testers:** create an email list with your 14 testers (12 is the minimum; 14 leaves a margin). Testers need a Google account on an Android phone.
3. **Countries:** the countries where your testers live.
4. **Create release:** upload `mediasync-vX.Y.Z.aab` from the latest [GitHub release](https://github.com/danyk20/mediasync/releases). The release name is filled in from the version. Paste the release notes from the GitHub release.
5. **Send for review.** The first review can take a few days.
6. When it's approved, copy the **opt-in link** and send it to the testers. Each one opens it, taps *Become a tester*, and installs MediaSync from Play.

**The 14 days start when 12 testers have opted in, and must be continuous.** If fewer than 12 stay opted in, they start over. Ask testers to keep the app installed and use it now and then. Play asks about their engagement when you apply for production.

New versions can be uploaded to the closed track at any time, without restarting the 14 days.

## 6. Production

After the 14 days: **Dashboard → Apply for production**. Answer the questions about the test, and how you acted on feedback. Then roll out in stages: 10 %, 50 %, 100 % (§14.4).

## 7. Uploading each release from GitHub (optional)

The Release workflow can upload each new version's App Bundle to Play by itself. It needs a Google Cloud service account that may release to testing tracks. Without it, nothing changes: upload the AAB from the GitHub release by hand, as in step 5.

1. **Google Cloud console** (<https://console.cloud.google.com>): create a project, e.g. `mediasync-play`, and enable the **Google Play Android Developer API** in it.
2. In that project: **IAM & Admin → Service accounts → Create service account**, e.g. `play-upload`. It needs no Google Cloud roles. Then **Keys → Add key → JSON**, which downloads the key.
3. **Play Console → Users and permissions → Invite new users:** the service account's email address (`play-upload@….iam.gserviceaccount.com`). Under **App permissions**, add MediaSync with **Release apps to testing tracks**, and nothing else. It may take up to a day before the account works.
4. **GitHub → Settings → Environments → release → Add environment secret:** `PLAY_SERVICE_ACCOUNT_JSON`, with the whole JSON file as its value. Then delete the downloaded file.
5. Optional, under **Settings → Secrets and variables → Actions → Variables**:
   - `PLAY_TRACK`: where releases go. `internal` (the default) reaches up to 100 internal testers within minutes, without review. `alpha` is *Closed testing - Alpha*, the closed test's track; a renamed closed track uses its name as Play shows it.
   - `PLAY_RELEASE_STATUS`: `completed` (the default) rolls the release out. Until the app's first release has passed review, Play accepts only `draft`: the release then waits in Play Console for you to roll it out.

The first App Bundle must be uploaded by hand (step 5), because Play only accepts uploads through the API for an app that already has one. Each release after that is uploaded when you approve the Release workflow, with the code's R8 mapping file so Play shows readable crash reports. Release notes are added in Play Console.

## Remaking the graphics

The screenshots and the feature graphic are rendered from the app's screens with sample data, and the icon from the launcher icon:

```bash
./gradlew :app:testDebugUnitTest --tests '*StoreGraphicsTest' -Pmediasync.storeGraphics -Proborazzi.test.record=true -Proborazzi.test.verify=false
```

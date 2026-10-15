# MediaSync privacy policy

*Effective 15 October 2026. Changes to this policy are listed in this file's history on GitHub.*

MediaSync copies photos and videos from your phones to another phone of yours, and nowhere else. It is open source: everything below can be checked in the code at <https://github.com/danyk20/mediasync>.

## The short version

- Your photos and videos go **directly from your phone to your other phone**, end-to-end encrypted. The developer never receives them, and no server can read them.
- MediaSync has **no accounts, no ads and no analytics**.
- Optional features use services you choose: your own Cloudflare account for syncing over the internet, Google's Firebase to wake the receiving phone, and the Google Photos app if you back up there.

## What stays on your phones

- **Your photos and videos, with all their details:** dates, places, camera information, album and file names. They are kept on the sending phone and copied to the receiving phone you paired, as they are, unless you choose in *Photo details* to remove the place or all details before sending.
- **Pairing keys.** Each phone creates its own keys, kept in Android's Keystore, and the paired phones exchange theirs through a QR code. They encrypt and authenticate every transfer.
- **Settings and the record of what was sent and received.**
- **The diagnostic log.** It records what MediaSync did, without file names or places. It leaves the phone only if you share it yourself, for example to report a problem.

## How your photos travel

- **On the same Wi-Fi, or with Turbo transfer (Wi-Fi Direct):** directly between the two phones, over an encrypted connection that only they can open.
- **Over the internet (optional):** directly between the phones when the networks allow it (WebRTC). Otherwise, through Cloudflare's relay (TURN), which forwards encrypted data it can't read.

  The two phones find each other through a small server, a Cloudflare Worker, that the receiving phone sets up **in your own Cloudflare account**. The developer has no access to it. It keeps only what it needs to introduce your phones:
  - their public keys;
  - the receiving phone's Firebase installation ID, to wake it;
  - messages between the phones while they're being delivered, sealed with a key only the two phones have;
  - this month's relay usage.

  The token you paste to set it up is used once and not stored. [Cloudflare's privacy policy](https://www.cloudflare.com/privacypolicy/) applies to your Cloudflare account.

## Waking the receiving phone (optional, with internet sync)

When a sending phone can't reach the receiving phone over the internet, it asks for it to be woken up. MediaSync uses Google's **Firebase Cloud Messaging** for this, and only on a receiving phone with internet sync turned on.

- **On the receiving phone:** Firebase gives the phone an installation ID, a random identifier. MediaSync gives it to your own Worker.
- **The wake relay:** Firebase messages can only be sent with the app's own Firebase key, so your Worker passes wake requests to a small relay run by the developer. The relay forwards the request to Firebase.
  - It doesn't store the installation ID.
  - To limit how often a phone can be woken, Cloudflare's rate limiter keeps a one-way hash of it for about a minute.
- **The message itself** says only "wake up". It carries no photos, names or places.

Firebase Analytics is switched off. [Google's privacy policy](https://policies.google.com/privacy) and the [Firebase privacy information](https://firebase.google.com/support/privacy) apply to Firebase.

## Backing up to Google Photos (optional)

MediaSync doesn't upload anything to Google Photos. It keeps received files in the *MediaSync* folder, and **the Google Photos app on that phone** backs them up if you turn that on in Google Photos. [Google's privacy policy](https://policies.google.com/privacy) applies.

## Permissions

| Permission | Why |
|---|---|
| Photos and videos | The sending phone reads the folders you choose; the receiving phone stores what it receives. |
| Location in photos | To send photos with their place, as taken. Without it, Android removes places before MediaSync reads them. |
| Camera | To scan the pairing QR code. Nothing is recorded. |
| Nearby devices (Android 13+), or location on Android 10 to 12 | Turbo transfer only: to find the other phone over Wi-Fi Direct. Not used for location. |
| Notifications | Transfer progress, problems and relay allowance warnings. |
| Network, Wi-Fi, running in the background | To transfer while the screen is off or at the scheduled time. |

## Children

MediaSync isn't directed at children.

## Contact

Questions about this policy: open an issue at <https://github.com/danyk20/mediasync/issues>.

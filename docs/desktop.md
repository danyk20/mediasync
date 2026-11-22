# MediaSync on a computer

The desktop app runs on macOS, Windows and Linux. It receives the photos and videos your phones send, keeps them in its own folder and copies them into a backup folder you choose, such as an external disk or a network share. A computer can also send its own photo folders: see [Sending from a computer](#sending-from-a-computer).

The installers are **not signed**: signing costs a yearly fee per platform, which this project doesn't pay. Your system warns about that the first time (see below). To make sure the file is the one the project built, check its checksum.

## Download and verify

1. Open the [latest release](https://github.com/danyk20/mediasync/releases/latest) and download the file for your system:

   | System | File |
   |---|---|
   | macOS (Apple Silicon: M1 or newer) | `MediaSync-X.Y.Z-macos-arm64.dmg` |
   | macOS (Intel) | `MediaSync-X.Y.Z-macos-x64.dmg` |
   | Windows 10/11, 64-bit | `MediaSync-X.Y.Z-windows-x64.msi` |
   | Debian, Ubuntu and similar | `MediaSync-X.Y.Z-linux-amd64.deb` |
   | Fedora, openSUSE and similar | `MediaSync-X.Y.Z-linux-x86_64.rpm` |

   Also download the checksum file for your system, `SHA256SUMS-desktop-macos-arm64`, `-macos-x64`, `-windows-x64` or `-linux-x64`.
2. Compare the file's SHA-256 with the line in the checksum file:
   - macOS and Linux: `shasum -a 256 MediaSync-*` (macOS) or `sha256sum MediaSync-*` (Linux)
   - Windows (PowerShell): `Get-FileHash .\MediaSync-*.msi -Algorithm SHA256`

   The two values must be identical. With the [GitHub CLI](https://cli.github.com/), `gh attestation verify <file> --repo danyk20/mediasync` also proves the file was built by this repository's release workflow.

## Install

### macOS

1. Open the `.dmg` and drag **MediaSync** into **Applications**.
2. Open MediaSync. macOS says it can't verify the app and doesn't open it. (On macOS 14 and older, right-click the app and choose **Open** instead.)
3. Open **System Settings → Privacy & Security**, scroll to the message about MediaSync and click **Open Anyway**, then confirm with your password. You need to do this once per version.
4. When macOS asks to allow MediaSync to find devices on your local network, choose **Allow**. Without it, phones can't reach the app. If you refused, turn it on in **System Settings → Privacy & Security → Local Network**.

On macOS, Finder's **Get Info** shows the version as 1.x.y, because macOS installers can't start at 0; the app's own Settings shows the real version.

### Windows

1. Run the `.msi`. It installs for your user only and needs no administrator rights.
2. SmartScreen says "Windows protected your PC": click **More info**, then **Run anyway**.
3. When Windows Defender Firewall asks, allow MediaSync on **private networks** (your home or office Wi-Fi). On public networks, leave it blocked.

MediaSync appears in the Start menu and on the desktop.

### Linux

```bash
sudo apt install ./MediaSync-*.deb        # Debian, Ubuntu
sudo dnf install ./MediaSync-*.rpm        # Fedora
```

MediaSync appears in the applications menu (Utility). Phones connect to it over the local network, so the firewall must let them in: TCP port 47420 (MediaSync uses another free port if that one is taken; the app then shows it) and UDP port 5353, which mDNS uses to announce the computer. Allow both from your local network only, for example:

```bash
# ufw
sudo ufw allow from 192.168.1.0/24 to any proto tcp port 47420
sudo ufw allow from 192.168.1.0/24 to any proto udp port 5353
# firewalld
sudo firewall-cmd --permanent --add-port=47420/tcp --add-service=mdns && sudo firewall-cmd --reload
```

Use your own network's address range. The identity is kept in the desktop's secret store (GNOME Keyring or KWallet through the Secret Service); without one, MediaSync keeps it in its data folder in a protected file.

## Pair a phone

1. Open MediaSync on the computer: it shows a QR code.
2. On the sending phone, open MediaSync and scan the code (it asks for the camera the first time).
3. Both must be on the same network. Photos and videos arrive on the computer once the phone starts a sync.

Pair each phone once. You can unpair a phone in the app.

## Sending from a computer

A computer can also send its photos and videos to a receiving phone or to another computer running MediaSync. A computer either receives or sends: it doesn't do both.

1. On first start choose **This computer sends**. To change it later, choose **Reset MediaSync** in the settings: the app closes and asks again.
2. Choose **Add folder…** and pick the folders to send. Everything in a folder and below it is sent, once. You can also choose which days to send.
3. Pair the computer with the receiving device, which accepts it once:
   - On the receiving phone, open MediaSync, choose **Add sending phone**, then **Share pairing link** and send the link to this computer (a message, an email or a notes app).
   - On a receiving computer, choose **Add sending device**, then **Copy link**.
   - On this computer, choose **Pair with receiving device**, then **Paste the receiver's link**. On the receiving device, tap **Allow**.

   The link works once, for 5 minutes. Both devices must be on the same network.
4. The computer syncs on the schedule the receiving device set, or when you choose **Sync now**. A timed schedule syncs at each due time while the computer is plugged in. A scheduled time the computer missed (asleep, switched off or on battery) is caught up once, as soon as it's on and plugged in again. When the last one is more than an hour overdue, the sending screen shows a missed-sync note. *Whenever charging* syncs when MediaSync starts and again after the folders have been quiet for 30 seconds, while plugged in. *Manual* syncs only when you choose **Sync now**. A computer that isn't paired never syncs by itself.
5. A photo or video that was sent isn't sent again: a sync after the first one sends nothing new, and renaming a file doesn't send it again.

## Backup folder

In the app's settings, **Choose folder** picks where received photos and videos are copied: a disk, or a network share you have mounted. If the folder isn't reachable, MediaSync keeps the files in its own folder and tries again every few minutes.

## Start at login

The first time a phone pairs, MediaSync starts at login, hidden in the menu bar or tray, so phones can send without you opening the app. Click the tray icon to show the window; **Quit** from the tray menu stops it. You can turn *Start at login* off in the settings, and it stays off.

A computer that sends doesn't turn *Start at login* on by itself: its syncs run while MediaSync is open. To have them run without you opening the app, turn *Start at login* on in the settings.

## Where the data lives

| System | Folder |
|---|---|
| macOS | `~/Library/Application Support/MediaSync` |
| Windows | `%APPDATA%\MediaSync` |
| Linux | `~/.local/share/mediasync` (or under `$XDG_DATA_HOME`) |

It holds the identity (a certificate), the database, the settings, the folder for received files (`inbox`) and the log (`logs`). The log is the file to attach when you report a problem.

## Uninstall

- **macOS:** turn off *Start at login* in the app first, quit it, then drag MediaSync from Applications to the Bin.
- **Windows:** Settings → Apps → MediaSync → Uninstall.
- **Linux:** `sudo apt remove mediasync` or `sudo dnf remove mediasync`.

Uninstalling leaves the data folder above and your backup folder. Delete the data folder to remove the identity and the settings too: the phones must then be paired again.

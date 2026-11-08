# Security Policy

## Data handling
- BlackLab stores all user content locally: a Room database in app-private storage and files in `Internal storage/BlackLab`.
- There is no BlackLab server, account system or analytics SDK. Google Play services components used for scanning and OCR are governed by Google's terms (see README › Privacy).
- Optional biometric / device-credential lock protects the app and/or the Professional account.
- Backups (`BlackLab/Backups/*.blbackup`) are encrypted with AES-256-GCM in authenticated 1 MiB chunks, so a changed, reordered or truncated backup is rejected. The data key is random per install and held in the Android Keystore-encrypted store; with a backup passphrase set, each backup also carries that key wrapped with PBKDF2-HMAC-SHA256 (310,000 iterations), so it can be restored on another phone. Without a passphrase a backup restores only on the phone and install that made it. Backups made before 5.10 (`*.zip`) are plain; Settings offers to delete them.
- A restore is decrypted, unpacked (known entries only) and integrity-checked in a scratch folder, and the current data is saved first, before anything is replaced.

## Secrets in this repository
No signing keys, keystore passwords, API keys or tokens are committed. The following are excluded via `.gitignore` and must never be added:

| Path / pattern | Purpose |
|---|---|
| `keystore/`, `*.jks`, `*.keystore` | Release signing key |
| `keystore.properties`, `signing.properties` | Keystore passwords |
| `local.properties` | Machine-specific SDK path |
| `.env*`, `google-services.json` | Environment secrets / service config |

`scripts/secret-scan.sh` enforces this. It runs in CI on every push and pull request and fails the build if any of the paths above, or a credential-shaped string (Google/GitHub/ElevenLabs/Anthropic/OpenAI/AWS/Slack keys, private keys, plain-text signing passwords), is tracked.

Release builds read the signing configuration from `keystore/keystore.properties` at build time and fall back to the debug key when it is absent.

## API keys

Assistant API keys (ElevenLabs, Google) are never compiled into the app or committed here. On the phone they are encrypted with a hardware-backed Android Keystore key, excluded from Android backups and device transfers, and the desktop link reports only whether a key is set. On the computer they live in `~/.config/blacklab-desktop/secrets.json` (mode 600). The desktop link requires a pairing code and the companion listens on 127.0.0.1 only.

## Updates

In-app updates come only from this repository's GitHub Releases, over HTTPS, from GitHub's own hosts. Before an APK is installed it must:

1. match the SHA-256 published next to it in the release;
2. be signed with the same certificate as the installed app;
3. be the same package with a higher version code.

Replacing both the APK and its checksum still fails check 2 without the release key, and Android re-checks the signature before replacing the app.

## Problem reports

Crash, ANR and native-crash reports are written to app-private storage, excluded from backups, and leave the phone only when you share them.

## Signatures

Saved signatures and initials are PNGs encrypted with AES-256-GCM. The key is created in the Android Keystore and never leaves the phone. They live in app-private storage (`files/signatures/`), are excluded from cloud backup and device transfer, and are never included in cloud sync or the desktop link. With **Confirm before signing** on (the default), a saved signature is placed only after fingerprint, face or screen-lock confirmation. Deleting one overwrites the file before removing it.

## Entry points other apps can reach

BlackLab accepts files from "Open with" and the share sheet, and deep links from its shortcuts. Links that point into BlackLab's own private folders, or at its own file provider, are refused. Deep links cannot carry file paths. The only assistant request an outside intent can start is the **Brief me** shortcut, so other apps can't send email or change settings through BlackLab.

## BlackLab Desktop

- **Phone link:** every call except `/api/ping` needs the pairing code, sent as a bearer header (never in a URL); wrong codes lock the address out. Browser requests are accepted only from BlackLab Desktop's own pages. Over Wi-Fi the link is plain HTTP, so file access, API key storage and backups work only over USB (adb), and security and link settings can't be changed over Wi-Fi or through Drive.
- **Desktop AI tools:** side-effect tools run only when the request asks for that kind of action, and after the model has read a file, document or chat it can only open links the user typed.

- **Companion:** listens on 127.0.0.1 only. It accepts browser requests only from its own origin and the GitHub Pages origin, requires an `X-BlackLab` header, and checks the Host header to stop DNS rebinding.
- **Web page:** has a Content-Security-Policy that allows only its own scripts and Google's sign-in library to run.
- **Google OAuth web client ID:** public by design. It works only from the JavaScript origins you authorize in Google Cloud.

## Reporting a vulnerability
Please open a private [security advisory](https://github.com/paulmmoore3416/BlackLab/security/advisories/new) rather than a public issue. Include steps to reproduce, the affected version and the device/Android version.

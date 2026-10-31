# Security Policy

## Data handling
- BlackLab stores all user content locally: a Room database in app-private storage and files in `Internal storage/BlackLab`.
- There is no BlackLab server, account system or analytics SDK. Google Play services components used for scanning and OCR are governed by Google's terms (see README › Privacy).
- Optional biometric / device-credential lock protects the app and/or the Professional account.
- Backups (`BlackLab/Backups/*.zip`) contain the database and settings in plain form — store copies somewhere you trust.

## Secrets in this repository
No signing keys, keystore passwords, API keys or tokens are committed. The following are excluded via `.gitignore` and must never be added:

| Path / pattern | Purpose |
|---|---|
| `keystore/`, `*.jks`, `*.keystore` | Release signing key |
| `keystore.properties`, `signing.properties` | Keystore passwords |
| `local.properties` | Machine-specific SDK path |
| `.env*`, `google-services.json` | Environment secrets / service config |

Release builds read the signing configuration from `keystore/keystore.properties` at build time and fall back to the debug key when it is absent.

## API keys

Assistant API keys (ElevenLabs, Google) are never compiled into the app or committed here. On the phone they are encrypted with a hardware-backed Android Keystore key, excluded from Android backups and device transfers, and the desktop link reports only whether a key is set. On the computer they live in `~/.config/blacklab-desktop/secrets.json` (mode 600). The desktop link requires a pairing code and the companion listens on 127.0.0.1 only.

## Reporting a vulnerability
Please open a private [security advisory](https://github.com/paulmmoore3416/BlackLab/security/advisories/new) rather than a public issue. Include steps to reproduce, the affected version and the device/Android version.

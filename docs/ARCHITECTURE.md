# BlackLab architecture

BlackLab is a single-module Android app (`:app`) written in Kotlin with Jetpack Compose. It favors a small number of well-defined layers over heavy frameworks: no DI framework, no network layer, and plain coroutines + `Flow` for reactivity.

```
┌──────────────────────────── UI (Compose, Material 3) ────────────────────────────┐
│ Home · Camera · Recorder · Library · Documents/Editor · Journal · Tasks ·        │
│ Schedule · Education · Settings · Onboarding/Lock                                │
└───────────────┬───────────────────────────────┬──────────────────────────────────┘
                │ collectAsState(Flow)          │ app.io { … } (app-wide scope)
┌───────────────▼───────────────┐   ┌───────────▼──────────────┐   ┌──────────────────┐
│ data/  Room DAOs, Ops, logic  │   │ docs/  HTML ⇄ blocks,    │   │ recorder/        │
│ (tasks, events, education…)   │   │ DOCX, PDF, MD, import    │   │ AudioRecord,     │
└───────────────┬───────────────┘   └───────────┬──────────────┘   │ AAC/WAV, STT,    │
                │                               │                  │ FGS              │
┌───────────────▼───────────────────────────────▼──────────────┐   └──────────────────┘
│ core/  Profile · Settings · BlackLabStorage · Time/Recurrence │
│        BackupManager                                           │
└────────────────────────────────────────────────────────────────┘
        reminders/ ReminderScheduler (one exact alarm) · study/ StudyTimer
```

## App container
`BlackLabApp` creates an `AppContainer` (database, settings, storage, content store, backups, an application `CoroutineScope`). Composables reach it via `rememberApp()`; writes run in `app.io { }` so they survive screen navigation.

## Accounts
Every entity carries a `profile` column (`PERSONAL` / `PROFESSIONAL`). The active profile lives in `SettingsRepository.state`; queries are keyed by it, and storage paths are `BlackLab/<Profile>/<Folder>`. The theme accent follows the profile so the active account is always obvious.

## Storage model
- **Room** holds structured data and *metadata* for rich content.
- **Rich content (documents, journal)** is written as standalone `.html` files via `ContentStore`, so it is portable and avoids SQLite's row-size limits for embedded images. Writes are atomic (temp file + rename).
- Paths in the database are **relative** to the BlackLab root, so data keeps working if the root moves between app storage and public storage (with automatic migration once All-files access is granted).
- `BackupManager` checkpoints the WAL and zips the database + settings daily into `BlackLab/Backups`.

## Rich-text editor
The editor is a `contenteditable` page in `assets/editor/editor.html` hosted in a `WebView`. Kotlin talks to it through `EditorController` (`evaluateJavascript`) and receives state through a `@JavascriptInterface` bridge (formatting state, word counts, edit ticks for debounced autosave). The Compose `EditorRibbon` provides Word-style tabs. File access is disabled in the WebView; images are embedded as downscaled data URIs.

## Export pipeline
`HtmlToBlocks` parses editor HTML (jsoup) into a neutral block model — paragraphs with runs and paragraph styles, tables, images, rules, page breaks. Each exporter consumes the same model:
- `DocxWriter` — Office Open XML package (styles, numbering, tables, inline images, hyperlinks, section/page setup)
- `PdfWriter` — native `PdfDocument` + `StaticLayout` renderer with line-accurate pagination, tables and images (selectable text)
- `TextExport` — Markdown and plain text
- `DocxReader` / `Markdown` — imports back into editor HTML
- `Printer` — system print dialog for high-fidelity “Save as PDF”

## Reminders
Instead of scheduling one alarm per reminder, `ReminderScheduler` keeps **one exact alarm** pointed at the next due item across all sources (tasks, event occurrences, assignments, class meetings, certification renewals/exams, morning agenda, journal nudge). When it fires it posts everything due since the last check and re-arms. Any data change calls `requestReminderSync()` (debounced). Boot, time and timezone changes re-arm via `BootReceiver`.

## Recurrence
`Recurrence` implements an RRULE subset (`FREQ`, `INTERVAL`, `BYDAY`, `UNTIL`, `COUNT`) shared by tasks, events and `.ics` import/export. Event occurrences are expanded per visible range; single occurrences are excluded with `exDates`.

## Camera
`CameraEngine` binds CameraX use cases per mode (Preview + ImageCapture, or Preview + VideoCapture) to avoid stream-combination limits. It queries capabilities at bind time (extensions, Ultra HDR output format, supported qualities, HLG dynamic range, stabilization, frame-rate ranges) and falls back gracefully when a combination fails to bind.

## Audio
`AudioEngine` reads PCM from `AudioRecord` on a high-priority thread, applies gain, computes levels, and writes to a sink: `WavSink` (RIFF header patched on close) or `AacSink` (`MediaCodec` AAC-LC → `MediaMuxer` MPEG-4). Pausing simply stops writing, so timestamps remain continuous. `LiveTranscriber` downmixes/resamples the same PCM to 16 kHz mono and feeds Android's on-device `SpeechRecognizer` through `EXTRA_AUDIO_SOURCE` with segmented sessions. Recording runs in `RecorderService`, a microphone foreground service.

## Education math
- `Grades.coursePercent` — weighted average across categories, re-normalized over categories that have graded work
- `Grades.neededOnRemaining` — percentage required on ungraded weight to reach the course target
- `Grades.gpa` — credit-weighted quality points on a 4.0 scale (pass/fail excluded)
- `Srs.review` — SM-2 spaced repetition with Again/Hard/Good/Easy grading

## Assistant
`assistant/Assistant.kt` routes every request through an ordered set of regex intents that call the same repositories the screens use (`TaskOps`, DAOs, `ContentStore`, services), so the assistant never has a separate copy of app state. Unmatched input goes to Gemini Nano (`Nano.kt`, ML Kit Prompt API over AICore) with a short command catalogue; Nano either returns `COMMAND: …`, which is routed again with Nano disabled to prevent loops, or `ANSWER: …`. Replies carry optional navigation routes, intents and UI actions (scanners, nameplate camera, Google sign-in) that the phone screen performs. Actions that send email, or change settings from the desktop, wait for a spoken or typed confirmation.

`LocalIndex` is an in-memory BM25 index over documents, journal, transcripts, tasks, events and courses, rebuilt lazily when data changes. `MeetingActions` and `Nameplate` reuse it and ML Kit OCR. `Secrets` stores API keys as AES-GCM blobs under an Android Keystore key; keys can be provisioned once by pushing `BlackLab/.provision/keys.json`, which is deleted after import.

## Desktop link & sync
`core/SettingsSchema.kt` describes every setting (type, label, help, range, options). The phone's settings search, the assistant's "set X to Y" and every BlackLab Desktop settings page are generated from it, and desktop writes go back through `SettingsSchema.apply`, which validates them.

- **USB / Wi-Fi:** `link/LinkServer.kt` is a small HTTP/1.1 JSON API on port 8765, bound to localhost (reached over USB with `adb forward`) and optionally to Wi-Fi. Every call except `/api/ping` needs the 8-character pairing code; repeated bad codes lock the client out. `LinkHub.rev` increases on every change so the desktop long-polls `/api/changes`.
- **Anywhere:** `link/CloudSync.kt` writes `blacklab-sync.json` (snapshot, schema, results) to the hidden Drive `appDataFolder` and reads `blacklab-inbox.json` (settings changes and commands queued by the web UI). Each side writes only its own file, so they never race.
- **Desktop:** `desktop/web` is a static single-page app (GitHub Pages and local). `desktop/companion/blacklab_desktop.py` (standard library only) serves it on 127.0.0.1:8770, keeps `adb forward` up while the phone is plugged in, and runs the desktop assistant: rules first, then a local Ollama model with tool calling.

## Files, viewers & editors
`files/FileKinds` maps every file to a kind and an in-app route (`media?` for photos/videos, `player?`
for audio, `view?` for everything else). `ui/viewer/FileViewerScreen` dispatches to the PDF viewer,
text/code editor, spreadsheet editor, document reader (Word/RTF/ODT/PowerPoint/EPUB → sanitized HTML in
a WebView with JavaScript, file and network access off), ZIP browser, calendar or hex view. Files that
other apps hand to BlackLab are copied to the external cache (`storage.openedDir`); saving them writes
into BlackLab › Imports, never back to the temporary copy.

PDFs render with the platform `PdfRenderer` (thread-safe wrapper + LRU bitmap cache) and are edited with
PdfBox-Android: annotations are drawn in "displayed page" space through a matrix that honours `/Rotate`.
Every tool writes to a temp file first and the user picks "save as copy" or "replace original";
replacement is atomic. The spreadsheet engine keeps raw cell text (formulas start with `=`), evaluates
with a small recursive-descent parser with cycle detection, and writes minimal OOXML with cached values.

## Music, audio & video editing
`media/PlaybackService` is a Media3 `MediaSessionService` (notification, lock screen, Bluetooth, audio
focus, becoming-noisy) with equalizer/bass/virtualizer/loudness effects bound to the player's audio
session. Screens talk to it through a `MediaController`. The audio editor decodes to interleaved 16-bit
PCM in memory (long files fall back to mono), applies immutable edits for cheap undo, previews through
`AudioTrack`, and re-encodes with the recorder's AAC/WAV sinks; tempo/pitch use Media3's Sonic processor.
The video editor builds a Media3 `Composition` (clipped media items, image items, a looping music
sequence) with one effect chain shared by the live ExoPlayer preview and the Transformer export; if a
device can't keep HDR with effects it retries with HDR→SDR tone-mapping.

## Brainstorm (notebooks, notes, collaboration)

- **Structure in Room, content in files.** `notebooks` → `nb_sections` (cascade) → `nb_pages` hold names, order, colors, subpage level, search text, tag index and soft-delete time. The canvas of each page is `Brainstorm/pages/<id>/page.json` with `assets/` and `versions/` beside it, so moving a page between sections or notebooks is a single row update. Schema v2 is created by `Migrations.M1_2` (its SQL is generated from Room's exported schema).
- **Canvas engine.** `assets/notebook/page.html` is served from `https://bl.local/` via `shouldInterceptRequest` (assets are decrypted on the fly; every other host is blocked). The model is `{title, bg, items[], ink[]}`: text boxes hold sanitized HTML; images, files and recordings reference `asset:` names; ink strokes are point lists with pressure (or shape endpoints) drawn as smoothed SVG paths. JS owns the DOM and undo stack, debounces `onSave(json)` through a `@JavascriptInterface`, and Kotlin writes the latest JSON on a single-threaded dispatcher.
- **Derived data.** On save, `PageText.extract` builds plain text (text boxes, transcripts, OCR of pictures, file names) for search and the assistant index, and a `tag|done|text` line per tagged paragraph for the Tags summary. History snapshots are kept every 10 minutes (last 40).
- **Locked sections.** PBKDF2-HMAC-SHA256 (210k) derives an AES-256 key; files get a `BLE1` header, a 96-bit IV and GCM. Only a salt and an encrypted check value are stored. Keys live in memory (`SectionKeys`, 20-minute idle timeout). Locking or unlocking re-encrypts pages, assets and history. Locked pages store no preview, search text or tags, and can't be Live Shared.
- **Notes** are Room rows (`notes`, `note_labels`) with checklist items and attachments as JSON. Time reminders join the single-alarm `ReminderScheduler` (repeats advance after firing). Place reminders use platform proximity alerts, re-registered at boot and on change.
- **Live Share.** `LinkServer` routes `/s/<token>` before the pairing check to `LiveShare.serve`. Tokens are 128-bit, scoped to one page or note, expiring and revocable; responses are same-origin with a strict CSP; guest posts are length-limited and rate-limited (20/min per link). Guests get a static, sanitized render (`PageHtml.static`) polled every 4 s, and can comment or (notes) tick/add items; the open note editor adopts changes that differ from what it last wrote.
- **Exports.** `PageHtml.linear` flows boxes top-to-bottom (tags become symbols, ink becomes one PNG) into the existing DOCX/PDF/Markdown exporters; `PageHtml.static` keeps the exact layout for HTML export and printing. `.blnotebook` is a zip with `manifest.json` and the page folders (locked sections stay encrypted).

## Widgets & quick capture

- **Glance widgets.** `TodayWidget`, `ChecklistWidget` and `CaptureWidget` (package `widgets/`) render with Jetpack Glance. Each loads a plain model (`Widgets.today`, `Widgets.checklist`) before composing and re-loads it whenever `Widgets.version` changes, so a running session and a cold start show the same data.
- **Freshness.** `Widgets.watch` puts a Room `InvalidationTracker` observer on `tasks`, `notes`, `events`, `courses`, `class_meetings` and `assignments`, and watches the active account and privacy setting. Changes trigger a debounced `updateAll`. The Today widget also refreshes every 30 minutes (day roll-over) and after boot or clock changes.
- **Tick boxes** are `ActionCallback`s that write through the same `TaskOps` / `NoteOps` used by the screens (recurrence, reminders, place alerts). Checklist ticks carry the item text as well as its index.
- **Privacy.** `widgetPrivacy = COUNTS` renders counts only. A widget whose host category is `keyguard` always does. The Checklist configuration activity checks that the widget id belongs to BlackLab and asks for the app lock before listing notes.
- **Quick capture** (`capture/QuickCaptureActivity`) is a separate translucent, non-exported task that only inserts. It saves new notes, checklists, voice notes (reusing the Brainstorm recorder sheet), photo notes (OCR runs after the sheet closes) and tasks, or appends items to the pinned checklist. Because it never reads existing content, it skips the app lock.

## Updates

- `update/Updater` asks `api.github.com/repos/<owner>/BlackLab/releases/latest` and picks the `BlackLab-vX.Y.Z.apk` and `.apk.sha256` assets. Every URL, including redirect targets, must be HTTPS on `github.com`, `api.github.com` or `*.githubusercontent.com`.
- The APK is hashed while it downloads. `verify` then requires the SHA-256 to match the published checksum, the archive's signer set to contain the installed app's signer (`PackageManager` signing info, rotation-aware), the same package name and a higher `longVersionCode`.
- Install re-verifies the file, then streams it into a `PackageInstaller` session (`USER_ACTION_NOT_REQUIRED` where Android allows it). `UpdateReceiver` launches the system confirmation when needed, or posts a notification if BlackLab is in the background.
- The weekly check is a unique periodic `WorkManager` job, scheduled only when the setting is on and the build isn't debug.

## Problem reports

- `core/CrashLog` wraps the default uncaught-exception handler (the previous handler still runs) and writes one text report per crash into `filesDir/crashes`, keeping the newest 30. Reports contain the type, time, version, device, thread and stack trace.
- On start it imports `ApplicationExitInfo` records newer than the last import: ANR (with the thread dump), native crash, excessive resource use and start-up failure. The first run only sets the baseline.
- Unseen and announced timestamps drive the Settings badge and a one-time "a report was saved" snackbar. Sharing goes through `FileProvider`.

## Signatures & forms

- `core/Signatures` stores signatures and initials as PNGs encrypted with AES-256-GCM under a Keystore key (`blacklab_signatures_v1`), in `filesDir/signatures` with a JSON index (one default per kind). The 4.1–4.3 unencrypted `signature.png` is migrated, overwritten and deleted on first use. `files/signatures/` is excluded from backup and device transfer.
- `ui/signature`: `SignatureCaptureDialog` (raw pointer input, so strokes start on touch; quadratic smoothing; or typed in a script font) → trimmed transparent PNG. `SignatureGate` requires biometric/device credential (3-minute grace) when *Confirm before signing* is on. `SignatureChooserDialog` and `SignaturePickerRow` are shared by PDF markup, the document editor (data-URI `<img>`, exported to PDF/DOCX) and notebook pages (scaled picture asset).
- *Sign a document* (`SignDocs.toPdf`): PDF is copied; images go through `PdfTools.imagesToPdf`; DOCX/RTF/Markdown/HTML/text go `DocImporter` → `DocExporter` (PDF). The result lands in `Documents/Signed` and opens with `view?…&sign=true`, which starts `PdfAnnotator` on the Sign tool.
- **Forms.** `PdfTools.formFields` reports text, check, choice, radio and signature fields with tooltips. `core/FormAutofill` normalizes field names ("Applicant_LastName" → "applicant last name") and maps them to profile slots. `fillForm` draws values into appearance streams (`NeedAppearances` off, `refreshAppearances` before `flatten`) and draws images inside signature widgets.

## Theme & live Home

- `ui/theme/AccentPalette`: nine palettes with light and dark shades of accent, complementary secondary and tertiary (all ≥ 4.5:1). `ThemeChoice` comes from settings, one palette per account plus an optional secondary override. Light schemes get matching containers and faintly accent-tinted surfaces. Dark selections use an accent tint unless a secondary was picked.
- `core/AutoTheme`: NOAA sunrise/sunset from the rounded last-known location, or custom hours. `rememberThemeMode` resolves AUTO/SCHEDULE to DARK/LIGHT, re-checked by `rememberNow()`.
- `ui/components/LiveClock`:
  - `rememberNow()` ticks on minute boundaries, on resume and on time, date or zone changes.
  - `rememberDeviceCalendarVersion()` observes `CalendarContract` with debouncing.
  - Home uses both to filter today's agenda live.
- `core/ActivityFeed` builds "Since you last looked" from existing timestamps: task completedAt/createdAt/reminderAt, event createdAt, recordings, document updatedAt, notes and guest comments. The window runs from the last Home visit (stored per account), capped at 3 days.
- `core/IncomingGuard` filters what other apps send to the exported `MainActivity`:
  - refuses `file://` and `content://` links into BlackLab's private storage;
  - drops deep-link routes that carry file paths;
  - accepts only the "brief me" assistant request.

## Motion & feel

- `ui/theme/Motion`: shared easings (`enter`, `exit`, `emphasized`, `settle`). With `Motion.reduced` (Settings › Reduce motion, or the system animator scale at 0) every duration is 0 and springs snap.
- `ui/components/SharedMotion`: the NavHost sits in a `SharedTransitionLayout`. Each destination is declared with `screen()`, which provides its `AnimatedVisibilityScope`. `Modifier.sharedCard(key)` gives container transforms (`task-<id>`, `note-<id>`) between list rows and detail screens.
- `ui/components/Feel`: `Haptics` maps tick, confirm, reject, toggle and long-press to `HapticFeedbackConstants`, gated by Settings › Haptic feedback. Shared components (`LabeledSwitchRow`, `ConfirmDialog`, `pressable`) apply it everywhere.
- `ui/components/Skeleton`: shimmer placeholders (`SkeletonRows`, `SkeletonPage`), still when motion is reduced.
- `core/FrameStats`: `Window.OnFrameMetricsAvailableListener` per route. A frame counts as late when (total − unknown delay) exceeds 1.5 × its deadline. Results are shown in Problem reports.
- Typography is Inter (`res/font/inter.ttf`, variable, weights 300–700). Display styles and `MonoDigits` use tabular figures.
- `src/main/baseline-prof.txt` covers app classes. `profileinstaller` installs it for apps not delivered through Play.
- Splash: `android:windowSplashScreenAnimatedIcon` = `drawable/splash_icon` (an animated vector).

## Testing & CI

- JVM tests (`app/src/test`) cover pure logic. On-device tests (`app/src/androidTest`) run against the debug build's real container and clean up after themselves.
- `MigrationTest` uses `MigrationTestHelper` with the exported schemas (added to the androidTest assets) to validate the 1 → 2 upgrade exactly.
- `.github/workflows/android.yml`:
  1. `secrets` runs `scripts/secret-scan.sh`, which checks for tracked keystores, property files and credential-shaped strings.
  2. `build` runs unit tests and the debug and release builds.
  3. `device-tests` runs `connectedDebugAndroidTest` on an API 35 emulator with KVM.

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

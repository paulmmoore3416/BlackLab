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

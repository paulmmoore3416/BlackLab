# Changelog

All notable changes to BlackLab are documented here. The project follows [Semantic Versioning](https://semver.org/).

## [4.1.0] — 2026-10-31

### Added
- **Open anything in BlackLab** — one universal viewer opens PDF, Word (.docx), RTF, OpenDocument text, PowerPoint (.pptx), EPUB e-books, Excel (.xlsx) and CSV/TSV, Markdown, HTML, JSON, XML, code, logs, calendar (.ics) files and ZIP archives, plus a hex view for anything else. Every file in the Library now opens in-app, and BlackLab registers as an **“Open with”** target for documents, media and archives from other apps (those open from a temporary copy; saving puts the result in BlackLab › Imports).
- **PDF viewer & tools** — continuous scroll with pinch/double-tap zoom, page thumbnails, go-to-page, night mode, full-text search and copy. Tools: **mark up & sign** (pen, highlighter, text, saved finger signature, white-out, boxes, check marks), **organize pages** (reorder, rotate, duplicate, delete, extract), **merge** with other PDFs or photos, **split** by ranges, **fill forms** (with optional flattening), **watermark**, **page numbers**, **password protect** (AES-256) / unlock, save pages as pictures, reduce file size, export text, edit properties. Every change offers “save as copy” or “replace original”.
- **Text & code editor** — undo/redo, find & replace (match case, whole word, regex), go to line, line numbers, word wrap, font size, Markdown/HTML live preview, JSON format/minify, text tools (case, sort, de-duplicate, trim, number lines…), save as, export to PDF, convert to a BlackLab document. Read-only fast viewer for very large logs.
- **Spreadsheet editor** — CSV/TSV/XLSX with multiple sheets, a formula bar and formula engine (SUM, AVERAGE, MIN, MAX, COUNT, MEDIAN, ROUND, IF, AND/OR, CONCAT, LEN… with cell ranges), insert/delete rows and columns, sort, filter, column statistics, Σ sum, save as Excel or CSV, export to PDF.
- **Document reader** — Word/RTF/ODT/PowerPoint/EPUB with text size, light/sepia/dark themes, EPUB chapter navigation, “Edit in Documents” and export to PDF/Word/text.
- **ZIP archives** — browse folders, open entries directly, extract all or selected (protected against path-escape entries), and compress any file or selection to ZIP.
- **Music player** — songs, albums, artists, playlists (with Favorites and .m3u8 export) and BlackLab's own audio; background playback with notification, lock-screen and Bluetooth controls; app-wide mini player; shuffle/repeat, queue, speed & pitch, A–B repeat, sleep timer with fade-out, and an equalizer with presets, bass boost, surround and loudness.
- **Audio editor** — waveform with drag-to-select and zoom; cut/copy/paste, trim, delete, silence, insert silence, duplicate, split into files; fade in/out, gain, normalize, mono/stereo; reverse, echo, noise gate, rumble and hiss filters; tempo (pitch kept) and pitch (tempo kept); append, insert or mix in another audio file; export to M4A or lossless WAV (or the selection only). Also extracts audio from video.
- **Video editor & creator** — multi-clip timeline of videos and photos, trim with a range slider, split at the playhead, reorder/duplicate/remove, 9 looks plus brightness/contrast/saturation, titles and a corner watermark, rotate, crop to 16:9 / 9:16 / 1:1 / 4:5 / 4:3, resolution up to 4K, speed 0.25–4×, mute or adjust the original sound, looping background music, H.264 or HEVC, HDR→SDR, audio-only export, and “frame → photo”. Effects preview live on the player.
- **Photo editor** — new **Crop** (free or fixed aspect ratios, rotate, flip, straighten) and **Adjust** (exposure, brightness, contrast, saturation, warmth, tint, fade, vignette, auto-enhance) tabs.
- **Create & Edit hub** — new document, spreadsheet, text or Markdown note, CSV, drawing canvas, PDF from photos, blank PDF, video montage, audio recording, photo/video; or open/edit any file from the phone. Library multi-select adds “Make a PDF”, “Make a video” and “Compress to ZIP”.

### Fixed
- Word import no longer drops the spaces between differently formatted words.

### Notes
- New libraries: Media3 Transformer, Effect and Session 1.9.0 and PdfBox-Android 2.0.27 (Apache-2.0). The APK grows from about 10 MB to about 18 MB.
- Unit tests for the formula engine, CSV/XLSX round trips, page ranges, file types and ZIP extraction safety.

## [4.0.0] — 2026-10-31

### Added
- **Personal assistant** — talk or type from the Home ask bar, the drawer, a launcher shortcut, the Quick Settings tile, or by setting BlackLab as the phone's digital assistant (hold power). A deterministic, fully offline command router acts on all of BlackLab: tasks and reminders, calendar events (with conflict warnings), agenda, daily briefing, notes, document templates, journal, recorder and dictation control, camera and scanners, focus timer, CEU logging, GPA, account switching and every setting by name. Phrasing the router doesn't recognize is rewritten into a command, or answered, by **Gemini Nano on-device** (AICore). Personas: laid-back, professional or concise. Spoken replies use **ElevenLabs** (laid-back male voice, monthly character budget, cached phrases) when online, and the on-device Android voice offline.
- **Three offline features**
  - **Ask my files** — on-device BM25 search over documents, journal, recording and dictation transcripts, tasks, events and courses; Gemini Nano writes a cited answer from the top passages.
  - **Meeting → Actions** — turns the latest dictation/recording transcript into minutes (summary, decisions, action items) and creates a To-Do task for each action item.
  - **Snap-to-Work-Order** — photograph an equipment nameplate; ML Kit OCR pulls manufacturer, model, serial, electrical and refrigerant specs into a pre-filled work order document and task.
- **Google services** (online, optional) — Gmail (check unread with a Nano summary, search, draft, send with confirmation), Google Drive (search, upload backups/photos/scans/recordings), Google Keep (via the Keep app; Keep's API is Workspace-only), and Google developer docs search (Developer Knowledge API). Gmail and Drive use Google sign-in (OAuth); API keys are stored encrypted with a hardware-backed Keystore key and are never backed up, exported or sent to the desktop.
- **BlackLab Desktop** — a desktop control center (`desktop/`) with dashboard, assistant chat, a command palette (Ctrl+K) and detailed settings pages for every section, all generated from the app's settings schema. It syncs live with the phone over USB (`adb forward`, automatic) or Wi-Fi with a pairing code, and through a hidden app-data folder in your Google Drive when away. A local companion service adds an offline desktop assistant (Ollama with tool calling) for desktop tasks: system status, disk usage, finding/opening files, launching apps, notes, and pulling phone files to the computer.
- **GitHub Pages** — the Desktop UI is published at https://paulmmoore3416.github.io/BlackLab/ with a **Sign in with Google** button for cloud sync from any browser; it also installs as an offline-capable web app.

### Improved
1. **Search all settings** from the top of the Settings screen.
2. **Launcher shortcuts** — Ask BlackLab, New task, Dictate, Brief me.
3. **Quick Settings tile** — “Ask BlackLab” from the notification shade.
4. **Default digital assistant** — BlackLab can be chosen for the assist gesture / power-button hold.
5. **Home ask bar** with one-tap spoken daily briefing.
6. **CameraX 1.6** — Night, Portrait and HDR extensions keep working after CameraX ≤1.5 extension support ends on 2026-11-01.
7. **Safer backups** — Android backup and device-transfer rules exclude the encrypted key store; BlackLab backups now include the assistant conversation.
8. **Unit tests** for the assistant's parsing logic.

## [3.1.0] — 2026-10-28

### Added
- **Photo information** — every photo is geotagged (GPS latitude/longitude, accuracy) and also stores the **street address**, **compass heading** the camera faced, **altitude**, and **author & copyright**, plus account/look notes in EXIF. Optional burned-in info stamp (date, address, GPS) for inspection photos. Details sheet shows it all with a map link.
- **Camera looks** — live-preview filters: **1970s** (faded warm film, grain, light leak, orange date imprint), Vivid, Noir, Silver, Golden hour, Arctic, Cinematic, Matte fade and Cross-process.
- **Pro mode** — manual ISO, shutter speed (1/8000 s – 30 s), manual focus and white-balance presets.
- **Exposure bracketing** — 3 shots at ±1 EV or ±2 EV, or 5 shots from −2 to +2 EV.
- **Panorama** — new full-resolution strip panorama: gyro-guided placement refined by image registration, cylindrical projection, feathered seams, locked exposure/white balance, auto-crop, live preview with level/speed guidance, auto-finish at 360°. High (~3,300 px tall) or Ultra (~4,000 px tall). Review screen with all looks and a **Tiny Planet** renderer.
- **Photo editor** — finger drawing (pen, highlighter, line, arrow, box, circle, eraser) with color, thickness, opacity and dashed lines, undo/redo; text with color, size, 8 fonts, **bold, italic, underline, cursive**, box and outline; looks; **watermarks** (text and/or logo, 6 positions incl. tiled, size, opacity, color, font); **background removal** (on-device ML Kit) with transparent PNG, blur, or solid-color backgrounds. Saves as a copy or replaces the original, at full resolution with EXIF preserved.
- **Long-press menus** everywhere — photos, videos and files, documents, recordings, dictations, journal entries, tasks and Home shortcuts open a sheet of actions for that item.
- **Dictation** — record-and-transcribe for meetings, conferences and interviews with spoken commands: “pause recording”, “resume/start recording”, “stop recording”, “new paragraph”, “new line”, “mark that”. Hands-free standby waits for “start recording”. Each session saves to `Dictation/Dictation_YYYY-MM-DD_HH-MM-SS/` with the M4A audio (GPS embedded), a dated transcript `.txt` with location header, and `meta.json`. Session view with playback, tap-to-seek paragraphs, editing, re-transcription, copy, map and Save as document.

### Improved
- Speech recognition accuracy for the recorder and dictation: proper anti-aliased resampling to 16 kHz (previously sample-dropping), automatic punctuation/capitalization, custom vocabulary biasing, and microphone/noise/gain presets for dictation.
- Smoother look and feel: softer tonal dark palette, rounder shape system, eased screen transitions, press feedback on cards, quieter borders.

## [3.0.0] — 2026-10-16

First release published to this repository (`versionCode 3`).

### Added
- **Accounts** — separate Personal and Professional workspaces (data, folders, calendars, accent colors) with optional biometric lock for the app and/or the Professional account.
- **Storage** — `Internal storage/BlackLab` folder created on first launch with per-account Photos, Videos, Recordings, Scans, Documents, Journal, Education, Exports and Imports folders; daily database backups with restore.
- **Camera** — CameraX photo & video with Night/Portrait/HDR extensions, Ultra HDR, max-resolution capture, 4K/60 fps, 10-bit HLG, stabilization, focus/exposure/zoom controls, grid and timer; document scanner, QR/barcode scanner and OCR.
- **Recorder** — AAC/WAV engine with source & device selection, noise suppression, AGC, echo cancellation, gain, markers, background recording, playback speed control and on-device transcription (beta).
- **Documents** — rich editor with ribbon (styles, fonts, colors, lists, checklists, tables, images, links, page breaks, find & replace, dictation, read aloud), page setup, 19 templates, export to DOCX/PDF/HTML/MD/TXT, printing, and DOCX/MD/HTML/RTF/TXT import.
- **Journal** — rich entries, moods, tags, favorites, prompts, streaks, calendar, “On this day”, templates.
- **To-Do** — My Day, Important, Planned, custom lists, natural-language quick add, steps, reminders with notification actions, recurrence, priorities, tags, attachments, Plan my day.
- **Schedule** — Month/Week/3-Day/Day/Agenda views, recurring events, reminders, conflict detection, free-time finder, phone calendar overlay, .ics import/export.
- **Education** — terms, courses, class schedules, assignments & exams, weighted grades, GPA (cumulative/term/projected/what-if), certifications with renewal reminders and CEU log, Pomodoro timer with study analytics, SM-2 flashcards.
- **Home dashboard**, morning agenda notification, journal reminder, “Share to BlackLab” from other apps.

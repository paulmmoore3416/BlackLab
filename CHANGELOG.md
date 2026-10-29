# Changelog

All notable changes to BlackLab are documented here. The project follows [Semantic Versioning](https://semver.org/).

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

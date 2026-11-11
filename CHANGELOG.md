# Changelog

All notable changes to BlackLab are documented here. The project follows [Semantic Versioning](https://semver.org/).

## [5.13.0] — 2026-11-10 (companion 1.7.0)

Your whole computer is now part of the living graph, your AI has 283 expert skills, and you can ask your notes questions in plain words.

### Added
- **Agent skills** (`agent_skills.py`): 283 skills from five open libraries — [Anthropic](https://github.com/anthropics/skills), [Vercel](https://github.com/vercel-labs/agent-skills), [OpenAI](https://github.com/openai/skills), [Google](https://github.com/google/skills) and [Hugging Face](https://github.com/huggingface/skills). They cover PDF, Word, Excel and PowerPoint, web and app design, React and Next.js, Google Cloud, Vercel, Hugging Face models and datasets, MCP servers and writing.
  - The desktop AI can use them through the new `skills_search` and `skill_use` tools. A request that names a skill starts with it already loaded, and close matches are suggested to the model.
  - On the phone, ask *"what skills do you have"*, *"find a skill for …"* or *"use the xlsx skill to …"*. MCP agents use `vault_skills`.
  - Each skill gets a card in `70 AI/Skills/`, linked to its library and to the node it serves.
  - The libraries are shallow clones on this computer, updated weekly. Their scripts never run, and the clones are never copied into this repository.
- **Ask my notes** (`vault_recall.py`): semantic recall over the whole vault through kb-core, from the OMNIX project.
  - It combines keyword and vector search (nomic-embed-text) with a calibrated relevance score, so it can answer "nothing relevant".
  - The local model answers from your notes and cites them. On the phone: *"what do my notes say about …"*. MCP: `vault_ask`. The desktop AI's vault search uses it too.
  - Phone requests search only synced notes. If kb-core isn't running, it falls back to keyword search.
- **Facility operations** (`facility_ops.py`):
  - Templates you can edit: EOC rounds, fire & life-safety audit, daily ops report, PM calendar, CMS-2567 plan of correction and facility incident report. *"Start EOC rounds"* creates a dated checklist.
  - A **panel directory**: *"which breaker feeds room 214"* (panel-schedule notes → a room-to-circuit index).
  - An **SDS binder** with storage-conflict checks (oxidizers with flammables, acids with bases, bleach with ammonia or acids) and revisions older than 3 years flagged.
  - *"Shift prep"*: what's overdue, open work orders, rounds, SDS and credential renewals.
- **Credentials & renewals** (`vault_credentials.py`): your own table plus the app's certifications. It shows what's expired or due within 90 days, and accepts renewal rules like `10/2028` or `+12m`.
- **Activity ledger** (`audit.py`): every vault change, job and AI tool call is one line in MemResort's observation schema, hash-chained like OMNIX's audit log. Editing or removing a past line shows up in **90 System/Activity Ledger**, and the AI Registry shows what each agent did. MCP: `vault_ledger`.
- **Color everywhere** (`vault_colors.py`):
  - Every node, and everything belonging to it, wears the node's color: graph groups, a shade per subnode and per skill library, note titles and headings, folders in the explorer, tags, and `> [!facility]`-style callouts.
  - Extended Graph adds folder hulls and tag arcs.
  - Graph colors are restored on every pass, because Obsidian on the phone was writing them back empty.
- **Graph FX** (`desktop/obsidian/blacklab-graph-fx`), BlackLab's own Obsidian plugin with three new graph animations:
  - ⚡ synapse signals travel the links in their note's color, and busy notes fire more;
  - 💓 a live pulse ripples from any note that changes (you, the phone, an AI) and bursts signals to its neighbors;
  - 🎞️ a time-lapse replays the vault being built, note by note, with the date running.
- **Web UI:** the Obsidian vault page has *Ask*, *Skills*, shift prep, EOC rounds, a breaker lookup, credentials, and recall and ledger status.
- **The Atlas** (`vault_atlas.py`): a card for every document, guide, photo, video and project on the computer, a folder note for every folder on the way, and **🖥️ This Computer** (hardware, disks, services, toolchains, local AI models, apps). Only changed files are re-read, every 30 minutes. Cards stay on this computer: `85 Atlas/` is git-ignored.
- **The Atlas** (`vault_atlas.py`): a card for every document, guide, photo, video and project on the computer, a folder note for every folder on the way, and **🖥️ This Computer** (hardware, disks, services, toolchains, local AI models, apps). Only changed files are re-read, every 30 minutes. Cards stay on this computer: `85 Atlas/` is git-ignored.
- **Every guide in one place:** user, technical, how-to and training guides (yours, your projects', your toolchains' and installed software's) are `type: guide` cards gathered in **📘 Guides** and Bases › *Guides*. BlackLab now ships its own User, Technical, How-to and Training guides in `90 System/Guides`.
- **Other vaults imported:** every Obsidian vault on the machine is brought into `86 Imported/` note for note and kept in sync. The originals are never changed.
- **People & topics** (`vault_entities.py`): people, organizations, equipment, credentials, projects, places and topics found across your documents become linked notes in `87 Entities/`, using known lists plus the local model. A name is kept only if it appears word for word in the text.
- **Unlimited nodes:** four new built-in nodes (IT, Networking & Systems · Media Library · Mining & Crypto · People & Relationships) and a node registry (`90 System/Node Registry.json`) for as many more as you need. A note now joins a node through its `up:` link too.
- MCP tools `vault_node_create` and `vault_atlas`. New graph views: *This computer*, *People & topics*, *Guides*, *Imported vaults*.

### Privacy
- `guards.vault_safe_passages` leaves out only the clinical or off-the-record paragraphs of a long document, so the rest of it can still be indexed. More secret patterns are scrubbed (OAuth client secrets, AWS keys, bearer tokens, JWTs, `key = value` secrets), and files such as `client_secret*.json`, `.env` and `token.json` are never opened.
- Synced notes (nodes, Nexus, Graph Health) count local notes without naming them. Related links never point from a synced note to a local one.

## [5.12.0] — 2026-11-09

Your computer can now reverse engineer software for you. Point it at a program, APK, library or firmware image and the desktop AI decompiles it with Ghidra and explains it, citing the evidence.

### Added
- **Reverse engineering on the computer** with [REA](https://github.com/morluto/rea) and Ghidra:
  - Say “reverse engineer ~/Downloads/tool.bin and find how it checks the license” and the report lands in Documents › Reverse Engineering.
  - Ask “decompile the function that prints the usage message in …”, “what strings are in …”, “who calls 0x4012a0 in …” or “how do I inspect an APK with REA?”.
  - Any program-like file (APK, .so, .exe, .dll, firmware, .pyc, .wasm, models…) has ⋮ › **Reverse-engineer on computer**.
  - Only read-only analysis runs. BlackLab never runs, debugs or instruments the target, and keystores, wallets and keys can't be targets.
- Reports show decompiled code in code blocks.

- **A living Obsidian graph:**
  - Links now pull notes together, so the 13 nodes form clusters. Each node has its own color, and tags no longer clutter the graph.
  - Typed links: `synapses:` between nodes and `related:` between notes about the same thing. Related links are found by a local AI (nomic-embed-text, offline) and your own entries are kept.
  - **Bookmarks › Graph views** has a saved graph per node, plus the brain, the AI layer, what needs attention, and the related-links web.
  - **Graph Health** lists orphans with a suggested node, unresolved links and hubs.
  - Plugins, pinned and checksum-verified: **Breadcrumbs** (a trail on every note, prev/next across daily notes, a relations matrix), **Extended Graph** (link types in their own colors, hubs sized by links, the open note highlighted) and **Advanced URI**.
  - “Show RTU-4 in my graph” (phone or desktop) opens Obsidian on that note's local graph, and every node note has *Open local graph* buttons.
- BlackLab Desktop › Obsidian vault › **Graph**: link and orphan counts, related pairs, plugin status, *Open the graph* and *Show in graph*.

### Changed
- Updated: ZXing 3.5.4, jsoup 1.24.1, ML Kit text recognition 19.0.2, ML Kit GenAI Prompt 1.0.0-beta5, Play services auth 22.0.0, and the CI actions. The AndroidX 2026.09 and AGP 9 updates wait for the planned AGP 9 migration.
- The phone's Obsidian mirror no longer pulls Obsidian's own settings back from nested `.obsidian` folders.

### Desktop (companion 1.6.0)
- `reverse.py` adds the `reverse_engineer` tool for the desktop AI, the `reverse` background job and a reverse-engineering workspace in `~/Desktop/BlackLab AI/Reverse Engineering`. REA's own guides are indexed for search.
- Guards for the small local model: one-step `find_code`, automatic facts about the file before it answers, repair for paths typed in the wrong case, and a warning on any code it wrote itself instead of quoting the decompiler.

## [5.11.0] — 2026-11-08

BlackLab gets a second brain: everything flows into an Obsidian vault on your computer, organized into 13 knowledge nodes your AIs can read and grow. Plus work-order intake, a daily timeline with ops handoff, and a Health page.

### Added
- **Obsidian vault** (Settings › Obsidian vault): BlackLab mirrors Facility, Garage, tasks and calendar, notes and notebooks, learning and recordings (journal only if you turn it on) into `~/Obsidian/BlackLab` on the computer, every 15 minutes while the desktop link is up. Behavioral Health, keys, the VIN and plate, trip places and locked notebook sections never go.
  - **13 main nodes + the Nexus**: Facility Operations · Life Safety & Compliance · Peer Support & Milieu Safety · Leadership & Team · Vendors & Contracts · Finance & Budget · Projects & Engineering · AI & Automation · Learning & Credentials · Knowledge Library · Vehicles & Travel · Home, Family & Health · Time, Goals & Planning. Each starts with a detailed primer and grows on every sync (members, signals, mentions); every note links up to its node, and a Neural Map canvas shows the network.
  - Daily notes with a **timeline** of everything that happened, an **AI Registry** every AI signs, version history (git), and your own writing is never overwritten.
  - Assistant: “save this to Obsidian: …”, “add to the facility node: …”, “log to the vault: …”, “search the vault for …”, “what's new in the vault?”, “open Obsidian on my computer”, “sync the vault”.
- **Offline library** (~50 GB, on the computer): 76 openly licensed knowledge packs (Stack Exchange, LibreTexts, Wikibooks, iFixit, MedlinePlus, Ready.gov, the USDA canning guide, field-medicine and post-disaster libraries, Wikipedia's best 50,000 articles plus 1 million article introductions) and public-domain government documents (DOE O&M guide, OSHA 29 CFR 1910/1926, the FAR, SAMHSA peer competencies, EPA water disinfection, the NWS spotter guide). Each source is a card in `95 Library/` linked to its node; the assistant answers from it offline: “look it up in the library: …”.
- **Self-Reliance & Survival** — 17 subnodes under Home, Family & Health, each with a primer and its own library shelf: off-grid survival, plant knowledge & foraging, sourdough & bread, water filtration & purification, field dressing & game care, first aid & wilderness medicine, food preservation & canning, emergency communications, land navigation, shelter & fire, knots, off-grid power, gardening & seed saving, disaster preparedness, tools & repair, weather reading, fermentation.
- **Work-order intake**: say or type “work order: leak under the sink in room 214, B-wing” (or Facility › 🔧). BlackLab picks the trade, priority (P1–P4, with the reason), location and matching asset, files it in To-Do › Work Orders with a safety-first checklist, and logs it on the asset.
- **Timeline & ops handoff** (side menu): one timeline per day — inspections, work orders, tasks, meetings, recordings, notes, drives, and Behavioral Health as counts only. **Ops handoff** writes the day's summary (open work orders by urgency, inspections needing attention, tomorrow) to share, copy or send to the vault; it never includes Behavioral Health. Assistant: “ops handoff”, “what happened today”.
- **Settings › Health**: backups, data files, desktop link, vault, crashes, storage and on-device AI on one page, each with a fix.
- **Ask my files** now searches Facility (assets, inspections, work orders) and Garage service history (Settings › Assistant knowledge › Facility & Garage). The VIN and plate are never indexed.

### Changed
- Facility, Garage, the drive log and recalls save through one crash-safe store: a last-good copy is kept, a damaged file is set aside instead of being overwritten, and files carry a format version so future updates migrate them.
- Weather and recall lookups retry briefly when the network blips; the assistant shows a clear offline banner.
- The assistant's command grammar is one ordered, tested table with a phrasing corpus. Fixed: “what were the action items from the meeting” and “what did we decide in the budget meeting” were read as calendar questions.
- Faster cold start: a generated Baseline Profile precompiles startup and Home.
- Updated: Kotlin 2.3, Android Gradle Plugin 8.13, Compose BOM 2026.06, Room 2.8, Lifecycle 2.10, Navigation 2.9.8, WorkManager 2.12, Glance 1.2.

### Desktop (companion 1.5.0)
- The vault engine, a **vault MCP server** (`desktop/companion/vault_mcp.py`: checkin, search, read, write, log, capture, recent, nodes, node_add) so Claude Code and other agents can work in the vault, and Claude Code session hooks (`vault_hook.py`).
- BlackLab Desktop › **Obsidian vault**: status, the 13 nodes, capture and search (vault or offline library), sync, open in Obsidian.
- `library.py` (downloads with SHA-256 verification, cards, search through kiwix-serve on 127.0.0.1:8780) and the MCP tool `vault_library_search`; an optional, quarantined **external guest book** (`guestbook.py`, off until a public repo is configured).
- The desktop AI can search and save to the vault; every non-private conversation lands on the day's timeline.
- All safety rules (HTTP, protected paths, privacy, tool calls) moved into `guards.py` with unit tests. Fixed: a path like `/home/paulx/…` passed the home-folder check.

### Project
- `scripts/release.sh X.Y.Z [--install] [--publish]`: version bump, changelog check, secret + privacy scans, tests, lint, signed APK + `.sha256`, tag and GitHub Release.
- `scripts/privacy-scan.sh` (also in CI): real VINs, personal e-mails and phone numbers, precise coordinates, SSNs and your private denylist (`~/.config/blacklab/privacy-denylist.txt`).
- Desktop companion tests run in CI. Dependabot skips AGP major versions.

## [5.10.0] — 2026-11-08

Facility gets its own section, backups are encrypted, and the desktop link is locked down.

### Added
- **Facility** (side menu › Facility): an asset register with QR / asset tags, preventive-maintenance and life-safety inspection schedules, and a log of inspections, work orders and findings.
  - **Life-safety & PM program**: add common inspections in one step, each with a checklist — extinguishers (monthly, annual · NFPA 10), emergency generator (weekly inspection, monthly 30-minute load test · NFPA 110), emergency & exit lighting (monthly 30-second, annual 90-minute · NFPA 101), eyewash (weekly · ANSI Z358.1), fire alarm (NFPA 72), sprinklers (NFPA 25), fire doors (NFPA 80), kitchen hood suppression, backflow, elevators and filter changes. Every interval is editable; confirm them with your AHJ and accreditor.
  - **Due** list with overdue and due-soon counts; log an inspection as Pass / Fail / N/A with its checklist, readings, who did it and the date. A failure can create a **work order** in To-Do › Work Orders; any schedule can go to To-Do › Inspections.
  - **Scan an asset tag** to open that asset, or start a new one with the tag filled in. The code scanner everywhere now offers "Open <asset>" / "New Facility asset with this tag".
  - **Survey binder (PDF)**: every schedule and its status, the inspection & work log for 30 days to 3 years, deficiencies and the asset register, saved to Professional › Exports.
  - A daily reminder when inspections come due or go overdue, a **Facility** card on Home (shows only when something is due), and "what inspections are due?" in the assistant.
- **Settings › Backup passphrase**: backups made after it's set restore on any phone; **Verify latest backup** decrypts and checks a backup without changing anything; **Delete unencrypted backups** removes the old plain `.zip` files.

### Changed
- **Backups are encrypted** (`.blbackup`, AES-256-GCM) and now also include Garage (vehicles, service, fuel, trips), saved knowledge conversations, playlists, the custom wake word and Facility. Database snapshots are consistent (`VACUUM INTO`). Uploading a backup to Drive uploads the encrypted file.
- **Restore checks everything first**: it decrypts and unpacks the backup into a scratch folder, checks the database's integrity and version, shows what's in it, and saves your current data as a "before-restore" backup before replacing anything. Backups from a newer BlackLab are refused instead of crashing the app. Old `.zip` backups still restore.
- **Desktop link over Wi-Fi**: reading files, storing API keys and making backups now work only over the USB (adb) link, and security and link settings (app lock, widget privacy, link and cloud sync switches) can't be changed over Wi-Fi or through Drive. Only BlackLab Desktop's own pages may call the phone from a browser, and the pairing code is never accepted in a URL (desktop downloads send it in a header).
- The document editor runs only its own script: scripts, event handlers and remote images inside imported documents are blocked.
- Screens stop watching for changes while BlackLab is in the background.

### Fixed
- Assistant messages sent at the same moment from the phone and the desktop could overwrite each other, and the history file could be cut short.

### Desktop (companion 1.4.1)
- After the desktop AI reads a file or document, it only opens links you typed yourself, so a document can't make it open a link that sends data out.

### Project
- Release APKs are no longer committed (they're attached to GitHub Releases; `releases/` keeps the checksums). CI actions are pinned to commit SHAs, and Dependabot proposes weekly updates for Gradle dependencies and actions.

## [5.9.0] — 2026-11-07

Notes gets its own section, and you choose what's on Home.

### Added
- **Notes**: a dedicated, Google Keep-style section (side menu › Notes). Search pill with grid/list toggle and account bubble; a Keep side menu with Notes, Reminders, your labels, Archive and Deleted; a bottom bar to start a list, drawing, voice note, picture or photo note, plus a **+** button. Pinned / Others, Reminders split into Upcoming / Sent, **swipe a note to archive** (with Undo), multi-select to pin, remind, color, label, archive, delete, copy, send or file into a notebook. Search opens on Types (reminders, lists, images, voice, drawings, URLs), Labels and Colors, then narrows as you type. Starting a note from a label view applies that label.
- **Undo / redo** in the note editor, plus Keep's list options: **Add new items to bottom** and **Move checked items to bottom**.
- **Notes card on Home**: a Take a note bar and your pinned and latest notes, two across. Choose pinned-then-latest, pinned only or latest only, and how many.
- **Settings › Home dashboard**: turn each of the 13 Home cards on or off and move them up or down; **Customize Home** at the bottom of Home goes straight there. The same switches appear in the desktop's settings.
- The assistant opens Notes ("open notes").

### Changed
- Brainstorm keeps Notebooks and Tags; its notes now live in Notes (old links to Brainstorm › Notes open Notes).

## [5.8.1] — 2026-11-07

BlackLab Desktop catches up with the app.

### Added
- **Brainstorm on the desktop**: your notebooks, sections and pages (titles, previews and searchable text; locked sections share titles only), Keep-style notes in their colors with checklists you can tick, a **Tagged** view that gathers To Do / Important / Question lines from every page, search across all of it, and **Add note** (text or checklist) straight to the phone over the live link.
- **Knowledge on the desktop**: all expert and conversation knowledge bases the assistant answers from, with full entries, search across every entry, and one-tap questions and conversation starters.
- **Summarize on the desktop**: pick what (today, tomorrow, this week, open tasks, recent notes, journal, last meeting) and how (brief, action items, bullets, email to your boss, status report, talking points), or paste any text to summarize.
- **Flashcards on the desktop**: your decks with cards learned and due today, under Education.
- The phone's assistant summarizes pasted text ("summarize this as action items: …").

## [5.8.0] — 2026-11-07

Twelve improvements on top of the car link: six features and six looks.

### Added
- **Recalls & your VIN** (NHTSA, free, no account): the Garage decodes your VIN into the exact build (trim, engine, transmission, drivetrain, plant) and fills the profile from it. It lists safety recalls for your year, make and model with what, risk and fix, and links to NHTSA's open-recall check for your VIN. A weekly check notifies you of new recalls; mark any that are done or don't apply. Ask "any recalls?".
- **Fill-up detection**: when the fuel level jumps after you park, a card and a notification offer to log the fill-up with the gallons already worked out from your tank size. You only add the price.
- **Predicted service dates**: your usual miles per day (from trips, fill-ups, or a yearly estimate in the profile) turn "in 950 mi" into "around Dec 1". A daily check sends a heads-up a week before anything comes due, and any maintenance item can become a To-Do with a 9 AM reminder. Ask "when's my next oil change?".
- **Trip rules**: open a trip and choose "Always tag trips here Business" (optionally weekdays only). Future trips that start or end within about 250 m are tagged automatically; "Apply rules to earlier trips" fixes the backlog. Your own tags are never changed.
- **Car widget**: fuel bar and range, locked or not, odometer, where it's parked, warnings and the next service on the home or lock screen (the lock screen hides the place).
- **Practice drive (simulator)**: a four-minute pretend drive through a pretend OBD-II adapter that runs the real parsing, trip log, MPG, alerts and drive notification. Optional pretend check-engine code. It's marked Simulated, left out of totals and exports, and never touches your odometer or the car's real values.
- **Desktop Garage**: BlackLab Desktop gets a Garage page (odometer, fuel, MPG, miles this month, what's due with dates, recalls, weekly miles and MPG charts, recent trips, live values and codes, VIN details) and a Car tile on the dashboard.
- **Vehicle provisioning**: details like the VIN and odometer can be pushed once with adb (`BlackLab/.provision/vehicle.json`) so they're never typed or stored in the repo.

### Changed
- **Charts in the Garage**: fuel and service-interval ring gauges, miles per week (business stacked), MPG per tank with your average, and fuel spend per month. Tap a bar or point for its value.
- **Dark mode styles**: Graphite (the original), **True black** for OLED screens, Slate, and Accent wash.
- **Corners and size**: Round, Soft or Square corners everywhere (widgets too), and Comfortable, Compact or Dense interface size.
- **Live drive notification**: on Android 16 the drive asks to be a Live Update, with trip miles as a status-bar chip and a running clock. Car notifications use your account color.
- **Widgets follow your theme**: light and dark styles, contrast and corners now reach every widget.
- **Fonts**: Inter, the phone's own font (Google Sans Flex on Pixel) or Serif for the app, plus a separate reading font and size for Knowledge, Journal, Summaries, text files and the document editor's zoom. The desktop follows the corner, font and dark-style choices.
- Google sign-in failures now say exactly what to fix (for error 10, the Android OAuth client's package and the app's real SHA-1, with a copy button). The desktop explains blocked pop-ups, origin mismatches and missing test users.

## [5.7.0] — 2026-11-07

The car talks to BlackLab: connect to the Santa Fe's Bluetooth and everything the car can share lands in the Garage.

### Added
- **Car link**: when the phone connects to the car's Bluetooth (or Android Auto starts), a drive session begins on its own. The car is recognised the first time the phone connects to it, or you pick it under Garage › **Car connection**. **Pair for automatic start** registers it as a companion device, so drives start in the background even after a restart.
- **Trip log**: every drive is saved with distance (odometer, else GPS, else the car's own speed), time moving and idling, average and top speed, hard braking and acceleration, fuel used and MPG, and where it started and ended. Only the endpoints are kept, not the route. Weekly, monthly and yearly totals; tag trips Personal, Business, Commute, Medical or Charity; business miles × your rate per mile; **Export CSV** for a mileage log. A summary notification arrives when you park.
- **Odometer that keeps up**: GPS miles between real readings are added to an estimate (shown with ≈), so "what's due" stays current. Real readings from the car replace the estimate.
- **Where you parked**: saved at the end of every drive (and from Bluelink), with directions. Ask "where did I park?"
- **OBD-II adapter** (optional, any Bluetooth classic or BLE ELM327-type plug, e.g. OBDLink, Veepeak or Vgate): VIN, check-engine light and stored, pending and permanent trouble codes with plain-language meanings (new codes raise a notification), RPM, speed, engine load, throttle, coolant, oil and intake temperatures, manifold and barometric pressure, mass air flow, fuel flow with instant and per-trip MPG, fuel level, battery and ECU voltage, run time, distance since codes were cleared, and the odometer where the car supports PID A6. **Test** checks the adapter from the setup screen.
- **Hyundai Bluelink** (optional, US): the car's last report to Hyundai, read a few minutes after you park and at the start of each drive: odometer, fuel and range, low fuel, locks, open doors, windows, liftgate and hood, tire-pressure warnings per tire, washer and brake fluid, key-fob battery, 12V battery charge, climate and where it's parked. A notification tells you if the car was left unlocked or open. The sign-in is Keystore-encrypted and only sent to Hyundai. BlackLab reads saved status only and never wakes the car.
- **Android Auto** now reads the car for the whole session, not only on the Garage screen: model, fuel types, fuel level, range, low-fuel warning, hybrid battery, speed and odometer. The car's Garage screen adds a **Car health** row (codes, battery, coolant, tires, trip miles).
- Garage › **From the car**: drive status, headline values, trouble codes, parking spot and **All vehicle data**, grouped, with the source and age of every value.
- Assistant (phone and car): "where did I park?", "any check-engine codes?", "how much gas do I have?", "is my car locked?", "tire pressure", "how many miles did I drive this month?", "business miles"; "how's the car?" now includes live data.

## [5.6.0] — 2026-11-07

Your phone and your computer now work as a team: hand work off and pick up the results later.

### Added
- **Hand-off jobs** (companion 1.4.0): a job queue on the computer the phone hands work to (`/phone/upload`, `/phone/jobs`). The phone holds work in an outbox while the computer is out of reach, checks in every minute while linked, and posts results as notifications ("Computer results" channel). Assistant: "in the background: …", "transcribe my last recording", "desktop brief", "weekly summary". Settings › Assistant › **Hand off work to the computer**.
- **Meeting intelligence**: every new recording (or **Transcribe on computer** on a recording) is transcribed offline with Whisper large-v3-turbo on the NVIDIA GPU, using your memory and equipment names as spelling hints. Minutes (summary, decisions, action items with owners and due dates) land in Documents › Meetings on both devices; your action items become tasks, other people's become follow-ups; the better transcript fills in the recording. Recordings with clinical content are processed but never kept on the computer.
- **Equipment registry**: Snap-to-Work-Order photos also go to the computer, where a local vision model (qwen2.5vl:7b) transcribes the plate and qwen3 maps it to fields. Each unit gets a tag (RTU-4…), a card in `BlackLab AI/Equipment/Cards`, and a history of photos, work orders, meetings and chats that mention it. Three problems in 120 days flag a repeat issue. Ask "history on RTU-4", "which units use R-22?", "show equipment", or the model's new `equipment` tool.
- **Overnight operations analyst**: from a snapshot the phone sends every 30 minutes (tasks, the next 3 days of events, coursework, exams, flashcards due, weather alerts; never Behavioral Health data), the computer writes a morning brief (05:30, or right after Night Mining stops) ranked by risk (weather and life-safety, overdue high-priority work orders, repeat-failure equipment, deadlines), with practice questions from your study material before an exam, and a Friday weekly summary for leadership. Both arrive as notifications and documents in Briefs.
- BlackLab Desktop: **Background jobs** and **Equipment** cards; `./daddyshome --install-whisper`, `--install-vision`, and status lines for both.
- **Protected paths**: wallet keys and recovery phrases, SSH/GPG keys, keyrings, browser profiles and BlackLab's own keys can never be read, opened or found by any desktop-AI tool, so a prompt hidden in a document can't exfiltrate them.
- **Night Mining aware**: jobs and the nightly memory update wait while the miners hold the GPUs; the morning brief runs 10 minutes after mining stops (07:10).
- After a heavy job the companion unloads the vision model and reloads the main model fully onto the GPUs, so everyday answers stay fast.
- **Desktop AI privacy guard** (companion 1.3.0): clinical requests (patients, meds, SBAR, peer notes, Behavioral Health…) and anything starting "privately:" or "off the record" are never saved to chat history, memory or task-log content; SSN, card and medical-record numbers are scrubbed from everything saved.
- **Desktop AI memory**: follow-ups work (the last 3 turns of the past 15 minutes go with each request), and `Chat History/memory.md` holds lasting facts, added by "remember that …" or nightly from the previous day's chats, removed by "forget …".
- **Search your documents**: local `nomic-embed-text` search across the AI's Documents and Downloads (including PDFs): "search my documents for …", or the model's new `search_documents` tool.
- **Tool guard**: tools that change something only run when the request asks for that kind of action; the model knows the real home folder and made-up `/home/user/…` paths are mapped onto it. qwen3:8b went from 9/10 to 12/12 on the desktop test set. File search skips the AI's own logs, and "open it" no longer launches a random app.
- **BlackLab AI folder on the Desktop** (companion 1.3.0): `~/Desktop/BlackLab AI/` holds `Downloads/` (files pulled from the phone), `Documents/` (notes and documents the AI writes), `Chat History/` (daily transcripts plus a searchable `chat-history.jsonl`) and `Task Logs/`. The assistant can search past chats with the new `recall_chats` tool, and "show chat history for …" searches them without the AI. The old `~/BlackLab-Desktop` and task-log folders move in automatically. Phone downloads can no longer be written outside `Downloads/`. See [docs/DESKTOP_AI.md](docs/DESKTOP_AI.md#the-blacklab-ai-folder).
- **Desktop AI task logs** (companion 1.2.0): every task the desktop AI runs, from the phone or BlackLab Desktop, is logged in `~/Desktop/BlackLab AI/Task Logs/` (originally `~/Desktop/BlackLab AI Task Logs/`): one Markdown file per task with the request, each step and tool, the reply, and every file it created, edited, downloaded, read or opened, plus a `task-index.csv`. Private to your user; "open task logs" opens the folder; `./daddyshome --status` shows it. See [docs/DESKTOP_AI.md](docs/DESKTOP_AI.md#task-logs).

## [5.5.0] — 2026-11-05

Three free-key integrations: Gemini cloud AI, a Picovoice wake word, and OpenWeatherMap facility weather.

### Added
- **Gemini API** (free key, Google AI Studio): a third AI tier after the desktop AI and Gemini Nano (Off / Fallback by default / Prefer). Photo understanding: nameplate photos read directly into work orders, **Describe with Gemini** in the photo menu, and **Smart sort with Gemini**, which proposes folders for photos before anything moves. The desktop companion falls back to Gemini when Ollama is down. Never used by the Behavioral Health workspace.
- **Wake word** (free Picovoice AccessKey): hands-free on the phone. Say the word (Jarvis, Computer… or your own "Hey Todd" .ppn), wait for the chime and ask. The reply is spoken and also posted as a notification. It steps aside while you record or dictate.
- **Weather** (free OpenWeatherMap key): in the briefing, Home, the assistant ("will it freeze tonight?") and the web dashboard. **Facility prep alerts** for freeze, hard freeze, heat, storms, wind, heavy rain and snow come with checklists, and can create Professional tasks (one per alert per day).
- Settings › Assistant & desktop: key slots with "get a free key" links, plus Cloud AI, Wake word and Weather cards.

## [5.4.0] — 2026-11-05

Organize your Library with folders, and put your computer's local AI behind the phone's assistant, all started with one command.

### Added
- **Library folders & sorting** for Photos, Videos, Scans, Audio, Exports and Imports:
  - **New folder**, open folders through breadcrumbs, and folder tiles with a cover photo and item count
  - **Move or copy** any selection, or a single item from its long-press menu, into any folder (with "new folder" in the picker); rename and delete folders
  - **Sort** (newest, oldest, name, size, type), **group** photos and videos by month, day or type or not at all, switch between **grid and list**, and **search** inside a folder; each tab remembers its choices
  - **Sort into month folders** files everything in a folder into folders like "2026-11 November"
  - Moving recordings carries their transcripts and keeps the Recorder pointing at the right files. Dictation and All files stay browse-only, because other screens track those by location.
- **Desktop AI**: while the computer running BlackLab Desktop is reachable (USB or Wireless debugging), the assistant, Summarize, Talk, Ask my files, meeting actions and Brainstorm's smart tools use its Ollama model. Otherwise they use Gemini Nano, then the offline rules. Source labels show which engine answered.
  - "On my computer …" or "ask my desktop …" hands a request to the desktop assistant (files, apps, system status), and "desktop AI status" checks the link
  - **Settings › Assistant & desktop › Desktop AI**: status, model, latency, Test, and an on/off switch
- **`./daddyshome`** (repo root and `~/daddyshome`): starts Ollama; loads and pins the selected desktop model at the companion's context size; restarts the companion; links every phone both ways (`adb forward` 8765, `adb reverse` 8770); pairs the phone with a shared key; prints a status board. Also `--status`, `--open`, `--repair` and `--stop`. See [docs/DESKTOP_AI.md](docs/DESKTOP_AI.md).
- Companion 1.1.0: key-protected `/phone/status`, `/phone/generate` and `/phone/ask`; automatic `adb reverse` for every linked phone; one context size and keep-alive setting for every request.

### Security
- The companion still listens on 127.0.0.1 only. Phone requests travel inside the adb tunnel and need the shared key, our Host header and no browser Origin.
- The app allows plain HTTP only to its own loopback address. The shared key lives in the Keystore-encrypted store and is pushed once by `./daddyshome`.

## [5.3.0] — 2026-11-05

A glass design language on the phone and the web, colors you can actually see change, and a BlackLab Desktop web app that matches the phone.

### Fixed
- **Color choices had almost no visible effect.** Palettes only reached a few buttons and toggles, so choosing one looked like nothing happened. They now drive the whole look: the backdrop, glass tint, bars, selections and highlights.
- Choosing a palette while Material You was on was silently ignored. Picking any palette or secondary color now switches Material You off.

### Added
- **Glass visual style** (default): an **aurora backdrop** built from your palette's main, secondary and tertiary colors drifting slowly behind every screen, with **frosted glass cards**, top bars, navigation bar and drawer. Choose **Glass** or **Solid**, a **moving, still or plain** backdrop, and its **intensity** (Settings › Appearance › Colors).
- **Fluid motion**: every color change (palette, secondary, light style, light/dark, contrast) blends over half a second; glass cards glow from where you touch them; stat numbers count up. Reduce motion stills it all.
- **BlackLab Desktop (web)**, https://paulmmoore3416.github.io/BlackLab/:
  - Restyled to match the phone: the same 17 palettes and 5 light styles read from your synced settings, the same WCAG contrast rules, a live aurora canvas, real backdrop-blur glass, a pointer-following glow and count-up figures.
  - **Appearance** page: theme, glass, backdrop, intensity, contrast, motion, palette and secondary per account, and light style, with a live preview. Changes go to the phone over the live link or through Google Drive.
  - **Behavioral Health** reference: crisis lines and national resources, the de-escalation guide, grounding with a breathing pacer, the 18-plan group library, printable blank **safety-round sheets** for all 11 area types, and the shift checklist. The encrypted phone workspace is never synced; the page explains why.
  - "New in this version" card on the dashboard.
- desktop/web/data.js is generated from the app's own palettes and library by a unit test that fails if the web copy drifts.

## [5.2.0] — 2026-11-05

A sharper, more readable light mode, more ways to make it yours, and eight new color palettes.

### Added
- **Light mode styles** (Settings › Appearance › Colors): **Clean** (soft gray page, crisp white cards), **Pure white**, **Warm paper** (cream, easy on the eyes at night), **Cool mist** (blue-gray) and **Accent wash** (your main color tints the page). Each has a live mini preview.
- **Stronger contrast**: WCAG AAA (7:1) text and accent colors with darker borders, in light and dark mode.
- **Eight new palettes**: Aurora, Crimson, Midnight, Sage, Copper, Lime, Plum and Sky — 17 in all, each with a complementary secondary and a tertiary color tuned for light and dark.
- Eight more colors for lists, calendars and other color pickers, and six more notebook section colors.

### Changed
- **Light-mode contrast**: accent colors used as text or icons (pills, badges, stat tiles, status labels, links) are darkened automatically wherever they'd fall below WCAG AA on the page. The main, secondary and tertiary colors are checked against each style's background too.
- **Light-mode depth**: cards are brighter than the page, with a hairline border and a soft shadow, so they read as raised surfaces. Borders and dividers are darker (3:1 or better) and secondary text is deeper.
- Dark-mode borders are a little more visible; the 988 button uses a deeper red so its white text meets AA.
- The palette pickers wrap onto several rows, so every color is visible without scrolling.

## [5.1.0] — 2026-11-05

A Behavioral Health workspace for working as a Mental Health Technician and Peer Support Specialist — encrypted on the phone, built with initials and aliases only.

### Added
- **Behavioral Health** (drawer): a hub with eight tools, a 988 button and quick links to de-escalation and grounding. Every screen sits behind a fingerprint / screen-lock check (on by default).
  1. **Shift Hub**: start and end a shift (type, role, unit), a 23-item start/during/end checklist, a de-identified census (room/bed + initials, observation level, 17 precautions), shift notes, per-person **shift observation notes** (affect, behavior, speech, groups, ADLs, meals, sleep, delegated vitals, precautions), an **SBAR handoff** that pulls in the census and each person's last check, and hours worked over 7 and 30 days.
  2. **Observation Rounds**: Q5/Q10/Q15/Q30/Q60, line of sight and 1:1 levels per person with live countdowns, overdue after a chosen grace period, **Log a round** for everyone at once (location and behavior dropdowns, “set all”), per-person checks with history, on-time percentage, and a copyable **round sheet**. Exact alarms notify when checks are due and keep nudging while anyone is overdue. Notifications show counts only and are hidden on the lock screen.
  3. **Crisis & De-escalation**: a five-phase guide (what it looks like, respond, say, avoid), ground rules and phrases, grounding techniques with a box-breathing coach, SAMHSA's trauma-informed principles, and **incident report** and **post-incident debrief** drafts (event type, location, antecedents, warning signs, interventions, restrictive-intervention details, injuries, notifications, objective narrative).
  4. **Peer Support**: participants by alias (setting, readiness, recovery pathways, strengths, supports, wellness/crisis plan on file), **recovery goals** by life area with steps, target dates and status, a 14-area **recovery capital** check, and **session notes** written as DAP, SOAP, BIRP, GIRP or narrative from 29 fields (contact type, setting, domains, 25 topics, 21 peer interventions, purposeful self-disclosure, response, safety reporting, plan). Also **wellness & crisis plan** drafts and **referrals**.
  5. **Group Facilitation**: 18 ready group plans in 12 categories (objective, opener, activity, discussion questions, closing, materials, adaptations), your own plans, a facilitation timer, and **group notes** with attendance and dynamics.
  6. **Resources & Referrals**: 26 national crisis, recovery and community resources (988, Crisis Text Line, Veterans Crisis Line, SAMHSA Helpline, NAMI, 211, Trevor, DV and more) with call, text, web, map, share and favorites; your own local resources; referral tracking by status.
  7. **Safety Rounds (EOC)**: ligature-risk and environment-of-care rounds across 11 area types and 77 checks (OK / Issue / N/A), risk level and mitigation, one-tap **work orders** into To-Do (Professional), recurring findings over 90 days, and a copyable round report.
  8. **Wellness & Supervision**: after-shift check-ins with trends and a compassion-fatigue nudge, a supervision log with hours by type and toward your credential, a self-care plan, and peer/MHT credentials you can track in Education.
- Every note has **Copy** (marked sensitive on the clipboard), **Share** and **Save as document**.
- **Knowledge**: two new expert bases, **Peer Support Practice** (31 entries) and **MHT Practice & Unit Safety** (33 entries), plus a **Shift & Peer Practice Lab** conversation.
- Assistant: “start rounds”, “when are rounds due?”, “start my shift”, “new peer note”, “SBAR handoff”, “crisis lines”, “call 988”, “grounding”, “safety rounds”.
- Home shows an on-shift / rounds-due card (counts only; can be hidden).
- Education suggests 12 behavioral-health credentials (CPSS/CPS, NCPS, CPRS, MHT, BLS/CPR, crisis prevention, Mental Health First Aid, QPR, WRAP facilitator…).

### Privacy
- The workspace is one file encrypted with AES-256-GCM under an Android Keystore key, in app-private storage. It's excluded from Android backup and device transfer and is never synced to Desktop or Drive, indexed by Ask my files, or read aloud in the car.
- Census and rounds are erased after 8–72 hours (24 by default), and drafts about other people after 1–90 days (14 by default). You can erase everything from the workspace settings.

## [5.0.0] — 2026-11-01

BlackLab comes to the car, summarizes anything you pick, and gains an expert knowledge library you can talk to.

### Added
- **Android Auto**, designed around the 2023 Hyundai Santa Fe (8-inch wireless and 10.25-inch USB displays). See [docs/ANDROID_AUTO.md](docs/ANDROID_AUTO.md).
  - **Ask BlackLab** hands-free through the car's microphone (Car App API 5+, on-device speech recognition), with spoken replies.
  - **Brief me**: daily briefing and spoken summaries (tomorrow, week ahead, latest meeting, recent notes, open tasks, journal week).
  - **Agenda** with **Navigate** and **Call**; **Tasks** with check-off toggles and voice add; **Voice note** dictation to Brainstorm.
  - **Talk**: the conversation knowledge bases by voice, hands-free back-and-forth.
  - **Santa Fe Garage**: fuel level, range and odometer when the car reports them, service due, MPG, voice fuel and service logging, gas and dealer navigation.
  - Task and event reminders as heads-up cards on the car display with **Done** / **Snooze**.
  - Music and recordings in Android Auto's media launcher; "play … on BlackLab" voice search.
- **Summarize**: pick any mix of documents, notes, notebook pages, journal entries, events, tasks, recordings, dictations and files (PDF, Word, text, slides, e-books, CSV), then choose the summary — TL;DR, key points, detailed, executive brief, action items, decisions & questions, timeline, compare, study guide, talking points, spoken brief — or say exactly what you want. Gemini Nano reads long items in chunks (map-reduce); without it an on-device extractive engine produces every style. Save as a document or note, make tasks, read aloud, share.
- **Knowledge**: five expert knowledge bases — Software Engineering, Software Development, AI Engineering, AI Development, and GitHub & Git — 153 entries, searchable on the phone.
- **Talk**: seven conversation knowledge bases with their own persona and notes. Four draw on all five expert bases — Architecture Roundtable, Code Craft & Review, AI Builder's Lab, Ship It: DevOps & GitHub — and three cover everyday life — Health & Performance at 40, Money, Home & Family, Downtime & Interests. Grounded in the notes with Gemini Nano, or answered from them offline; history stays on the phone.
- **Garage** (phone): vehicle profile (trim, engine, AWD/FWD, VIN, tire pressure, oil spec, dealer), editable maintenance schedule with normal and severe intervals and what's due by miles or months, fuel log with MPG and cost per mile, service history, 2023 Santa Fe facts and Android Auto setup steps.
- Assistant commands: “summarize this week as action items”, “let's talk about system design”, “what is RAG?”, “log fuel 13 gallons for 48 dollars at 31,250 miles”, “I got an oil change at 31,000”, “what's due on the car?”, “navigate to my next meeting”, “find a gas station”.
- Home shortcuts for Summarize, Talk and Garage; drawer entries for Knowledge & Talk, Summarize and Garage.
- Settings › Android Auto: speak replies in the car, hands-free conversations, brief me when I connect.

### Changed
- The music service is now a browsable media library (Media3 `MediaLibraryService`).
- Assistant speech is tagged as assistant audio so the car and Bluetooth duck music under it.

## [4.6.0] — 2026-11-01

A polish release: BlackLab should feel smoother, more consistent and more responsive in the hand.

### Added
- **Connected motion**:
  - A task (on Home or in To-Do) or a note card grows into its full screen and shrinks back when you return.
  - Screens slide and fade with a consistent easing, and the back gesture previews the screen underneath.
- **Haptic feedback**:
  - A tick when you switch tabs or reopen a task; a confirm buzz when you complete a task or place a signature.
  - A firmer buzz for deletes and swipe-to-delete, the long-press buzz on menus, and on/off clicks on every switch.
  - Settings › Appearance › **Haptic feedback** turns it off.
- **Reduce motion** (Settings › Appearance): instant, simpler transitions and still placeholders. It switches on by itself when Android's *Remove animations* is set.
- **Loading placeholders** in place of spinners: Home's task list, PDFs, documents, journal entries, spreadsheets, the music library and the form dialog show soft shapes of the content, so nothing jumps around when it arrives.
- **Instant task ticks**: completing a task on Home or in To-Do checks and strikes it through immediately with a springy check, then the save follows.
- **Smoothness meter** (Settings › Problem reports): the share of frames drawn on time this session, and the slowest screens. Counted on the phone only.
- **Animated launch screen**: the flask draws itself, fills and bubbles while BlackLab starts.

### Changed
- **Typeface**: BlackLab now uses **Inter** (SIL Open Font License 1.1, bundled). Timers, clocks and big numbers use even-width digits so they don't shift as they count.
- **Icons**: one rounded icon style across the app. Bottom-bar icons are outlined and fill in when selected.
- **Empty screens** show a small illustration in your palette's main, secondary and third colors, easing in.
- **Large titles** on Settings, Signatures, App updates and Problem reports shrink into the top bar as you scroll.
- **Bottom bar** slides away while you scroll down a list and returns when you scroll up.
- **Widgets** (Today, Checklist, Quick capture) use your account's palette and BlackLab's light/dark setting, including Auto. They follow your wallpaper colors when Material You is on, and redraw when you change colors.
- **Faster start and first scrolls**: a bundled baseline profile has Android precompile BlackLab's code at install (`profileinstaller`).
- Home builds "Since you last looked" only when its data changes.

### Fixed
- Long-press menus could vibrate twice, and they now respect the haptics setting.

### Notes
- Tests: 50 JVM unit tests (new: the frame-timing rules) and 15 on-device tests.

## [4.5.0] — 2026-11-01

### Added
- **Fill in PDF forms for you** (PDF › ⋮ › Fill in form):
  - **Auto-fill my details** recognizes what each field asks for from its name and tooltip (e.g. "Applicant_LastName", "txtEmail", "Date of Signature"). It fills your name, first and last name, job title, organization, email, phone, address, city, state, ZIP and today's date. Each field shows what it was matched to and has a one-tap fill button. Fields such as date of birth are left alone.
  - Signature fields, and text fields meant for a signature or initials, get **Sign here**: pick a saved signature or initials (confirmed with your fingerprint) and it's drawn inside the box, scaled to fit.
  - **Lock as finished copy** turns on automatically when you sign.
  - **My details for forms** (in the form dialog and in Signatures) holds your email, phone and address. They're kept only on the phone and not synced to BlackLab Desktop. Name, title and organization come from Settings › You.
- **"Since you last looked" on Home**:
  - **What it lists:** tasks finished or added elsewhere (a widget, the desktop, the assistant), new calendar events, reminders that went off, recordings saved (and whether the transcript is ready), new or edited documents, new notes, and Live Share guest comments.
  - **How it behaves:** newest first, with a tap to open each item and **Got it** to clear. It covers the time since you last viewed Home, up to 3 days.
- **Automatic light/dark** (Settings › Appearance › Auto):
  - **Sunset to sunrise:** times are worked out on the phone from your approximate location (the last known position, rounded to ~10 km; nothing is looked up online).
  - **Custom hours:** dark from 7 PM to 7 AM by default, adjustable. Also used when no location is available.
  - **Switching:** happens on time while you're using the app. Also available from BlackLab Desktop.

### Fixed
- **Flattened forms came out blank.** Filling a form with *Flatten* ticked dropped every answer, because values were never drawn into their fields before flattening. Values are now drawn as they're set, and flattening keeps them. Found by the new form test.
- **Dark ink on dark notebook paper** was invisible. Near-black ink now shows light while the page is dark, and the stored color is unchanged, so it flips back on white paper.

### Notes
- Tests: 48 JVM unit tests (new: form-field recognition, sunrise/sunset and dark windows, the activity feed) and 15 on-device tests (new: build a real PDF form, auto-fill it, sign the signature box, flatten, and check the result).

## [4.4.0] — 2026-11-01

### Added
- **Signatures** (drawer › Signatures, or Settings):
  - Draw your signature and initials with a finger or stylus (smooth ink that starts the moment you touch; black, blue or navy; fine, medium or bold), or type your name in a script, classic, casual or clean font. Keep several, set a default, rename or delete.
  - **Sign a document**: choose a PDF, Word (.docx), picture, RTF, Markdown, HTML or text file, or scan a paper document. BlackLab converts it to a PDF, opens it with the Sign tool ready, and you tap to place your signature, initials and today's date, then save it to *Documents › Signed*.
  - **PDF › Mark up & sign** now lists your saved signatures and initials and adds a **Date** stamp. Initials are placed at initial size.
  - **Documents › Insert › Signature** and **notebook pages › Insert › Signature** put a saved signature into the document or page. It comes along when you export to PDF or Word.
  - **Confirm before signing** (on by default): fingerprint, face or screen lock before a saved signature is placed, then not again for 3 minutes.
- **Colors** (Settings › Appearance › Colors): choose a palette for each account from Mint, Cobalt, Violet, Coral, Rose, Forest, Amber, Ocean and Graphite. Each palette has a main color, a **complementary secondary** and a third (tertiary) color. Optionally pick a different secondary, and see a live light-and-dark preview. Every color meets WCAG AA contrast (4.5:1). Also changeable from BlackLab Desktop.

### Changed
- **Light mode** has matching color sets: the secondary and tertiary colors now have soft containers (selected chips, the navigation highlight, badges) with deep text colors, instead of Material's default purple. Backgrounds carry a faint tint of your main color. In dark mode, selections use a deep tint of your main color, unless you pick a secondary.
- **Home dashboard updates live**:
  - **Time:** it re-checks every minute and whenever you return to it, so events that ended drop off ("✓ 2 finished earlier today"), the one happening now is marked **NOW**, overdue tasks flip at midnight and the greeting follows the time of day.
  - **Phone calendar:** new events in your phone's calendar (e.g. Google Calendar) appear as soon as they sync.
  - **Tasks:** the Focus header counts tasks finished today.

### Security
- The single signature that PDF markup saved in 4.1–4.3 was an unencrypted image. It is moved into the encrypted store on first use, and the old file is overwritten and deleted.
- Signatures are excluded from cloud backup and device transfer.

### Notes
- Tests: 39 JVM unit tests (new: the signature index and its one-default-per-kind rule) and 14 on-device tests. New on-device tests check that signatures are encrypted at rest and reload, that the old signature migrates, and that text and pictures become signable PDFs.

## [4.3.0] — 2026-11-01

### Added
- **Home-screen widgets** (Glance):
  - **Today** — the rest of today's events, classes, deadlines and note reminders, plus tasks that are due, overdue or in My Day. Tick tasks off from the widget; repeating tasks roll forward as in the app, and tasks finished today stay listed (struck through) so a mis-tap can be undone. **+** adds a task.
  - **Checklist** — pin a Brainstorm checklist or note. Tick items (matched by text, so a list edited in the app since the widget drew is never ticked in the wrong place) and add items with **+**. Reconfigurable.
  - **Quick capture** — “Take a note…” plus checklist, voice note (recorded and transcribed), photo note (text in the photo is recognized after saving) and task buttons; adapts to its size. Capture is **write-only**: it works without unlocking BlackLab and never lists existing content. Choose Personal or Professional per capture.
  - Settings › Home-screen widgets adds any widget in one tap. **Hide details in widgets** shows counts only; widgets placed on a lock screen always hide details; picking a note for a widget asks for the app lock when it's on.
- **Safe in-app updates** (Settings › App updates) from GitHub Releases. An update is offered for install only after three checks pass: its **SHA-256 matches the checksum published with the release**, it is **signed with the same certificate as the installed app**, and it is the same package with a higher build number. It is re-hashed right before the install, downloads are accepted only over HTTPS from GitHub's hosts, and Android verifies the signature again. Optional weekly check (off by default) with a notification; skip a version; nothing downloads without your OK.
- **Problem reports** (Settings › Problem reports) — crashes are recorded as they happen, and freezes (ANRs), native crashes and start-up failures that Android reports are picked up on the next launch. Reports stay in the app's private storage (newest 30, not in backups) and leave the phone only if you share them. After a crash, BlackLab mentions the saved report once.
- **Automated checks on every push**: a secret scan (`scripts/secret-scan.sh`) for keys, tokens, keystores and signing passwords; unit tests; debug and release builds; and **on-device tests on an Android 15 emulator** for the database upgrade from 4.1, pictures on pages and every export format, notebook export/import, voice recording, location reminders, the widgets, update verification and the crash log.

### Fixed
- Exporting, printing or Live-Sharing a Brainstorm page that contains a picture failed (the temporary file name for the picture was too short for Android). Found by the new on-device tests.

### Security
- **Other apps can no longer drive BlackLab through its "open with", share and shortcut entry points.** Before, an installed app could:
  - make BlackLab copy its own private files (database, settings) by sending a `file://` link to them or a link into BlackLab's own file provider;
  - open a private file in the viewer or editor through a deep link;
  - run any assistant command (for example, change a setting or send an email) through the shortcut's `ask` extra.

  Incoming links to BlackLab's private folders are now refused, deep links can't carry file paths, and the only request an outside intent can make of the assistant is the **Brief me** shortcut.
- **Desktop companion**: requests must name `127.0.0.1` or `localhost` in their Host header. This blocks DNS-rebinding pages that point their own domain at your computer.
- **BlackLab Desktop (web)**: a Content-Security-Policy now allows only the page's own scripts and Google's sign-in library to run, which protects the Drive token held while you're signed in.
- Bouncy Castle (pulled in by the PDF tools) raised from 1.72 to 1.80 for CVE-2024-29857, CVE-2024-30171, CVE-2024-30172 and CVE-2024-34447.
- Opening two different files with the same name from other apps no longer overwrites the first.

### Notes
- New permission: install updates (`REQUEST_INSTALL_PACKAGES`). Android asks you once, the first time you install an update from inside BlackLab.
- New library: Jetpack Glance 1.1.1 (Apache-2.0). Android lint now passes with no errors (Media3 and CameraX experimental APIs are explicitly opted in).
- Tests: 36 JVM unit tests (16 new: incoming-intent guard, version comparison, release parsing, checksum parsing, download-host allowlist, crash report format and pruning, Today and checklist selection) and 11 on-device tests (new: the PDF tools — create, page numbers, merge, split, password protect and unlock).
- The self-update path was tested end to end on the emulator: a newer build passed all checks, Android showed its update confirmation and the app was replaced in place.

## [4.2.0] — 2026-10-31

### Added
- **Brainstorm** (drawer › Brainstorm) — one place for notebooks, quick notes and ideas.
- **Notebooks, OneNote-style** — notebooks › colored section tabs (with section groups) › pages and subpages. Each page is a free-form canvas:
  - **Type anywhere**: tap empty space to start a text box; drag its bar to move it and its edge to resize. Pinch to zoom; the canvas grows as you write.
  - **Formatting**: styles, bold/italic/underline/strike, font size and color, highlight, bullets, numbering, checklists, indent, alignment, tables with row/column tools, links and **links to other pages**.
  - **Tags**: To Do (tickable), Important, Question, Idea, Critical, Remember for later, Definition, Follow up, Discuss, Schedule, Contact, Address, Phone, Website, Password and Book. They collect in a **Tags summary** across all notebooks, where To Dos can be ticked or sent to To-Do as tasks.
  - **Draw**: pen, highlighter, stroke eraser, line, arrow, rectangle and ellipse, colors and widths, stylus-only mode, and a **lasso** to move, duplicate, recolor or delete ink and objects together.
  - **Insert**: pictures (the text inside them is recognized on-device and becomes searchable), camera photos, document scans, any file as an attachment, **voice recordings with a live transcript**, dictation, date and time, and lines.
  - **Quick math**: type `1250*1.08=` then a space and the answer appears.
  - **Page tools**: templates (lined, grid, dots, to-do, meeting notes, Cornell lecture notes, project overview, mind map, SWOT, pros & cons, daily and weekly planners, retrospective), page color, rule lines and grids, find on page, read aloud, undo/redo, page reminders, and move/copy pages between sections and notebooks.
  - **Organizing**: recent pages, a recycle bin that keeps deleted pages for 60 days, and search across every page (including text found in pictures and transcripts).
- **Password-protected sections** — AES-256-GCM with a PBKDF2 key; pages, pictures, recordings and history are encrypted on the phone and left out of search while locked. Unlocked sections lock again after 20 idle minutes or with “Lock all”.
- **Notes, Keep-style** — a masonry board (or list) with pinned and other notes, plus:
  - **Content**: checklists whose ticked items collapse below, Enter for a new item, reorder and indent; photos with **“grab image text”**; sketches you can re-edit; voice notes that record and transcribe at the same time.
  - **Look**: 12 colors and themed backgrounds.
  - **Organizing**: labels (create, rename, delete, filter), archive, and a trash that empties after 7 days. Long-press to multi-select and pin, color, label, archive, copy or delete.
  - **Reminders**: by time with repeats, or **by place** (arrive or leave, with a radius).
  - **Moving**: send a note to a notebook as a page, or share text from any app as a new note.

### Recommendations implemented (sharing, collaboration & smart tools)
1. **Share anywhere** — export a page to PDF, Word, Markdown, plain text or an exact-layout web page, or print it (with Save as PDF). Send notes as text or pictures. Export a whole notebook as a portable **.blnotebook** package (locked sections stay encrypted inside) and import it on another phone.
2. **Live Share over Wi-Fi** — create a private link and QR code for one page or note. Anyone on the same network opens it in a browser, with live updates and no account; nothing is uploaded. Choose **view, comment or edit** (guests can tick and add checklist items), an expiry of 1 hour to 1 week, see how often the link was opened, and turn it off at any time. Links are random 128-bit tokens scoped to a single item, served with a strict Content-Security-Policy and rate-limited. The desktop API still needs the pairing code.
3. **Review & history** — comment threads on pages and notes, from you or Live Share guests, with resolve and reopen and a “new from guests” inbox. Page history keeps a snapshot every 10 minutes (the last 40) with preview and one-tap restore.
4. **Smart brainstorming & tasks** — on-device Gemini Nano (with offline fallbacks) to **summarize** a page, pull out **action items** and turn them into tasks or To Do tags, **brainstorm ideas**, and build a **mind map** page around any topic (5W1H and SCAMPER prompts when Nano isn't available). Notebook pages and notes are also searchable from the assistant's “Ask my files”.

### Notes
- Database version 2 (migration adds the notebook, page, note, label, comment and share tables; existing data is untouched).
- New permission: background location, requested only when you set a place reminder.
- New library: ZXing core 3.5.3 (Apache-2.0) for QR codes.
- Pages and notes are stored as files in BlackLab › <profile> › Brainstorm and are covered by the existing backups of that folder.
- Pages never reach the network: remote images pasted into a page don't load.
- Unit tests for text and tag extraction, section encryption, templates, ink rendering, HTML sanitizing, note data, the migration and share tokens.

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

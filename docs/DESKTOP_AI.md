# Desktop AI and `./daddyshome`

BlackLab's phone assistant can think with the local model on your computer: Ollama, through the
BlackLab Desktop companion. While the phone is linked, the assistant, Summarize, Talk, Ask my
files, meeting actions and Brainstorm's smart tools use the desktop model. When it isn't
linked, they fall back to Gemini Nano on the phone and then the offline rules, so the assistant
always works.

## Start everything

```bash
./daddyshome            # from the repo, or from your home folder (~/daddyshome)
./daddyshome qwen2.5:14b    # switch the desktop assistant's model (and make it the default)
./daddyshome --status   # status board only
./daddyshome --open     # …then open the BlackLab Desktop window
./daddyshome --repair   # re-send the phone's key (after reinstalling the app)
./daddyshome --stop     # unload the model and unlink the phone
```

`./daddyshome`:

1. Starts Ollama if it isn't running (system service, or `ollama serve`).
2. Loads the model selected in BlackLab Desktop (`~/.config/blacklab-desktop/config.json` → `model`)
   and pins it in memory (`keep_alive: -1`), at the same context size the companion uses
   (`num_ctx`, default 8192). Ollama reloads a model whenever its context size changes, so the
   two must match.
3. Restarts the BlackLab Desktop companion so it runs the latest code.
4. Links every connected phone, over USB or Wireless debugging, both ways:
   - desktop → phone: `adb forward tcp:8765` (dashboard, settings, files)
   - phone → desktop: `adb reverse tcp:8770` (the phone reaches the companion at its own `127.0.0.1:8770`)
5. Pairs the phone the first time: it pushes a shared key to `BlackLab/.provision/keys.json`, which
   the app imports into its Keystore-encrypted store and deletes when it next opens or comes to the
   front.
6. Prints a status board and checks the whole path end to end.

## How it stays private

- The companion still listens on `127.0.0.1` only. The phone reaches it through the adb tunnel,
  so no network port is opened on the computer.
- `/phone/status`, `/phone/generate` and `/phone/ask` need the shared key (`Authorization: Bearer`),
  our Host header, and no browser `Origin`, so web pages can't use them.
- The app allows plain HTTP only to its own loopback address (`res/xml/network_security_config.xml`).
  Everything else stays HTTPS.
- The Behavioral Health workspace never uses this path.

## The BlackLab AI folder

Everything the desktop AI produces goes into one folder on the Desktop, **`~/Desktop/BlackLab AI/`**:

| Folder | What goes in it | Say |
|---|---|---|
| `Downloads/` | Files pulled from the phone (backups, documents, photos, scans, recordings, dictation), in the phone's own `Personal/…` and `Professional/…` layout | "open ai downloads" |
| `Documents/` | Notes and documents the AI writes (`2026-11-07-roof-inspection.md`) | "open ai documents" |
| `Chat History/` | Every conversation: a readable transcript per day (`2026-11/2026-11-07.md`) and `chat-history.jsonl`, the assistant's searchable memory | "open chat history" |
| `Task Logs/` | One log per job (below) | "open task logs" |

"open blacklab ai" opens the whole folder. All of it is private to your user (folders 700, files 600).
Companion 1.3 moves the old `~/BlackLab-Desktop` and `~/Desktop/BlackLab AI Task Logs` into place the
first time it starts.

**Chat memory.** The assistant has a `recall_chats` tool, so "what did we figure out about the chiller
yesterday?" searches the archive and answers from it. "show chat history for <words>" searches it
without the AI. Searching never remembers itself, so recall answers don't pile up in later results.

`~/.config/blacklab-desktop/config.json`: `"ai_folder"` moves everything; `"downloads_dir"`,
`"documents_dir"`, `"chat_history_dir"` or `"task_log_dir"` moves one folder; `"chat_history": false`
stops archiving chats.

## Privacy

Requests that look clinical (patients, clients, meds, diagnoses, SBAR, peer notes, Behavioral Health,
q15 checks…) and anything you start with **"privately:"**, **"off the record"**, **"private:"** or
"don't save this" are answered as usual but **never saved**: not in Chat History, not in memory, and
the task log records only that a private task ran (no request, tool details or reply). The reply's
source ends in "private, not saved" so you can tell. Social Security, card and medical-record numbers
are scrubbed from everything that is saved. config.json: `"private_by_default": true` saves nothing;
`"clinical_guard": false` turns the clinical check off.

## Memory and follow-ups

- **Follow-ups:** the last 3 exchanges of the past 15 minutes go with each request, so "find files
  named roof inspection" then "open the first one" works.
- **Long-term memory:** `Chat History/memory.md` is a plain list the AI reads before every answer.
  "remember that Trane is our chiller vendor" adds a line, "forget the chiller vendor" removes it,
  "what do you know about me?" lists it, "open memory" opens it to edit. After 2 AM the AI reads the
  previous day's chats and adds up to five lasting facts (people, vendors, equipment, preferences,
  recurring duties). It never stores health details or guesses. `"memory_auto": false` turns that off.

## Document search

The AI can search inside **Documents/** and **Downloads/** (Markdown, text, CSV, HTML, JSON, PDF) using
the local `nomic-embed-text` model, so nothing leaves the computer. Ask "search my documents for what
the roof inspection found" for an answer with its sources, or just ask a question about your files and
the model uses its `search_documents` tool. The index lives in `~/.cache/blacklab-desktop/doc-index.json`
and updates itself on every search. `"embed_model"` picks another embedding model.

## Hand-off jobs (companion 1.4)

The phone hands the computer work it shouldn't wait for, and gets the results as notifications.

| Job | Starts when | Result |
|---|---|---|
| **transcribe** | a recording is saved (automatic), **Transcribe on computer**, or "transcribe my last recording" | Whisper transcript in the recording, minutes in Documents › Meetings (phone and `BlackLab AI/Documents/Meetings`), action items as tasks |
| **equipment** | a Snap-to-Work-Order photo | the unit in `BlackLab AI/Equipment` (SQLite + a Markdown card per unit), linked to the work order |
| **brief** | 05:30 daily, or 10 minutes after Night Mining stops when it's installed (07:10 here; `"brief_time"` overrides), "desktop brief", or the desktop's button | ranked morning brief in Documents › Briefs |
| **weekly** | Fridays 15:30 (`"weekly_time"`), "weekly summary" | one-page operations summary |
| **ask** | "in the background: …" | the desktop assistant's answer, with 8 tool rounds instead of 4 |

- **Transport:** `POST /phone/upload?name=` streams a file (2 GB max) into `BlackLab AI/Downloads/From phone`;
  `POST /phone/jobs` starts a job; `GET /phone/jobs` lists running jobs and finished ones the phone
  hasn't collected; `POST /phone/jobs/ack` marks them collected. All need the phone's key.
- **Outbox:** work queued while the computer is unreachable waits on the phone and goes on the next
  link. Jobs survive a companion restart (`~/.cache/blacklab-desktop/jobs.json`).
- **One at a time:** a single worker runs jobs so the GPUs never juggle Whisper, the vision model and
  qwen3 at once. Afterwards the main model is reloaded fully onto the GPUs if it was squeezed out.
- **Privacy:** a recording with 3 or more clinical terms, or any job marked private, is processed but
  not kept: no Meetings file, no index entry, no equipment links, and the audio and result are deleted
  once the phone collects them.
- **Context snapshot:** every 30 minutes while linked the phone sends `POST /phone/context`: open and
  recently completed tasks (work-order notes included), the next 3 days of events, coursework due in 14
  days, exams in 30, flashcards due and weather alerts. Behavioral Health data is never in it. The
  computer links work orders to equipment by serial number or tag.
- **Night Mining:** while `~/Desktop/mining` miners hold the GPUs and CPU (23:00–07:00), jobs wait
  ("Waiting: the computer is mining") and start when mining stops; the nightly memory update waits too.
  `"run_during_mining": true` overrides.
- **Setup:** `./daddyshome --install-whisper` (faster-whisper in `~/.local/share/blacklab-desktop/whisper`)
  and `./daddyshome --install-vision` (`qwen2.5vl:7b`). config.json: `"whisper_model"` (default
  `large-v3-turbo`), `"vision_model"`, `"brief_time"`, `"weekly_time"`, `"equipment_dir"`.

Measured on this computer: a 30-second meeting transcribes in 9 s on the GTX 1060 and minutes take ~8 s
more; a nameplate takes 35–50 s (vision model load included); a morning brief ~50 s.

## Notices and optional add-ons

- `POST /local/notify {"title", "text"}` (local only, `X-BlackLab: 1`) queues a notification the phone shows on
  its next check-in. Other local tools use it, for example a personal trading journal asking for approval.
- If `~/.local/bin/desk` exists (or `"crypto_desk_cmd"`), "crypto status" runs `desk status` read-only.
- `~/Desktop/BlackLab AI/Business Intelligence` and `~/Desktop/BlackLab AI/Crypto Desk`, when present, are
  indexed for "search my documents" and open by name.

## Protected paths

No AI tool can read, open, list or search wallet keys and recovery phrases (`~/Desktop/mining/wallet-backup`,
`~/Desktop/mining/config`), `~/.ssh`, `~/.gnupg`, keyrings, browser profiles, cloud credentials, BlackLab's
own keys (`~/.config/blacklab-desktop`, `~/.config/blacklab-crypto`, `keystore/`), or any file named like a
seed, mnemonic, recovery phrase, private key, wallet file, `.pem`/`.jks` or `secrets.json`, whatever a request
or a document asks. Add more with `"protected_paths"` in config.json (paths relative to home).

## Tool guard

Small models sometimes "try things". A tool that changes something only runs when the request asks for
that kind of action: `phone_command` needs a phone-app word (task, calendar, note…), `desktop_note`
needs note/save/write…, `open_url` needs open/visit/link…, `launch_app` and `open_path` need open/
launch/show. A blocked call is noted in the task log. The model is also told the real home folder, and
made-up paths like `/home/user/Downloads` are mapped onto it. With these, qwen3:8b went from 9/10 to
12/12 on the desktop test set and granite4.2:8b stopped causing side effects (9/12, about twice as slow).

## Task logs

Every job the desktop AI does is written down in **`Task Logs/`**, whether the phone's assistant sent it
("on my computer …", "ask my desktop …") or you asked in BlackLab Desktop.

- One Markdown file per task in a month folder (`2026-11/2026-11-06_14-03-22_find-my-resume.md`):
  when it ran and how long it took, where it came from, which engine answered, the request, every
  step and tool with its arguments and result, the reply, and any error.
- A **Files** table lists everything the task created, edited, downloaded, read or opened, so you can
  always see what the AI touched. Pulling files from the phone lists each downloaded file.
- `task-index.csv` has one row per task (open it in a spreadsheet to scan or filter).
- The folder and files are readable only by your user (700/600), because logs can quote file contents.
- Say "open task logs" to the desktop assistant to open the folder. `./daddyshome --status` shows where
  they are.
- `~/.config/blacklab-desktop/config.json`: `"task_log_dir"` moves the folder, `"task_logs": false`
  turns logging off.

Raw text generation for the phone's own features (summaries, Talk, Ask my files) is not a desktop task
and isn't logged. Developers adding a desktop tool: decorate it with `@logged_tool("name")` and call
`task_file("created" | "edited" | "downloaded" | "deleted" | "read" | "opened", path)` for every file it
touches.

## Wi-Fi

Pair the phone with Android's **Wireless debugging** (Developer options):
`adb pair <ip:pairing-port>`, then `adb connect <ip:port>`, then `./daddyshome`. `adb reverse` works
the same over Wi-Fi, so the phone keeps its desktop brain away from the cable.

## On the phone

- **Settings › Assistant & desktop › Desktop AI** shows the model, latency and a Test button,
  and has the switch to turn it on or off.
- Ask "desktop AI status", or send work to the computer: "on my computer open my downloads",
  "ask my desktop how much disk space is left".
- Source labels show who answered: "Desktop AI · qwen3:8b" or "Gemini Nano · on-device".

## Obsidian vault & MCP (companion 1.5.0)

- The companion keeps `~/Obsidian/BlackLab` current (every 10 minutes, and whenever the phone sends its feed) and commits each change.
- Desktop AI tools: `vault_search` (reads content → counts as tainted for `open_url`) and `vault_write` (only when the request asks to save/note/log).
- MCP server for agents: `claude mcp add --scope user blacklab-vault -- python3 desktop/companion/vault_mcp.py`. Tools: `vault_checkin`, `vault_status`, `vault_search`, `vault_read`, `vault_write`, `vault_log`, `vault_capture`, `vault_recent`, `vault_nodes`, `vault_node_add`. It goes through the companion when it runs (one writer), otherwise writes directly.
- Claude Code hooks: `vault_hook.py start|end` (SessionStart / SessionEnd) — sign-in reminder, then a session note with files changed and commits. Prompts are never copied.

## Reverse engineering with REA (companion 1.6.0)

`desktop/companion/reverse.py` gives the desktop AI (qwen3:8b) and the phone [REA](https://github.com/morluto/rea)
("Reverse Engineer Anything", MIT, npm `rea-agents`, pinned 6.8.0) with Ghidra 12.1.x as the decompiler.

- **Toolchain (user space):** Node 24 + JDK 21 + Ghidra in `~/.local/blacklab-toolchain/`, REA in
  `~/.local/share/blacklab-desktop/rea` (`python3 desktop/companion/reverse.py install`; `python3 reverse.py` prints status).
  REA 6.8 needs Node ≥ 22.23.4 / 24.22.1. The system Node is too old, so the toolchain Node is used.
- **Tool `reverse_engineer`:** actions `overview`, `find_code` (text → string → xrefs → decompiled functions in one step;
  small models do this badly on their own), `strings`, `functions`, `search`, `decompile`, `function`, `xrefs`,
  `disassemble`, `inspect` (format parsers), `objdump`, `report`, `help`, `status`.
- **Job `reverse`:** a full triage of one file → `~/Desktop/BlackLab AI/Reverse Engineering/<date> <name>/`
  (`report.md`, `evidence/*.json`, `strings.txt`, `functions.txt`, `code/*.c`). For a stripped ELF it follows `entry` to
  `main`, follows strings that match the user's question, and has the model write the report from that evidence only.
- **Rules (no model):** `reverse engineer <path> [and <question>]`, `decompile the function that prints <text> in <path>`,
  `rea status`, `how do I … with rea` (answered from REA's own docs, copied into `Reverse Engineering/REA reference/`).
- **Safety (tests/test_reverse.py):** an allowlist of read-only commands. Nothing that runs, debugs, instruments or captures
  the target, touches a device, installs or updates, or writes outside the workspace. Targets pass the path guard (home, or
  /usr, /opt; never protected paths). Values can't inject flags. Only one Ghidra runs at a time, and none while mining.
  Results count as untrusted content (`READS_CONTENT`). Any code block in a reply that isn't in the tool output gets a
  warning, because the 8B model will "simplify" (invent) code.
- **Phone:** `ReverseEngineering.route` (ReverseEngineeringTest). "reverse engineer <file>" starts a report job, and a phone
  file is uploaded. Ghidra questions run as background `ask` jobs (they outlast the 180 s chat timeout). Status and how-to
  questions are answered right away. Any program-like file has ⋮ › **Reverse-engineer on computer**. Reports arrive as
  documents in *Reverse Engineering*.
- Only analyze software you own or are allowed to examine.

## The vault graph (companion 1.6.0)

`desktop/companion/vault_graph.py` runs on every vault pass (tests: `tests/test_vault_graph.py`).

- **Profile** (`PROFILE`): graph forces (link strength was 0, so nodes never clustered), one color group per node by `up:`
  or folder, tags hidden, and a *Graph views* bookmark group with a saved graph per node. It's applied once per profile
  bump, so your later tweaks are kept until the next bump.
- **Typed links:** node and subnode notes carry `synapses:`. `related:` comes from local embeddings: cosine against an adaptive
  bar (this vault's 95th-percentile pair similarity, clamped to 0.72–0.90), at most 3 per note, mutual, skipping pairs
  that are already linked. A pair stays while it's within 0.03 of the bar. Only notes that changed are re-embedded, and a pass
  with no changes does no work. Your own `related:` entries are kept.
- **Graph Health** (`90 System/Graph Health.md`): orphans with a suggested node, unresolved links, hubs, related stats.
- **Plugins:** Breadcrumbs 4.22.3 (michaelpporter), Extended Graph 2.7.7 (elsatam) and Advanced URI 2.2.1 (vinzent03).
  They're downloaded from GitHub releases and verified against pinned SHA-256 before writing. Only installed plugins are
  enabled. Their settings (`data.json`) are deep-merged, and their code is git-ignored in the vault. Obsidian asks once to
  turn on community plugins.
- **Advanced URI:** `show <note> in my graph` (desktop rule `VAULT_SHOW_RE`, phone intent `VAULT_SHOW`, `/phone/vault/show`)
  opens `obsidian://adv-uri?…&commandid=graph:open-local`. `/local/vault/graph` returns the numbers shown in the desktop UI.

## Agent skills, Ask my notes, facility ops and the ledger (companion 1.7.0)

**Agent skills.** 283 skills from Anthropic, Vercel, OpenAI, Google and Hugging Face live in
`~/.local/share/blacklab-desktop/skills`: shallow clones, updated weekly, never committed here.
- The desktop AI finds them with `skills_search` and loads one with `skill_use`. Naming a skill ("use the pdf skill to …") loads it up front.
- Skill text is third-party guidance. The AI never runs a skill's scripts (it has no shell); it shows you the commands. Like any other read content, skill text marks the request as tainted, so only links you typed get opened.
- Cards live in `70 AI/Skills/`.
- Phone: "what skills do you have", "find a skill for …", "use the … skill to …". MCP: `vault_skills`.

**Ask my notes.** [kb-core](https://github.com/paulmmoore3416/omnix-ai-desktop) (loopback, `http://127.0.0.1:8100`, or `kb_core_url` in config.json) indexes the vault.
- Synced notes go into the `blacklab-vault` collection; this computer's own folders (85–89) go into `blacklab-local`.
- Each pass sends only changed notes, and spends at most `recall_budget_s` seconds doing it.
- Answers come from the local model only. Vault text never goes to a cloud model.
- Phone requests search synced notes only, unless config.json sets `"phone_recall_local": true`.

**Facility ops.**
- `10 Facility/Templates` (yours to edit) and `10 Facility/Rounds`.
- `10 Facility/Panels`: one note per panel with a `| Ckt | Load |` table. **⚡ Panel Directory** is the room index.
- `10 Facility/SDS Binder`: one note per product. **🧪 SDS Binder** shows conflicts and stale SDSs.
- `40 Learning/🏅 Credentials.md`.
- Endpoints: `/local/vault/{round,panel,sds,shift_prep,credentials}`, and the same under `/phone/vault/`.

**Activity ledger.** `~/.cache/blacklab-desktop/vault/ledger.jsonl` holds one hash-chained line per action, using MemResort's fields (`ts, actor, action, source, target, model, provider, duration_ms, metadata`).
- Metadata only: never note text, queries or answers.
- `vault_ledger` (MCP) and `/local/vault/ledger` verify the chain.

**Colors and Graph FX.**
- `vault_colors.py` writes `.obsidian/snippets/blacklab-colors.css` from the node registry and restores the graph's color groups on every pass.
- `desktop/obsidian/blacklab-graph-fx` is copied into the vault and enabled once. If you turn it off, it stays off.
- Graph FX settings: signals, pulse, intensity, what counts as recent, time-lapse length, and respecting "reduce motion".


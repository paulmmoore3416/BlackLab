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

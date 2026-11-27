# Phone Agent Ops — design (BlackLab 5.19.0 + companion 1.15.2)

## Intent

Paul (the only user) wants the desktop AI's Agent Ops — worker, advisor, fork layer, running tasks, session log and nightly
code review — on his phone, both as something to glance at and as a live monitor, plus a few extras. It should feel like
the rest of BlackLab (Glass theme, cards, chips) and ride the existing phone link. Approved 2026-11-27 from this list:
Home card, live screen, home-screen widget, Quick Settings tile, live task notification, phone controls (run review now,
advisor switch), morning-brief line.

## Goals

1. **Home card "Desktop AI"**: status dot (Idle / Working / Offline), worker model, today's fork decisions and advisor
   consults with a 14-day trend, nightly-review chip (Reviewed / Skipped / Failed / Missed / Off). Tap → Agent Ops screen.
2. **Agent Ops screen** (live): worker + advisor tiles, Running now (live task steps as they land), fork-layer bars with
   sharp rate, advisor feed, session log, nightly review card with the verdict and **Open report** (rendered in the app).
3. **Home-screen widget** (Glance, like TodayWidget): the card's content; tap → screen.
4. **Quick Settings tile "Desktop AI"** (like AssistTile): subtitle Idle / Working · N / Offline; tap → screen.
5. **Live task notification**: an "ask" job the phone starts ("In the background: …") gets an ongoing notification showing
   its current step, replaced by the existing Done/Failed result notification when it finishes.
6. **Controls** (each behind a confirm dialog): **Run review now** (starts the nightly review service on demand), and the
   **advisor switch**: auto (local model, default) / off / Claude (opus). Private requests stay local regardless (already
   enforced by the companion).
7. **Morning brief line**: the 05:30 brief gets a short "Desktop AI" section: last night's review verdict and yesterday's
   fork/advisor counts.

## Non-goals

- No new transport (no SSE/WebSocket push). No changes to the desktop web Agent Ops beyond sharing the new fields.
- No editing of fork rules or advisor prompts from the phone. No cancel-task control (the companion has none today).
- Not multi-user; no per-device permissions beyond the existing shared key.

## Anti-goals (a failure even if it "works")

- Polling that drains the battery: nothing polls faster than the existing 20–60 s link check unless the Agent Ops screen
  is visible or a phone-started job is running.
- Anything from a private task reaching the phone in readable form (requests already show as "(private)", advice as
  blank; keep it that way). Nothing Behavioral Health related in the brief line.
- A phone control that can do more than its label says (the review endpoint only starts the read-only review — the
  same `nightly_review.py`, same Read/Grep/Glob-only Claude — and the advisor switch only accepts the three values).
- An on-demand review that wipes out last night's verdict or report, or pays to re-review commits already reviewed.

## Constraints (from the code today)

- Phone → computer calls go through `DesktopAi.request(method, path, body, timeoutMs)` to `/phone/*` routes, which the
  companion protects with `phone_guard()` (BlackLab Host, no browser Origin, shared key). Routes that mirror a local one
  follow the `/phone/models` ↔ `/local/models` pattern in `blacklab_desktop.py`.
- `DesktopAi.status` (StateFlow) already says whether the computer is reachable; `DesktopAi.init` runs the link loop
  (20 s while jobs exist, 60 s otherwise) and calls `DesktopJobs.sync`.
- `agent_ops()` in the companion already builds the full payload (now incl. `today`/`days` series and nightly review
  `setup`/`health`, companion 1.15.1). Live task logs (`LIVE`) carry request (or "(private)"), origin and steps.
- Background jobs (`agents.py`) run inside `C.task_log(...)`; job ids are not linked to their live log today.
- Notification channels live in `reminders/Notifications.kt` (`CH_DESKTOP` for results). Widgets in `widgets/`
  (Glance), tiles like `assistant/AssistTile.kt`. Theme in `ui/theme/` (Glass, Contrast).
- The nightly review runs as `blacklab-nightly-review.service` (systemd --user); the companion runs as a user service
  too, so it can `systemctl --user start --no-block` it.
- The companion has no general config writer; `CONFIG` is loaded from `~/.config/blacklab-desktop/config.json`.
- Release conventions: bump versionCode/versionName (33/5.18.1 → 34/5.19.0), sign with the release keystore, unit tests
  + lint, install with `adb -s <serial>` (Pixel 10 Pro XL $PIXEL_SERIAL), stage the .sha256 in `releases/`.

## Approaches considered

- **A. Poll one read-only endpoint (picked).** `GET /phone/agentops` returns the same payload as `/local/agentops`
  (trimmed for the phone). The screen polls every 2 s while visible; the card/widget/tile refresh from the existing link
  loop. Simple, matches existing routes, works over USB and tailnet, no new infrastructure.
- B. Push from the companion (SSE/WebSocket over the link). Truly live, but new plumbing on both ends, reconnect logic
  over cellular Tailscale, and battery cost. Not worth it for one user.
- C. Ride the existing jobs sync only. No new calls, but 20–60 s staleness makes the "live monitor" pointless.

## The design

### Companion (1.15.2)

- `GET /phone/agentops` (phone_guard): `agent_ops()` minus nothing private beyond what the local view shows, plus:
  - `live[].job`: the job id when the task log was opened by a background job (set on the TaskLog by the job runner).
  - **Private tasks (Claude's call, after the builder check):** for a private log, `live[]` carries no steps at all —
    only `{"request": "(private)", "origin", "started", "job", "private": true, "step_count"}`. Step titles can carry
    tool reasons or clinical-content notes, and the phone shows them on the lock screen, so they never leave the computer
    for private work. The desktop web view is unchanged.
  - `nightly_review.report_md`: the latest report's Markdown, capped at 12 KB (read from the vault note or the fallback
    file named in the state), so the phone can render it without another call.
  - `controls`: `{"advisor": current setting, "advisor_choices": ["auto", "off", "claude:opus"], "review_running": bool}`.
- `POST /phone/agentops/review` (phone_guard): starts an **on-demand** review (a separate systemd --user unit,
  `blacklab-review-now.service`, installed by `install.sh`, running `nightly_review.py --on-demand`) with `--no-block`,
  unless a review unit is already active. Refuses with a note when `"nightly_review": false`. Returns
  `{"started": bool, "note": ...}`.
- `nightly_review.py` (decided after the builder check, Claude's call):
  - Every run records `last_reviewed_head` per repo in its state. `--on-demand` reviews only commits since that point
    (falls back to 24 h when there is none); with nothing new it returns "Nothing new since the last review" without
    calling Claude.
  - On-demand results go to their own state file (`review-now.json`); they never overwrite `nightly-review.json`, which
    the Home chip, widget and morning brief read. The payload carries both (`nightly_review`, `review_now`).
  - On-demand vault notes are named `{date} {HHMM} {repo} (on demand)`, so they never replace the nightly note.
  - The nightly run also starts from `last_reviewed_head` when it is newer than 24 h ago, so commits already reviewed on
    demand aren't paid for twice.
- `POST /phone/agentops/advisor` (phone_guard): body `{"advisor": "auto"|"off"|"claude:opus"}`; anything else → 400.
  Updates `CONFIG["advisor"]` and writes config.json atomically, keeping every other key.
- Brief: `run_brief` adds an `agent_ops` item to its data (yesterday's fork/advisor totals from the `days` series, last
  review status + verdict) and a "## Desktop AI" section to the prompt's section list.
- Unit tests for the pure parts: payload trimming/report cap, advisor value validation, config write preserving keys,
  brief data assembly.

### Phone (5.19.0)

- `link/AgentOps.kt`: model (data classes parsed from the payload, tolerant of missing fields), `refresh()` via
  `DesktopAi.request("GET", "/phone/agentops", …)`, a cached last snapshot (SharedPreferences JSON) so the card and widget
  have something to show offline, and `StateFlow<Snapshot?>`. Refreshed from the link loop at its existing cadence and
  every 2 s while the screen is visible. Pure parsing unit tested.
- `ui/agentops/AgentOpsScreen.kt`: the live screen described in Goals 2, Glass styling, sparklines drawn with Canvas,
  report rendered with the existing Markdown → HTML path in a WebView or the app's document viewer.
- Home card in `HomeScreen.kt` next to Projects (hidden when the desktop link is off).
- `widgets/AgentOpsWidget.kt` (Glance) + receiver; updated when the snapshot changes.
- `assistant/AgentOpsTile.kt` (TileService) + manifest entry.
- Live notification: when `DesktopJobs` sees a phone-started `ask` job running, a short-lived watcher polls
  `/phone/agentops` every 3 s, finds `live[]` with that job id, and posts/updates an ongoing notification (new low-importance
  channel "Computer working") with the latest step; stops when the job leaves running state or after 30 minutes.
  Phone-started jobs are the ones whose `origin` starts with "Phone". Every one of these notifications is
  `VISIBILITY_PRIVATE` with a public version that says only "Your computer is working", so nothing shows on the lock
  screen; a private job shows "Working… (private) · step N" even unlocked.
- Controls on the screen: Run review now, advisor switch; confirm dialog each; result as a snackbar.
- Navigation: a route for the screen from Home card, widget, tile and notification taps (deep link intent extra).

## Settled after the builder check (Claude's calls, flagged for Paul)

- Advisor settings outside the three choices (`local:<model>`, `claude:sonnet`, …) show read-only as "custom: …"; the
  switch still offers the three.
- The advisor write re-reads `config.json` from disk right before writing (`./daddyshome --stop` edits it too).
- `report_md` is cached by the report's mtime, not re-read on every 2 s poll.
- The brief's `agent_ops` item goes first in its data, ahead of the 22,000-character cut.
- The widget and tile show the snapshot's age ("2 h ago") when it is older than 10 minutes.

## Left to the builder

Exact layout, spacing and copy within the Glass style; data-class shapes; how sparklines are drawn; widget sizes; whether
the report shows in a WebView or the existing viewer; test names and structure.

# Phone Agent Ops Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Put the desktop AI's Agent Ops on the phone (Home card, live screen, widget, QS tile, live task notification,
controls) and add on-demand reviews and a brief line on the computer.

**Architecture:** The companion serves one read-only `GET /phone/agentops` (the `/local/agentops` payload, trimmed for
privacy) plus two guarded controls. The phone's `AgentOps` object caches the last snapshot and refreshes it from the
existing link loop, every 2 s while the screen is visible, and every 3 s while a phone-started job runs.

**Tech Stack:** Python 3.14 stdlib (companion, unittest); Kotlin + Compose + Glance + TileService (app, JUnit).

**Spec:** `docs/superpowers/specs/2026-11-27-phone-agent-ops-design.md`

## Global Constraints

- Versions: companion `VERSION = "1.15.2"`; app `versionCode = 34`, `versionName = "5.19.0"`.
- Phone routes use `phone_guard()`; every new route is under `/phone/agentops`.
- Private tasks: `live[]` entries carry no `steps`; only `request: "(private)"`, `origin`, `started`, `job`, `private: true`, `step_count`.
- Advisor switch accepts exactly `"auto"`, `"off"`, `"claude:opus"`; anything else → HTTP 400.
- Polling: never faster than the link loop (20 s / 60 s) except 2 s while `AgentOpsScreen` is resumed and 3 s while a phone-started (`origin` starts with "Phone") job is `running`, max 30 min.
- Live task notifications: `VISIBILITY_PRIVATE`, public version text "Your computer is working".
- On-demand reviews never write `nightly-review.json`; they write `review-now.json` and name notes `{date} {HHMM} {repo} (on demand)`.
- Toolchain: `JAVA_HOME=~/.local/blacklab-toolchain/jdk-21.0.12.1+1`, adb at `~/Android/Sdk/platform-tools/adb`, device `-s $PIXEL_SERIAL`.
- Companion tests: `python3 -m unittest discover -s desktop/companion/tests -q`. App unit tests: `./gradlew :app:testDebugUnitTest`; lint: `./gradlew :app:lintRelease`.
- Commits end with the session's Co-Authored-By / Claude-Session lines; author email is the GitHub noreply.

## Review Focus

1. **Computer offline or link off**: the card shows "Offline · updated 2 h ago" from the cache, the screen shows a notice, nothing crashes. Test: `AgentOpsTest.offlineSummaryUsesCacheAge` (Task 4).
2. **Companion older than 1.15.2** (`/phone/agentops` → 404): the screen says "Update the companion on your computer (needs 1.15.2)". Test: `AgentOpsTest.notFoundMeansOldCompanion` (Task 4).
3. **Sparse payloads** (`nightly_review: {}`, `advisor.recent` with nulls, no `days`): parsing yields empty lists and "—", never throws. Test: `AgentOpsTest.parsesEmptyAndPartialPayloads` (Task 4).
4. **Private live task**: no step text or request leaves the computer. Test: `PhonePayloadTest.test_private_live_task_has_no_steps` (Task 2).
5. **Run review now pressed twice / while the nightly runs**: the second call returns `started: false` with "A review is already running"; the button is disabled while `controls.review_running`. Test: `PhoneControlsTest.test_review_refused_while_running` (Task 2).

---

### Task 1: On-demand reviews in nightly_review.py

**Files:**
- Modify: `desktop/autopilot/nightly_review.py`, `desktop/autopilot/install.sh`
- Test: `desktop/companion/tests/test_nightly_review.py`

**Interfaces:**
- Produces: `NOW_STATE = ~/.cache/blacklab-desktop/review-now.json`; state files carry `"heads": {"<repo name>": "<sha>"}`; CLI flag `--on-demand`; systemd unit `blacklab-review-now.service` (oneshot, same env as the nightly one, `ExecStart=$PY $HERE/nightly_review.py --on-demand`).
- `since_for(repo: Path, heads: dict, on_demand: bool) -> tuple[str, str | None]` → `(since, base_sha)`: base is the recorded head when it is still an ancestor of HEAD; `day_changes(repo, since, base=None)` uses `base..HEAD` when base is set.

- [ ] **Step 1: Write failing tests** in `test_nightly_review.py`:
  - `test_on_demand_reviews_only_new_commits`: temp repo, commit A, `heads={"r": A}`, commit B → `day_changes(repo, *since_for(...))` log contains B, not A.
  - `test_nothing_new_skips_without_claude`: heads == HEAD, `--on-demand` → status `"Skipped"`, summary `"Nothing new since the last review"`, `subprocess.run` for claude never called (patch `N.ai_models.find_claude` to raise).
  - `test_on_demand_never_touches_nightly_state`: patch `N.STATE`/`N.NOW_STATE` to temp paths, run `main()` with `sys.argv=["x","--on-demand"]` → only `NOW_STATE` written.
  - `test_on_demand_note_name`: `note_name("BlackLab", on_demand=True, now=datetime(2026,11,27,14,5))` == `"2026-11-27 1405 BlackLab (on demand)"`; nightly == `"2026-11-27 BlackLab"`.
  - `test_heads_recorded`: after a Reviewed run (claude stubbed), saved state `heads["<repo>"]` == HEAD sha.
- [ ] **Step 2: Run** `python3 -m unittest tests.test_nightly_review -q` from `desktop/companion` → FAIL (missing names).
- [ ] **Step 3: Implement** `since_for`, `note_name(repo: str, on_demand: bool, now: datetime) -> str`, `NOW_STATE`, `--on-demand` in `main()` (refuse with "nightly review is off" when disabled), heads read from both state files (newest wins) and written to the state file of the run. The nightly run also uses `since_for` (base from heads when newer than 24 h). Add the unit + `--uninstall` entry in `install.sh` (no timer).
- [ ] **Step 4: Run** tests → PASS; `bash -n desktop/autopilot/install.sh` → no output.
- [ ] **Step 5: Commit** `Nightly review: on-demand runs review only new commits and keep their own state`.

### Task 2: Phone payload and controls on the companion (1.15.2)

**Files:**
- Modify: `desktop/companion/agentops.py`, `desktop/companion/blacklab_desktop.py` (routes near the `/phone/models` GET and the `/phone/ops/ack` POST; `TaskLog.__init__` gets `self.job = ""`; `agent_ops()` live entries gain `job`, `private`, `step_count`; payload gains `review_now`, `controls`; `VERSION = "1.15.2"`), `desktop/companion/agents.py` (job runner sets `log.job = jid` inside its `task_log` block)
- Test: `desktop/companion/tests/test_agentops.py` (new classes `PhonePayloadTest`, `PhoneControlsTest`)

**Interfaces:**
- `agentops.phone_payload(ops: dict, report_md: str) -> dict` — deep copy; private `live[]` reduced to the Global Constraints keys; adds `nightly_review.report_md` (≤ 12 000 chars, cut with "\n\n…(cut)").
- `agentops.ADVISOR_CHOICES = ("auto", "off", "claude:opus")`; `agentops.advisor_label(setting: str) -> str` (one of the choices, else `"custom: <setting>"`).
- `agentops.write_config_key(path: Path, key: str, value) -> dict` — re-reads the file, sets one key, writes atomically (0600), returns the new dict.
- `agentops.report_path(state: dict, vault_root: Path) -> Path | None` — `state["report"]` relative to the vault (adds `.md`) or absolute fallback path; None if missing.
- Payload `controls`: `{"advisor": str, "advisor_label": str, "advisor_choices": [...], "review_running": bool}`.
- Routes: `GET /phone/agentops` → `phone_payload(...)`; `POST /phone/agentops/review` → `{"started": bool, "note": str}`; `POST /phone/agentops/advisor` body `{"advisor": str}` → `{"ok": true, "advisor": str}` or 400 `{"error": "advisor must be one of auto, off, claude:opus"}`.
- `review_running` = `systemctl --user is-active` of `blacklab-nightly-review.service` or `blacklab-review-now.service` returns "active"/"activating".

- [ ] **Step 1: Write failing tests**:
  - `test_private_live_task_has_no_steps`: ops with a private live entry holding steps titled "Clinical content: x" → payload entry keys == `{"request","origin","started","job","private","step_count"}`, `"Clinical"` not in `json.dumps(payload)`.
  - `test_public_live_task_keeps_steps`, `test_report_capped` (20 000-char report → ≤ 12 010 and ends with "…(cut)").
  - `test_advisor_label`: `"auto"`→`"auto"`, `"local:qwen3:32b"`→`"custom: local:qwen3:32b"`.
  - `test_write_config_key_preserves_others`: file `{"a":1,"advisor":"auto"}`, external edit adds `"b":2` before the call → result `{"a":1,"b":2,"advisor":"off"}`, mode 0600.
  - `test_report_path`: vault-relative and absolute fallback cases; missing → None.
  - `test_review_refused_while_running`: `agentops.review_start(running: bool, enabled: bool) -> dict` → running → `{"started": False, "note": "A review is already running"}`; disabled → `{"started": False, "note": "The nightly review is off in autopilot.json"}`; else `{"started": True, "note": "Review started"}` (the route calls `systemctl --user start --no-block blacklab-review-now.service` only when `started`).
- [ ] **Step 2: Run** `python3 -m unittest tests.test_agentops -q` → FAIL.
- [ ] **Step 3: Implement** the helpers, `review_start(running, enabled)`, routes, TaskLog `job`, runner `log.job = jid`, live-entry fields, cached report text keyed by mtime, version bump.
- [ ] **Step 4: Run** full companion suite → OK; `systemctl --user restart blacklab-desktop.service`; `curl -s -H 'Host: 127.0.0.1:8770' -H 'X-BlackLab: 1' http://127.0.0.1:8770/local/agentops | python3 -c 'import json,sys;d=json.load(sys.stdin);print(d["version"],sorted(d["controls"]))'` → `1.15.2 ['advisor', 'advisor_choices', 'advisor_label', 'review_running']`.
- [ ] **Step 5: Commit** `Companion 1.15.2: /phone/agentops, review-now and advisor controls`.

### Task 3: Desktop AI line in the morning brief

**Files:**
- Modify: `desktop/companion/agentops.py`, `desktop/companion/agents.py` (`run_brief`: `data = {"agent_ops": ..., **rest}` first; add `'## Desktop AI'` (one or two lines) to the section list in the prompt)
- Test: `desktop/companion/tests/test_agentops.py` (`BriefItemTest`)

**Interfaces:**
- `agentops.brief_item(forks: dict, advisor: dict, review: dict, now: float) -> dict` → `{"yesterday_fork_decisions": int, "yesterday_advisor_consults": int, "last_review": {"status": str, "verdict": str, "when": "YYYY-MM-DD HH:MM"} | None}`; yesterday = the `days` entry for `day(now - 86400)`.

- [ ] **Step 1: Write failing test** `test_brief_item_counts_yesterday` (days series with yesterday sharp 3/split 1, advisor plan 2 → 4 and 2; review state Reviewed with summary "No findings." → verdict "No findings.") and `test_brief_item_without_review` (`{}` → `last_review` None).
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** full suite → OK.
- [ ] **Step 5: Commit** `Morning brief: Desktop AI line (review verdict, yesterday's Agent Ops)`.

### Task 4: AgentOps model on the phone

**Files:**
- Create: `app/src/main/java/com/blacklab/app/link/AgentOps.kt`
- Test: `app/src/test/java/com/blacklab/app/link/AgentOpsTest.kt`

**Interfaces:**
- `object AgentOps` with `data class Snapshot(val at: Long, val version: String, val worker: String, val advisorModel: String, val advisorMode: String, val forksTotal: Int, val forksToday: Int, val forkDays: List<Int>, val forks: List<Fork>, val consultsTotal: Int, val consultsToday: Int, val consultDays: List<Int>, val feed: List<Consult>, val live: List<LiveTask>, val jobs: List<JobRow>, val tasks: List<TaskRow>, val review: Review, val controls: Controls)` and small row classes `Fork(name, sharp, split)`, `Consult(at, stage, advice, silent, error, seconds)`, `LiveTask(request, origin, started, job, private, steps: List<Step>, stepCount)`, `Step(at, kind, title)`, `JobRow(id, title, status, origin)`, `TaskRow(started, request, status, answeredBy)`, `Review(status, summary, at, model, advisor, time, enabled, health, healthNote, reportMd, now: Review?)`, `Controls(advisor, advisorLabel, choices, reviewRunning)`.
- `fun parse(j: JSONObject, receivedAt: Long): Snapshot` (tolerant: missing → empty/0/"").
- `enum class Status { IDLE, WORKING, OFFLINE }`; `fun status(s: Snapshot?, online: Boolean): Status` (WORKING when `live` or running jobs non-empty).
- `fun summary(s: Snapshot?, online: Boolean, now: Long): String` — e.g. `"Working · 1 task"`, `"Idle · qwen3:14b"`, `"Offline · updated 2 h ago"`; age shown when `now - at > 10 min`.
- `val snapshot: StateFlow<Snapshot?>`; `val error: StateFlow<String>`; `fun load(c: Context)` (cache from SharedPreferences `"agent_ops"`), `suspend fun refresh(app: AppContainer): Boolean` (404 → error "Update the companion on your computer (needs 1.15.2)"), `suspend fun runReview(): Pair<Boolean, String>`, `suspend fun setAdvisor(v: String): Pair<Boolean, String>`. On a new snapshot it calls `AgentOpsWidget.refresh(context)` (Task 6) and `AgentOpsTile.requestUpdate(context)`.
- `DesktopAi.init` loop: after `DesktopJobs.sync`, `runCatching { AgentOps.refresh(container) }`.

- [ ] **Step 1: Write failing tests** `parsesFullPayload` (fixture JSON copied from a real `/local/agentops` response saved to `app/src/test/resources/agentops.json`), `parsesEmptyAndPartialPayloads` (`{}` and `{"nightly_review":{}, "advisor":{"recent":[null]}}`), `statusWorkingWhenLive`, `offlineSummaryUsesCacheAge` (at = now − 2 h, online false → `"Offline · updated 2 h ago"`), `notFoundMeansOldCompanion` (`AgentOps.errorFor(404)` == the message above).
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*AgentOpsTest*'` → FAIL.
- [ ] **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `App: AgentOps model, cache and refresh from the desktop link`.

### Task 5: Agent Ops screen and Home card

**Files:**
- Create: `app/src/main/java/com/blacklab/app/ui/agentops/AgentOpsScreen.kt`, `app/src/main/java/com/blacklab/app/ui/agentops/Sparkline.kt`
- Modify: `ui/AppRoot.kt` (`screen("agentops") { AgentOpsScreen() }`), `core/HomeLayout.kt` (`Card("agentops", "Desktop AI", "The computer's AI at a glance: working or idle, today's numbers and last night's code review (needs the computer link).")` after `projects`), `ui/home/HomeScreen.kt` (`"agentops" ->` card in the Projects style: status dot, `AgentOps.summary`, today's counts with `Sparkline`, review chip; `onClick = { shell.go("agentops") }`; hidden when `asstDesktopAi` is off)
- Test: `app/src/test/java/com/blacklab/app/core/HomeLayoutTest.kt` existing order tests must still pass (add `agentopsCardFollowsProjects`).

**Interfaces:**
- `@Composable fun Sparkline(values: List<Int>, modifier: Modifier, color: Color)` — Canvas bars, empty → nothing.
- `AgentOpsScreen()`: polls `AgentOps.refresh` every 2 000 ms in a `LaunchedEffect` tied to `Lifecycle.State.RESUMED` (`repeatOnLifecycle`). Sections in order: tiles (Worker, Advisor, Fork layer), Running now (private → "Private task · step N"), Fork layer bars, Advisor feed, Session log (8 rows), Nightly review card (setup line, health warning, status chip, summary, **Open report** → bottom sheet rendering `reportMd` via `DesktopJobs.markdownToHtml` in a WebView with JS off; on-demand result below it if present), Controls card (**Run review now** disabled while `reviewRunning`; advisor segmented choice of `controls.choices`, showing `advisorLabel`; each behind an `AlertDialog`; results in a snackbar).

- [ ] **Step 1: Write failing test** `agentopsCardFollowsProjects`: `HomeLayout.order("")` has `"agentops"` right after `"projects"`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement** screen, card, route. Add `"agentops"` to anything that lists allowed routes for intents (`IncomingGuard.routeAllowed` already allows it by charset; verify).
- [ ] **Step 4: Run** `./gradlew :app:testDebugUnitTest` → PASS; `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** `App: Agent Ops screen and Desktop AI Home card`.

### Task 6: Home-screen widget and Quick Settings tile

**Files:**
- Create: `widgets/AgentOpsWidget.kt` (`class AgentOpsWidget : GlanceAppWidget`, `class AgentOpsWidgetReceiver`, `object AgentOpsWidget.Companion.refresh(context)` → `updateAll`), `res/xml/widget_agentops.xml` (min 2×1, resizable), `assistant/AgentOpsTile.kt` (`class AgentOpsTile : TileService`; `companion fun requestUpdate(c)` → `TileService.requestListeningState`; label "Desktop AI", subtitle `AgentOps.summary`, state ACTIVE when WORKING; click opens `MainActivity` with `EXTRA_ROUTE = "agentops"` via `startActivityAndCollapse(PendingIntent)`)
- Modify: `AndroidManifest.xml` (receiver + tile service, mirroring TodayWidgetReceiver / AssistTile), `res/values/strings.xml` (`widget_agentops_label` = "Desktop AI"), `ui/settings/SettingsScreen.kt` (add `"Desktop AI" to AgentOpsWidgetReceiver::class.java` next to "Today")
- Test: `app/src/test/java/com/blacklab/app/widgets/WidgetLogicTest.kt` (`agentOpsWidgetLines`)

**Interfaces:**
- `fun AgentOps.widgetLines(s: Snapshot?, online: Boolean, now: Long): List<String>` (in `AgentOps.kt`): line 1 = `summary(...)`, line 2 = `"$forksToday decisions · $consultsToday consults today"`, line 3 = review `"Review: <status>"` or `"Review: missed"`.

- [ ] **Step 1: Write failing test** `agentOpsWidgetLines` (null snapshot → `["Offline", "—", "Review: —"]`; full fixture → three expected strings).
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** unit tests → PASS, `:app:compileDebugKotlin` → OK.
- [ ] **Step 5: Commit** `App: Desktop AI widget and Quick Settings tile`.

### Task 7: Live task notification

**Files:**
- Create: `app/src/main/java/com/blacklab/app/assistant/TaskWatcher.kt`
- Modify: `reminders/Notifications.kt` (`const val CH_WORKING = "computer_working"`, channel "Computer working", `IMPORTANCE_LOW`, no badge), `assistant/DesktopJobs.kt` (after `collect`, `TaskWatcher.ensure(app, jobs.value)`)
- Test: `app/src/test/java/com/blacklab/app/assistant/TaskWatcherTest.kt`

**Interfaces:**
- `object TaskWatcher { fun watched(jobs: List<DesktopJobs.Job>, origins: Map<String,String>): List<String>; fun text(job: DesktopJobs.Job, live: AgentOps.LiveTask?): Pair<String, String>; fun ensure(app: AppContainer, jobs: List<DesktopJobs.Job>) }` — watches `running` jobs whose origin starts with "Phone" (add `origin` to `DesktopJobs.Job`, parsed from the job payload); one coroutine on `app.appScope`, polls `AgentOps.refresh` every 3 000 ms, stops when no watched job is running or after 30 min; notification id `7300 + (job.id.hashCode() and 0xff)`, ongoing, `setOnlyAlertOnce(true)`, `VISIBILITY_PRIVATE`, public version "Your computer is working"; tap → `EXTRA_ROUTE = "agentops"`; cancelled when the job leaves running (the existing result notification follows).
- `text(...)`: title `"Computer: <job title ≤ 50>"`; body = last step title, or `"Working… (private) · step N"` when private, or job `progress` when no live entry.

- [ ] **Step 1: Write failing tests** `watchesOnlyPhoneStartedRunningJobs`, `privateTaskShowsOnlyStepCount` (live private stepCount 4 → body `"Working… (private) · step 4"`), `fallsBackToProgress`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `App: live notification while the computer works on a phone task`.

### Task 8: Release 5.19.0 and install

**Files:**
- Modify: `app/build.gradle.kts` (versionCode 34, versionName "5.19.0"), `releases/BlackLab-v5.19.0.apk.sha256`

- [ ] **Step 1:** Bump version. **Step 2:** `JAVA_HOME=... ./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease` → BUILD SUCCESSFUL, lint 0 errors.
- [ ] **Step 3:** `sha256sum app/build/outputs/apk/release/*.apk` → write `releases/BlackLab-v5.19.0.apk.sha256` in sha256sum format with the file named `BlackLab-v5.19.0.apk`.
- [ ] **Step 4:** `adb -s $PIXEL_SERIAL install -r <apk>` → `Success`; `adb -s $PIXEL_SERIAL shell dumpsys package com.blacklab.app | grep versionName` → `5.19.0`.
- [ ] **Step 5:** On the device: open the app, launch `agentops` route (`adb shell am start -n com.blacklab.app/.MainActivity --es route agentops`), screenshot (`adb exec-out screencap -p`) shows the screen with live data; Home shows the card.
- [ ] **Step 6: Commit** `BlackLab 5.19.0: Agent Ops on the phone` and a local tag `v5.19.0` (no push).

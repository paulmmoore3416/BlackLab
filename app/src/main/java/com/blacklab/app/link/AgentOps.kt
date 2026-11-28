package com.blacklab.app.link

import android.content.Context
import com.blacklab.app.AppContainer
import com.blacklab.app.assistant.DesktopAi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Agent Ops on the phone (5.19, companion 1.15.2): the computer's AI at a glance — the worker and advisor models, the
 * fork layer, what is running right now, the session log and the nightly code review — from GET /phone/agentops.
 *
 * The last snapshot is kept so the Home card, widget and tile still show something while the computer is away. It is
 * refreshed with the link check (every 20–60 s), every 2 s while the Agent Ops screen is open, and every 3 s while a
 * task this phone started is running (TaskWatcher). Private tasks arrive already stripped to "(private)" and a step count.
 */
object AgentOps {
    data class Fork(val name: String, val sharp: Int, val split: Int)
    data class Consult(val at: Double, val stage: String, val advice: String, val silent: Boolean, val error: String, val seconds: Double)
    data class Step(val at: String, val kind: String, val title: String)
    data class LiveTask(val request: String, val origin: String, val started: String, val job: String, val private: Boolean, val steps: List<Step>, val stepCount: Int)
    data class JobRow(val id: String, val kind: String, val title: String, val status: String, val progress: String, val origin: String, val private: Boolean)
    data class TaskRow(val started: String, val request: String, val status: String, val answeredBy: String, val tools: String)
    data class Review(
        val status: String, val summary: String, val at: Double, val model: String, val advisor: String, val time: String,
        val enabled: Boolean, val health: String, val healthNote: String, val reportMd: String, val now: Review?,
    )
    data class Controls(val advisor: String, val advisorLabel: String, val choices: List<String>, val reviewRunning: Boolean)
    data class Snapshot(
        val at: Long, val version: String, val worker: String, val advisorModel: String, val advisorMode: String,
        val forksTotal: Int, val forksToday: Int, val forkDays: List<Int>, val forks: List<Fork>,
        val consultsTotal: Int, val consultsToday: Int, val consultDays: List<Int>, val feed: List<Consult>,
        val live: List<LiveTask>, val jobs: List<JobRow>, val tasks: List<TaskRow>, val review: Review, val controls: Controls,
    )

    enum class Status(val label: String) { IDLE("Idle"), WORKING("Working"), OFFLINE("Offline") }

    const val OLD_COMPANION = "Update the companion on your computer (needs 1.15.2)"
    private const val PREFS = "agent_ops"
    private const val STALE_MS = 10 * 60_000L

    // ---------------------------------------------------------------- parsing (pure)

    private fun JSONObject?.obj(k: String): JSONObject = this?.optJSONObject(k) ?: JSONObject()
    private fun JSONObject?.arr(k: String): List<JSONObject> = (this?.optJSONArray(k) ?: JSONArray()).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
    private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k)
    private fun JSONObject.sum(vararg keys: String): Int = keys.sumOf { optInt(it) }

    private fun review(r: JSONObject, now: Review?): Review {
        val setup = r.obj("setup"); val health = r.obj("health")
        return Review(
            r.str("status"), r.str("summary"), r.optDouble("at", 0.0).takeUnless { it.isNaN() } ?: 0.0,
            setup.str("model"), setup.str("advisor"), setup.str("time"), setup.optBoolean("enabled", true),
            health.str("state"), health.str("note"), r.str("report_md"), now,
        )
    }

    fun parse(j: JSONObject, receivedAt: Long): Snapshot {
        val adv = j.obj("advisor"); val forks = j.obj("forks"); val ctl = j.obj("controls")
        val nowJ = j.obj("review_now")
        val forkMap = forks.obj("forks")
        return Snapshot(
            at = receivedAt, version = j.str("version"), worker = j.str("model"),
            advisorModel = adv.str("model"), advisorMode = adv.str("mode"),
            forksTotal = forks.optInt("total"), forksToday = forks.optInt("today"),
            forkDays = forks.arr("days").map { it.sum("sharp", "split") },
            forks = forkMap.keys().asSequence().toList().map { k -> forkMap.obj(k).let { Fork(k, it.optInt("sharp"), it.optInt("split")) } },
            consultsTotal = adv.obj("total").sum("plan", "repeat", "done"), consultsToday = adv.obj("today").sum("plan", "repeat", "done"),
            consultDays = adv.arr("days").map { it.sum("plan", "repeat", "done") },
            feed = adv.arr("recent").map { Consult(it.optDouble("at", 0.0), it.str("stage"), it.str("advice"), it.optBoolean("silent"), it.str("error"), it.optDouble("seconds", 0.0)) },
            live = j.arr("live").map { t ->
                val steps = t.arr("steps").map { Step(it.str("at"), it.str("kind"), it.str("title")) }
                LiveTask(t.str("request"), t.str("origin"), t.str("started"), t.str("job"), t.optBoolean("private"), steps, t.optInt("step_count", steps.size))
            },
            jobs = j.arr("jobs").map { JobRow(it.str("id"), it.str("kind"), it.str("title"), it.str("status"), it.str("progress"), it.str("origin"), it.optBoolean("private")) },
            tasks = j.arr("tasks").map { TaskRow(it.str("started"), it.str("request"), it.str("status"), it.str("answered_by"), it.str("tools")) },
            review = review(j.obj("nightly_review"), if (nowJ.has("at")) review(nowJ, null) else null),
            controls = Controls(ctl.str("advisor"), ctl.str("advisor_label"),
                (ctl.optJSONArray("advisor_choices") ?: JSONArray()).let { a -> (0 until a.length()).map { a.optString(it) } }, ctl.optBoolean("review_running")),
        )
    }

    fun status(s: Snapshot?, online: Boolean): Status = when {
        !online -> Status.OFFLINE
        s != null && (s.live.isNotEmpty() || s.jobs.any { it.status == "running" }) -> Status.WORKING
        else -> Status.IDLE
    }

    fun age(ms: Long): String = when {
        ms < 3_600_000L -> "${(ms / 60_000L).coerceAtLeast(1)} min ago"
        ms < 48 * 3_600_000L -> "${ms / 3_600_000L} h ago"
        else -> "${ms / 86_400_000L} days ago"
    }

    /** One line for the card, tile and widget: "Working · 1 task", "Idle · qwen3:14b", "Offline · updated 2 h ago". */
    fun summary(s: Snapshot?, online: Boolean, now: Long): String {
        val st = status(s, online)
        val base = when (st) {
            Status.OFFLINE -> "Offline"
            Status.WORKING -> s!!.live.size.coerceAtLeast(s.jobs.count { it.status == "running" }).let { "Working · $it task" + if (it == 1) "" else "s" }
            Status.IDLE -> if (s?.worker.isNullOrBlank()) "Idle" else "Idle · ${s!!.worker}"
        }
        val old = s != null && now - s.at > STALE_MS
        return when {
            s == null -> base
            st == Status.OFFLINE -> "$base · updated ${age(now - s.at)}"
            old -> "$base · updated ${age(now - s.at)}"
            else -> base
        }
    }

    /** The review in a word or two: no findings / findings / failed / missed / skipped / off / tonight. */
    fun reviewLabel(r: Review): String = when {
        !r.enabled -> "off"
        r.health == "missed" -> "missed"
        r.status == "Failed" || r.health == "failed" -> "failed"
        r.status == "Reviewed" -> if (r.summary.startsWith("No findings")) "no findings" else "findings"
        r.status == "Skipped" -> "skipped"
        else -> "tonight"
    }

    /** The widget's three lines (also what decides whether the widget and tile need redrawing). */
    fun widgetLines(s: Snapshot?, online: Boolean, now: Long): List<String> = listOf(
        summary(s, online, now),
        s?.let { "${it.forksToday} decisions · ${it.consultsToday} consults today" } ?: "—",
        "Review: " + (s?.let { reviewLabel(it.review) } ?: "—"),
    )

    fun errorFor(code: Int): String = if (code == 404) OLD_COMPANION else "HTTP $code"

    fun errorMessage(e: Throwable): String = when {
        e is SecurityException -> "The computer rejected this phone's key"
        e.message == "HTTP 404" || e.message.equals("not found", ignoreCase = true) -> errorFor(404)
        else -> e.message?.takeIf { it.isNotBlank() } ?: "Couldn't reach the computer"
    }

    // ---------------------------------------------------------------- state

    private val _snapshot = MutableStateFlow<Snapshot?>(null)
    val snapshot: StateFlow<Snapshot?> = _snapshot.asStateFlow()
    private val _error = MutableStateFlow("")
    val error: StateFlow<String> = _error.asStateFlow()

    /** The last snapshot from disk (once, at start-up), so Home has something before the first refresh. */
    fun load(c: Context) {
        if (_snapshot.value != null) return
        val p = c.getSharedPreferences(PREFS, 0)
        val raw = p.getString("last", null) ?: return
        runCatching { _snapshot.value = parse(JSONObject(raw), p.getLong("at", 0L)) }
    }

    /** Fetches a fresh snapshot; false (keeping the old one) when the computer can't be reached. */
    suspend fun refresh(app: AppContainer): Boolean {
        if (!DesktopAi.usable) return false
        return withContext(Dispatchers.IO) {
            runCatching { DesktopAi.request("GET", "/phone/agentops", null, 10_000) }.fold(
                onSuccess = { j ->
                    val at = System.currentTimeMillis()
                    val s = parse(j, at)
                    app.context.getSharedPreferences(PREFS, 0).edit().putString("last", j.toString()).putLong("at", at).apply()
                    val before = widgetLines(_snapshot.value, true, at)
                    _snapshot.value = s
                    _error.value = ""
                    if (widgetLines(s, true, at) != before) notifySurfaces(app.context)
                    true
                },
                onFailure = { _error.value = errorMessage(it); false },
            )
        }
    }

    /** Redraws the Home-screen widget and asks the Quick Settings tile to refresh (called only when what they show changes). */
    suspend fun notifySurfaces(c: Context) {
        runCatching { com.blacklab.app.widgets.AgentOpsWidget.refresh(c) }
        runCatching { com.blacklab.app.assistant.AgentOpsTile.requestUpdate(c) }
    }

    /** "Run review now": the computer reviews the commits since the last review (read-only Claude Code). */
    suspend fun runReview(): Pair<Boolean, String> = post("/phone/agentops/review", JSONObject()) { it.optBoolean("started") to it.optString("note") }

    suspend fun setAdvisor(v: String): Pair<Boolean, String> =
        post("/phone/agentops/advisor", JSONObject().put("advisor", v)) { it.optBoolean("ok") to "Advisor: ${it.optString("advisor")}" }

    private suspend fun post(path: String, body: JSONObject, read: (JSONObject) -> Pair<Boolean, String>): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            if (!DesktopAi.usable) return@withContext false to "The computer isn't reachable"
            runCatching { read(DesktopAi.request("POST", path, body, 15_000)) }.getOrElse { false to errorMessage(it) }
        }
}

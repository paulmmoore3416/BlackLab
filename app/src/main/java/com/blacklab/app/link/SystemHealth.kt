package com.blacklab.app.link

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The computer's nightly system check (desktop/autopilot, 03:00 → restart at 04:00 → after-restart check).
 *
 * The report arrives as a "health" desktop job ([com.blacklab.app.assistant.DesktopJobs]); it is saved as a
 * document under "System Health" and kept here until it has been shown once. BlackLab shows it the first time
 * it opens after the report lands (AppRoot › SystemHealthSheet).
 */
object SystemHealth {
    enum class Level { OK, WARN, FAIL }

    data class Issue(val area: String, val level: Level, val title: String, val detail: String, val fix: String)

    data class Report(
        val id: String,
        val date: String,
        val title: String,
        val level: Level,
        val summary: String,
        val issues: List<Issue>,
        val fixed: List<String>,
        val checks: Int,
        val docId: Long,
        val seen: Boolean,
    )

    private const val PREFS = "system_health"
    private val _latest = MutableStateFlow<Report?>(null)
    /** The newest report, seen or not (null until the first one arrives). */
    val latest: StateFlow<Report?> = _latest.asStateFlow()
    private var loaded = false

    fun level(s: String?) = when (s) { "fail" -> Level.FAIL; "warn" -> Level.WARN; else -> Level.OK }

    /** Pure: the desktop job's result → a report. */
    fun parse(r: JSONObject, docId: Long = 0, seen: Boolean = false): Report {
        val issues = r.optJSONArray("issues") ?: JSONArray()
        val fixed = r.optJSONArray("fixed") ?: JSONArray()
        return Report(
            id = r.optString("id"),
            date = r.optString("date"),
            title = r.optString("title").ifBlank { "System check" },
            level = level(r.optString("status")),
            summary = r.optString("summary"),
            issues = (0 until issues.length()).mapNotNull { issues.optJSONObject(it) }.map {
                Issue(it.optString("area"), level(it.optString("status")), it.optString("title"), it.optString("detail"), it.optString("fix"))
            }.sortedByDescending { it.level.ordinal },
            fixed = (0 until fixed.length()).map { fixed.optString(it) }.filter { it.isNotBlank() },
            checks = r.optJSONObject("counts")?.optInt("checks") ?: 0,
            docId = docId,
            seen = seen,
        )
    }

    /**
     * Pure: should a newly arrived copy be shown again? A new night always is. The same night's after-restart
     * copy only comes back if it is worse than what was already shown.
     */
    fun showAgain(old: Report?, new: Report): Boolean =
        old == null || old.id != new.id || !old.seen || new.level.ordinal > old.level.ordinal

    private fun toJson(r: Report, raw: JSONObject) = JSONObject(raw.toString()).put("_doc", r.docId).put("_seen", r.seen)

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        val s = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("latest", null) ?: return
        runCatching { JSONObject(s) }.getOrNull()?.let { _latest.value = parse(it, it.optLong("_doc"), it.optBoolean("_seen")) }
    }

    /** A report from the computer. Returns true when it should be shown (unseen). */
    fun receive(c: Context, r: JSONObject, docId: Long): Boolean {
        load(c)
        val old = _latest.value
        val incoming = parse(r, docId)
        val show = showAgain(old, incoming)
        val rep = incoming.copy(seen = !show)
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("latest", toJson(rep, r).toString()).apply()
        _latest.value = rep
        return show
    }

    fun markSeen(c: Context) {
        val cur = _latest.value ?: return
        if (cur.seen) return
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = p.getString("latest", null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        p.edit().putString("latest", raw.put("_seen", true).toString()).apply()
        _latest.value = cur.copy(seen = true)
    }
}

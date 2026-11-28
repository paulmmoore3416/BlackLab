package com.blacklab.app.assistant

import android.content.Context
import com.blacklab.app.AppContainer
import org.json.JSONArray
import org.json.JSONObject

/**
 * One memory for every BlackLab AI (5.20 + companion 1.16).
 *
 * The computer's memory.md (the facts it has learned or been told to remember) and its cross-device thread (the last
 * few exchanges on the computer, in Obsidian or here) come from GET /phone/memory on every link check and are kept
 * here, so they still work when the computer is away. [promptBlock] puts them into the prompts of the phone's own
 * models (Gemini Nano, Gemini, Claude), so "remember that …" said anywhere is known everywhere, and a conversation
 * started on the computer can be continued from the phone.
 *
 * The other direction already exists: every answer a phone model gives goes to the computer (Assistant.saveTurn →
 * /phone/vault/chat), which now also adds it to its chat history and thread.
 *
 * Behavioral Health never reaches this path, and the computer drops clinical lines before sending.
 */
object SharedMemory {
    data class Turn(val at: Long, val surface: String, val you: String, val ai: String)
    data class State(val rev: Long = 0, val facts: List<String> = emptyList(), val recent: List<Turn> = emptyList(), val fetchedAt: Long = 0)

    private const val PREFS = "shared_memory"
    /** A turn from another device is offered to the phone's models for this long (the computer uses the same window). */
    const val HANDOFF_MS = 10 * 60_000L

    @Volatile var state = State()
        private set
    @Volatile private var loaded = false

    fun parse(j: JSONObject, now: Long): State {
        val facts = j.optJSONArray("facts") ?: JSONArray()
        val recent = j.optJSONArray("recent") ?: JSONArray()
        return State(
            rev = j.optLong("rev"),
            facts = (0 until facts.length()).map { facts.optString(it).trim() }.filter { it.isNotBlank() },
            recent = (0 until recent.length()).mapNotNull { recent.optJSONObject(it) }.map {
                Turn(it.optLong("at"), it.optString("surface"), it.optString("you"), it.optString("ai"))
            }.filter { it.you.isNotBlank() },
            fetchedAt = now,
        )
    }

    fun toJson(s: State): JSONObject = JSONObject().put("rev", s.rev).put("fetched", s.fetchedAt)
        .put("facts", JSONArray(s.facts))
        .put("recent", JSONArray().apply { s.recent.forEach { put(JSONObject().put("at", it.at).put("surface", it.surface).put("you", it.you).put("ai", it.ai)) } })

    /**
     * The block a phone prompt gets: the memory facts (newest last, trimmed to [factChars]) and, when [phoneIdle] (no
     * exchange on the phone in the hand-off window), the other devices' turns from that window so "and the second one?"
     * works after switching devices.
     */
    fun promptBlock(s: State, now: Long, factChars: Int, includeRecent: Boolean, phoneIdle: Boolean): String = buildString {
        if (s.facts.isNotEmpty()) {
            var facts = s.facts.joinToString("\n") { "- $it" }
            if (facts.length > factChars) facts = facts.takeLast(factChars).substringAfter('\n')
            if (facts.isNotBlank()) append("What you know about the user (shared memory from all their devices; may be out of date):\n$facts\n")
        }
        if (includeRecent && phoneIdle) {
            val other = s.recent.filter { now - it.at in 0..HANDOFF_MS && !it.surface.equals("Phone", true) }.takeLast(3)
            if (other.isNotEmpty()) append("The user was just talking to you on another device:\n" +
                other.joinToString("\n") { "[${it.surface}] User: ${it.you.take(600)}\n[${it.surface}] You: ${it.ai.take(900)}" } + "\n")
        }
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        runCatching { prefs(c).getString("state", null)?.let { state = parse(JSONObject(it), JSONObject(it).optLong("fetched")) } }
    }

    /** Called from DesktopJobs.sync while the computer is reachable. */
    fun refresh(app: AppContainer) {
        load(app.context)
        val j = DesktopAi.request("GET", "/phone/memory", null, 15_000)
        if (!j.optBoolean("ok")) return
        state = parse(j, System.currentTimeMillis())
        prefs(app.context).edit().putString("state", toJson(state).toString()).apply()
    }

    /** "remember that …" while the computer is away: shown right away on the phone, saved to memory.md when it's back. */
    fun rememberLocally(app: AppContainer, fact: String) {
        load(app.context)
        val f = fact.trim().removeSuffix(".")
        if (f.isBlank() || state.facts.any { it.equals(f, true) }) return
        state = state.copy(facts = state.facts + f)
        prefs(app.context).edit().putString("state", toJson(state).toString()).apply()
        DesktopJobs.queueCall(app, "/phone/memory", JSONObject().put("add", JSONArray().put(f)), "Remember: ${f.take(40)}")
    }
}

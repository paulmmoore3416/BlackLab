package com.blacklab.app.link

import android.content.Context
import com.blacklab.app.AppContainer
import com.blacklab.app.assistant.DesktopAi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Projects from the Project Board in Obsidian (5.17): the computer reads every project note's `status:` and the board's
 * lanes (02 Projects/🗂️ Project Board.md) and serves them at /phone/projects. The Home card shows the active and
 * blocked ones; the last list is kept so the card still shows while the computer is away.
 */
object Projects {
    data class Project(val name: String, val status: String, val due: String, val release: String)

    private const val PREFS = "projects"

    fun parse(j: JSONObject): List<Project> {
        val a = j.optJSONArray("projects") ?: JSONArray()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            Project(it.optString("name"), it.optString("status"), it.optString("due"), it.optString("release"))
        }.filter { it.name.isNotBlank() }
    }

    fun cached(c: Context): List<Project> = runCatching {
        parse(JSONObject(c.getSharedPreferences(PREFS, 0).getString("last", null) ?: "{}"))
    }.getOrDefault(emptyList())

    /** The latest list from the computer, or null when it isn't reachable. */
    suspend fun refresh(app: AppContainer): List<Project>? {
        if (!DesktopAi.usable) return null
        return withContext(Dispatchers.IO) {
            runCatching { DesktopAi.request("GET", "/phone/projects", null, 15_000) }.getOrNull()?.let { j ->
                app.context.getSharedPreferences(PREFS, 0).edit().putString("last", j.toString()).apply()
                parse(j)
            }
        }
    }
}

package com.blacklab.app.core

import org.json.JSONObject

/**
 * The app's sections and where each one lives: in the bottom bar (2–5 tabs), in the side menu, or hidden.
 * A section is never listed twice — the side menu leaves out whatever is already a tab — and every section can
 * be renamed and reordered. Home can't be hidden (it's where Back lands), so it is always a tab or a menu item.
 *
 * Saved as `nav_tabs` (comma list, in tab order), `nav_order` (comma list of the side-menu order), `nav_hidden`
 * (comma list) and `nav_names` (JSON object route → your name). New sections in a later version slot in after
 * their default neighbour, the same way [HomeLayout] handles new cards.
 */
object NavLayout {
    data class Section(val route: String, val label: String, val group: String, val help: String)

    val sections: List<Section> = listOf(
        Section("home", "Home", "Everyday", "Your dashboard."),
        Section("assistant", "Assistant", "Everyday", "Ask, brief me, desktop AI."),
        Section("tasks", "To-Do", "Everyday", "Tasks, lists and My Day."),
        Section("schedule", "Schedule", "Everyday", "Calendar and agenda."),
        Section("notes", "Notes", "Everyday", "Keep-style notes and checklists."),
        Section("office", "Office", "Work", "PowerPoint, Excel and Word files, Microsoft 365 and Teams."),
        Section("documents", "Documents", "Work", "Write and edit documents."),
        Section("facility", "Facility", "Work", "Assets, inspections, PMs and work orders."),
        Section("bh", "Behavioral Health", "Work", "Shift hub, rounds, peer support."),
        Section("timeline", "Timeline & handoff", "Work", "Your day and the ops handoff."),
        Section("signatures", "Signatures", "Work", "Sign documents."),
        Section("library", "Library", "Files", "Every file in your BlackLab folder."),
        Section("create", "Create & Edit", "Files", "Photo, video, audio and PDF tools."),
        Section("brainstorm", "Brainstorm", "Files", "OneNote-style notebooks."),
        Section("education", "Learn", "Growth", "Courses, assignments and certifications."),
        Section("knowledge", "Knowledge & Talk", "Growth", "Knowledge bases and conversations."),
        Section("summarize", "Summarize", "Growth", "Summaries of anything."),
        Section("journal", "Journal", "Growth", "Reflections and moods."),
        Section("camera", "Camera", "Media", "Pro camera and scanner."),
        Section("recorder", "Recorder", "Media", "Audio recorder with transcripts."),
        Section("dictation", "Dictation", "Media", "Long-form dictation."),
        Section("music", "Music", "Media", "Music player."),
    )

    val DEFAULT_TABS = listOf("home", "tasks", "schedule", "office", "library")
    const val MIN_TABS = 2
    const val MAX_TABS = 5
    const val TABS_KEY = "nav_tabs"
    const val ORDER_KEY = "nav_order"
    const val HIDDEN_KEY = "nav_hidden"
    const val NAMES_KEY = "nav_names"

    private val routes = sections.map { it.route }
    fun section(route: String): Section? = sections.firstOrNull { it.route == route }

    data class Layout(val tabs: List<String>, val order: List<String>, val hidden: Set<String>, val names: Map<String, String>) {
        /** Side-menu sections, in order: everything that isn't a tab or hidden. */
        val menu: List<String> get() = order.filter { it !in tabs && it !in hidden }
        fun label(route: String): String = names[route]?.takeIf { it.isNotBlank() } ?: section(route)?.label ?: route
        fun isTab(route: String) = route in tabs
    }

    fun parse(tabs: String?, order: String?, hidden: String?, names: String?): Layout {
        val h = list(hidden).filter { it != "home" }.toSet()
        val t = list(tabs).filter { it !in h }.take(MAX_TABS).ifEmpty { DEFAULT_TABS }.let { if (it.size < MIN_TABS) DEFAULT_TABS else it }
        val n = runCatching { JSONObject(names ?: "{}") }.getOrDefault(JSONObject()).let { o ->
            o.keys().asSequence().filter { it in routes }.associateWith { o.optString(it).trim().take(32) }.filterValues { it.isNotEmpty() }
        }
        return Layout(t, fullOrder(order), h, n)
    }

    /** Saved order (known routes only), then any missing section after its default neighbour. */
    fun fullOrder(saved: String?): List<String> {
        val out = list(saved).toMutableList()
        routes.forEachIndexed { i, id ->
            if (id in out) return@forEachIndexed
            val before = routes.subList(0, i).lastOrNull { it in out }
            out.add(if (before == null) 0 else out.indexOf(before) + 1, id)
        }
        return out
    }

    private fun list(s: String?): List<String> = s.orEmpty().split(',').map { it.trim() }.filter { it in routes }.distinct()

    fun serialize(l: List<String>) = l.joinToString(",")
    fun serializeNames(m: Map<String, String>): String = JSONObject(m.filterValues { it.isNotBlank() }).toString()

    /** Moves [route] one step within [l] (-1 up, +1 down). */
    fun move(l: List<String>, route: String, dir: Int): List<String> {
        val m = l.toMutableList(); val i = m.indexOf(route); val j = i + dir
        if (i < 0 || j !in m.indices) return l
        m.add(j, m.removeAt(i)); return m
    }

    /** Adds [route] as a tab (at the end) or removes it; keeps 2–5 tabs. Returns null when the change isn't allowed. */
    fun toggleTab(tabs: List<String>, route: String): List<String>? = when {
        route in tabs -> if (tabs.size <= MIN_TABS) null else tabs - route
        tabs.size >= MAX_TABS -> null
        else -> tabs + route
    }

    /** Hides or shows [route]; Home can't be hidden, and a hidden section stops being a tab (if enough remain). */
    fun toggleHidden(l: Layout, route: String): Layout? {
        if (route == "home") return null
        if (route in l.hidden) return l.copy(hidden = l.hidden - route)
        val tabs = if (route in l.tabs) (l.tabs - route).takeIf { it.size >= MIN_TABS } ?: return null else l.tabs
        return l.copy(tabs = tabs, hidden = l.hidden + route)
    }
}

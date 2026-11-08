package com.blacklab.app.core

/**
 * The cards on the Home dashboard, in their default order. Each card can be shown or hidden
 * (`home_show_<id>`, on by default) and the order is saved as a comma list in `home_order`.
 * Cards added in a later version slot in after their default neighbour, so a saved order never
 * hides something new.
 */
object HomeLayout {
    data class Card(val id: String, val label: String, val help: String)

    val cards: List<Card> = listOf(
        Card("ask", "Ask bar", "Type or talk to the assistant, or get a spoken briefing."),
        Card("tools", "Smart tools", "Summarize, Talk and Garage shortcuts."),
        Card("status", "Recording & dictation", "Shows only while a recording or dictation is running."),
        Card("weather", "Weather", "Current conditions and facility weather alerts."),
        Card("bh", "Behavioral Health", "On shift and rounds due. Counts only, never initials or rooms."),
        Card("facility", "Facility", "Inspections and PMs overdue or due soon. Shows only when something is."),
        Card("actions", "Quick actions", "Photo, video, dictate, record, scan, document, journal, task, event and focus."),
        Card("feed", "Since you last looked", "What changed since you last opened Home."),
        Card("notes", "Notes", "Your pinned and latest notes, with a Take a note bar."),
        Card("today", "Today", "What's on your calendar today."),
        Card("focus", "Focus tasks", "Tasks due today or added to My Day."),
        Card("learning", "Learning", "Next class, assignments due and certification dates."),
        Card("documents", "Recent documents", "Documents you edited recently."),
        Card("reflect", "Journal prompt", "Today's reflection prompt."),
    )

    const val ORDER_KEY = "home_order"
    fun showKey(id: String) = "home_show_$id"

    private val ids = cards.map { it.id }
    fun card(id: String): Card? = cards.firstOrNull { it.id == id }

    /** The full order: saved ids that still exist, then any missing card after its default neighbour. */
    fun order(saved: String?): List<String> {
        val out = saved.orEmpty().split(',').map { it.trim() }.filter { it in ids }.distinct().toMutableList()
        ids.forEachIndexed { i, id ->
            if (id in out) return@forEachIndexed
            val before = ids.subList(0, i).lastOrNull { it in out }
            out.add(if (before == null) 0 else out.indexOf(before) + 1, id)
        }
        return out
    }

    fun serialize(order: List<String>): String = order.joinToString(",")

    /** Moves [id] one step up (-1) or down (+1); returns the new order. */
    fun move(order: List<String>, id: String, dir: Int): List<String> {
        val l = order.toMutableList(); val i = l.indexOf(id); val j = i + dir
        if (i < 0 || j !in l.indices) return order
        l.add(j, l.removeAt(i))
        return l
    }
}

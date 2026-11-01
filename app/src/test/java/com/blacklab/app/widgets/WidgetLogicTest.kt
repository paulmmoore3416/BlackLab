package com.blacklab.app.widgets

import com.blacklab.app.brainstorm.NoteItem
import com.blacklab.app.data.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetLogicTest {
    private val today = 20_000L
    private val start = today * 86_400_000L

    private fun t(id: Long, title: String, due: Long? = null, myDay: Long? = null, done: Boolean = false, doneAt: Long? = null, minute: Int? = null, profile: String = "PERSONAL", prio: Int = 0) =
        TaskEntity(id = id, profile = profile, title = title, dueDay = due, myDayDay = myDay, completed = done, completedAt = doneAt, dueMinute = minute, priority = prio)

    @Test fun todayListShowsDueOverdueAndMyDayThenFinishedToday() {
        val tasks = listOf(
            t(1, "later", due = today + 1),
            t(2, "today 3pm", due = today, minute = 15 * 60),
            t(3, "overdue", due = today - 2),
            t(4, "my day", myDay = today),
            t(5, "today 9am", due = today, minute = 9 * 60),
            t(6, "done today", due = today, done = true, doneAt = start + 3_600_000),
            t(7, "done yesterday", due = today - 1, done = true, doneAt = start - 3_600_000),
            t(8, "other account", due = today, profile = "PROFESSIONAL"),
            t(9, "no date", due = null),
        )
        val ids = TodayLogic.tasks(tasks, "PERSONAL", today, start).map { it.id }
        assertEquals(listOf(3L, 5L, 2L, 4L, 6L), ids)
    }

    @Test fun checklistPutsOpenItemsFirstAndKeepsIndexes() {
        val items = listOf(NoteItem("Eggs", true), NoteItem("Coffee"), NoteItem(""), NoteItem("Spinach"), NoteItem("Milk", true))
        val rows = TodayLogic.checklist(items)
        assertEquals(listOf(1, 3, 0, 4), rows.map { it.first })
        assertEquals("Coffee", rows.first().second.text)
    }

    @Test fun countLabels() {
        assertEquals("1 task", TodayLogic.countLabel(1, "task"))
        assertEquals("3 tasks", TodayLogic.countLabel(3, "task"))
        assertEquals("0 things", TodayLogic.countLabel(0, "thing", "things"))
    }
}

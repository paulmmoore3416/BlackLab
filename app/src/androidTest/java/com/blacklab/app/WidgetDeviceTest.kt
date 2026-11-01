package com.blacklab.app

import androidx.glance.action.actionParametersOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blacklab.app.brainstorm.NoteItem
import com.blacklab.app.brainstorm.NoteOps
import com.blacklab.app.core.Time
import com.blacklab.app.data.NoteEntity
import com.blacklab.app.data.TaskEntity
import com.blacklab.app.data.TaskOps
import com.blacklab.app.widgets.ToggleNoteItemAction
import com.blacklab.app.widgets.ToggleTaskAction
import com.blacklab.app.widgets.Widgets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The widgets read the right data and their tick boxes write back to tasks and checklists. */
@RunWith(AndroidJUnit4::class)
class WidgetDeviceTest {

    private val fakeGlanceId = object : androidx.glance.GlanceId {}

    @Test fun todayWidgetListsAndTicksTasks() = runBlocking {
        val app = DeviceTest.app
        val profile = app.settings.current.activeProfile.name
        val title = DeviceTest.tag()
        val id = TaskOps.save(app, TaskEntity(profile = profile, title = title, dueDay = Time.today().toEpochDay()))
        try {
            val row = Widgets.today(DeviceTest.context, onLockScreen = false).tasks.first { it.title == title }
            assertFalse(row.done)
            ToggleTaskAction().onAction(DeviceTest.context, fakeGlanceId, actionParametersOf(ToggleTaskAction.TaskId to id))
            assertTrue(app.db.tasks().get(id)!!.completed)
            // Finished today: still listed (so it can be un-ticked), now checked
            assertTrue(Widgets.today(DeviceTest.context, false).tasks.first { it.title == title }.done)
            // Lock-screen hosts never get details
            assertTrue(Widgets.today(DeviceTest.context, onLockScreen = true).hideDetails)
        } finally { app.db.tasks().get(id)?.let { TaskOps.delete(app, it) } }
    }

    @Test fun checklistWidgetTicksTheRightItem() = runBlocking {
        val app = DeviceTest.app
        val id = NoteOps.save(app, NoteEntity(profile = app.settings.current.activeProfile.name, title = DeviceTest.tag(), checklist = true,
            items = NoteOps.itemsJson(listOf(NoteItem("Coffee"), NoteItem("Eggs"), NoteItem("Spinach")))))
        try {
            val m = Widgets.checklist(DeviceTest.context, id, false)
            assertEquals(listOf("Coffee", "Eggs", "Spinach"), m.rows.map { it.second.text })
            // The list was reordered in the app since the widget drew: the text still finds the right item
            val n = app.db.notes().get(id)!!
            NoteOps.save(app, n.copy(items = NoteOps.itemsJson(listOf(NoteItem("Spinach"), NoteItem("Coffee"), NoteItem("Eggs")))))
            ToggleNoteItemAction().onAction(DeviceTest.context, fakeGlanceId, actionParametersOf(
                ToggleNoteItemAction.NoteId to id, ToggleNoteItemAction.Index to 1, ToggleNoteItemAction.Text to "Eggs"))
            val items = NoteOps.items(app.db.notes().get(id)!!)
            assertEquals(listOf(false, false, true), items.map { it.done })
            assertEquals(1, Widgets.checklist(DeviceTest.context, id, false).doneCount)
        } finally { app.db.notes().get(id)?.let { NoteOps.deleteForever(app, it) } }
    }
}

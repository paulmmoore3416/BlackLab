package com.blacklab.app.ui.notes

import com.blacklab.app.core.Settings
import com.blacklab.app.data.NoteEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardNotesTest {
    private val notes = listOf(
        NoteEntity(id = 1, profile = "P", title = "old pinned", pinned = true, updatedAt = 10),
        NoteEntity(id = 2, profile = "P", title = "newest", updatedAt = 50),
        NoteEntity(id = 3, profile = "P", title = "archived", archived = true, updatedAt = 60),
        NoteEntity(id = 4, profile = "P", title = "trashed", trashedAt = 5, updatedAt = 70),
        NoteEntity(id = 5, profile = "P", title = "middle", updatedAt = 30),
        NoteEntity(id = 6, profile = "P", title = "new pinned", pinned = true, updatedAt = 40),
    )

    @Test fun pinnedFirstThenLatest() {
        assertEquals(listOf(1L, 6L, 2L, 5L), dashboardNotes(notes, Settings()).map { it.id })
        assertEquals(listOf(1L, 6L, 2L), dashboardNotes(notes, Settings(notesHomeCount = 3)).map { it.id })
    }

    @Test fun pinnedOnlyAndLatestOnly() {
        assertEquals(listOf(1L, 6L), dashboardNotes(notes, Settings(notesHomeMode = "PINNED")).map { it.id })
        assertEquals(listOf(2L, 6L, 5L, 1L), dashboardNotes(notes, Settings(notesHomeMode = "RECENT")).map { it.id })
    }

    @Test fun neverShowsArchivedOrTrashed() {
        val ids = dashboardNotes(notes, Settings(notesHomeCount = 12, notesHomeMode = "RECENT")).map { it.id }
        assertEquals(false, 3L in ids || 4L in ids)
    }
}

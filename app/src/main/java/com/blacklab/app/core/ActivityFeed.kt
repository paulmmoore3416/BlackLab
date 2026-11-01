package com.blacklab.app.core

import com.blacklab.app.data.CommentEntity
import com.blacklab.app.data.DocumentEntity
import com.blacklab.app.data.EventEntity
import com.blacklab.app.data.NoteEntity
import com.blacklab.app.data.RecordingEntity
import com.blacklab.app.data.TaskEntity

/**
 * "Since you last looked": what happened between two moments, built from timestamps BlackLab
 * already keeps. Things finished, added (on the phone, from a widget, the desktop or a Live Share
 * guest), recordings that finished transcribing and reminders that went off.
 */
object ActivityFeed {
    enum class Kind { TASK_DONE, TASK_NEW, EVENT_NEW, REMINDER, RECORDING, DOCUMENT, NOTE, COMMENT }

    data class Item(val kind: Kind, val time: Long, val title: String, val detail: String, val route: String)

    /** Window start: the last look, but never more than [maxBackMs] ago (first run: the last 12 hours). */
    fun since(lastSeen: Long, now: Long, maxBackMs: Long = 3 * 86_400_000L): Long =
        if (lastSeen <= 0) now - 12 * 3_600_000L else maxOf(lastSeen, now - maxBackMs)

    fun build(
        since: Long, now: Long,
        tasks: List<TaskEntity> = emptyList(), events: List<EventEntity> = emptyList(), docs: List<DocumentEntity> = emptyList(),
        recordings: List<RecordingEntity> = emptyList(), notes: List<NoteEntity> = emptyList(), comments: List<CommentEntity> = emptyList(),
        timeLabel: (Long) -> String = { "" },
        limit: Int = 8,
    ): List<Item> {
        fun inWindow(t: Long?) = t != null && t > since && t <= now
        val out = ArrayList<Item>()
        tasks.forEach { t ->
            if (t.completed && inWindow(t.completedAt)) out += Item(Kind.TASK_DONE, t.completedAt!!, t.title, "Completed", "task/${t.id}")
            else if (!t.completed && inWindow(t.createdAt)) out += Item(Kind.TASK_NEW, t.createdAt, t.title, "New task", "task/${t.id}")
            if (!t.completed && inWindow(t.reminderAt) && !inWindow(t.createdAt)) out += Item(Kind.REMINDER, t.reminderAt!!, t.title, "Reminder went off", "task/${t.id}")
        }
        events.forEach { e ->
            if (inWindow(e.createdAt)) out += Item(Kind.EVENT_NEW, e.createdAt, e.title.ifBlank { "Event" }, "New event · " + timeLabel(e.start), "event/${e.id}")
        }
        recordings.forEach { r ->
            if (inWindow(r.createdAt)) out += Item(Kind.RECORDING, r.createdAt, r.title.ifBlank { "Recording" },
                if (r.transcript.isNotBlank()) "Recording saved · transcript ready" else "Recording saved", "recording/${r.id}")
        }
        docs.forEach { d ->
            if (inWindow(d.updatedAt)) out += Item(Kind.DOCUMENT, d.updatedAt, d.title.ifBlank { "Untitled" }, if (inWindow(d.createdAt)) "New document" else "Document edited", "doc/${d.id}")
        }
        notes.filter { it.trashedAt == null && !it.archived }.forEach { n ->
            val title = n.title.ifBlank { n.body.lineSequence().firstOrNull().orEmpty().take(60) }.ifBlank { "Note" }
            when {
                inWindow(n.reminderAt) -> out += Item(Kind.REMINDER, n.reminderAt!!, title, "Reminder went off", "note/${n.id}")
                inWindow(n.createdAt) -> out += Item(Kind.NOTE, n.createdAt, title, "New note", "note/${n.id}")
            }
        }
        comments.filter { it.guest && inWindow(it.createdAt) }.forEach { c ->
            val route = c.noteId?.let { "note/$it" } ?: c.pageId?.let { "page/$it" } ?: "brainstorm"
            out += Item(Kind.COMMENT, c.createdAt, "${c.author}: ${c.text.take(80)}", "Live Share comment", route)
        }
        return out.sortedByDescending { it.time }.distinctBy { it.kind to it.route }.take(limit)
    }
}

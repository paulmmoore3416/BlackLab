package com.blacklab.app.summarize

import android.net.Uri
import com.blacklab.app.AppContainer
import com.blacklab.app.brainstorm.NoteOps
import com.blacklab.app.core.Folder
import com.blacklab.app.core.Profile
import com.blacklab.app.core.Time
import com.blacklab.app.data.CalendarLogic
import com.blacklab.app.data.ContentStore
import com.blacklab.app.dictation.DictationStore
import com.blacklab.app.files.FileKind
import com.blacklab.app.files.FileKinds
import com.blacklab.app.files.FileOps
import com.blacklab.app.files.PdfTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/** Everything that can be picked for a summary, and how to read its text. */
object SummarySources {

    enum class Kind(val label: String) {
        DOCUMENT("Document"), NOTE("Note"), PAGE("Notebook page"), JOURNAL("Journal"), EVENT("Event"),
        TASK("Task"), RECORDING("Recording"), DICTATION("Dictation"), FILE("File"),
    }

    /** One selectable row. [key] is stable across reloads ("doc:12", "file:Personal/Scans/x.pdf"). */
    data class Item(val key: String, val kind: Kind, val title: String, val subtitle: String, val time: Long, val route: String?)

    private val readableFiles = setOf(FileKind.PDF, FileKind.TEXT, FileKind.CODE, FileKind.MARKDOWN, FileKind.HTML, FileKind.WORD, FileKind.RTF, FileKind.ODT, FileKind.SLIDES, FileKind.EPUB, FileKind.SHEET, FileKind.CALENDAR)

    suspend fun catalog(app: AppContainer, profile: Profile): List<Item> = withContext(Dispatchers.IO) {
        val p = profile.name
        val out = ArrayList<Item>()
        app.db.documents().all().filter { it.profile == p }.forEach { d ->
            out += Item("doc:${d.id}", Kind.DOCUMENT, d.title.ifBlank { "Untitled" }, listOf(d.folder, "${d.wordCount} words").filter { it.isNotBlank() }.joinToString(" · "), d.updatedAt, "doc/${d.id}")
        }
        app.db.notes().allFor(p).filter { it.trashedAt == null }.forEach { n ->
            val text = NoteOps.plainText(n)
            out += Item("note:${n.id}", Kind.NOTE, n.title.ifBlank { text.lineSequence().firstOrNull()?.take(60) ?: "Note" }, if (n.checklist) "Checklist" else text.take(60).replace('\n', ' '), n.updatedAt, "note/${n.id}")
        }
        app.db.brainstorm().allPages().filter { it.profile == p && it.deletedAt == null }.forEach { pg ->
            out += Item("page:${pg.id}", Kind.PAGE, pg.title.ifBlank { "Untitled page" }, pg.preview.take(60), pg.updatedAt, "page/${pg.id}")
        }
        app.db.journal().allFor(p).forEach { j ->
            val day = LocalDate.ofEpochDay(j.entryDay)
            out += Item("journal:${j.id}", Kind.JOURNAL, j.title.ifBlank { "Journal · ${Time.date(day)}" }, j.preview.take(60), Time.millis(day, 12 * 60), "journal/${j.id}")
        }
        app.db.events().all().filter { it.profile == p }.forEach { e ->
            out += Item("event:${e.id}", Kind.EVENT, e.title, listOf(Time.dateTime(e.start), e.location).filter { it.isNotBlank() }.joinToString(" · "), e.start, "event/${e.id}")
        }
        app.db.tasks().all().filter { it.profile == p }.forEach { t ->
            val due = t.dueDay?.let { "due " + Time.relativeDay(LocalDate.ofEpochDay(it)).lowercase() }
            out += Item("task:${t.id}", Kind.TASK, t.title, listOfNotNull(if (t.completed) "Done" else "Open", due).joinToString(" · "), t.updatedAt, "task/${t.id}")
        }
        app.db.recordings().allFor(p).forEach { r ->
            out += Item("rec:${r.id}", Kind.RECORDING, r.title, if (r.transcript.isBlank()) "No transcript" else "${ContentStore.wordCount(r.transcript)} words transcribed", r.createdAt, "recording/${r.id}")
        }
        DictationStore.list(app.storage.dir(profile, Folder.DICTATION)).forEach { (folder, m) ->
            val rel = app.storage.relative(folder)
            out += Item("dict:$rel", Kind.DICTATION, m.title, "${m.wordCount} words", m.startedAt, "dictation/session?path=" + Uri.encode(rel))
        }
        // Loose files anywhere in the account folder (scans, imports, exports, downloads you saved)
        val skip = setOf(Folder.DOCUMENTS, Folder.JOURNAL, Folder.BRAINSTORM, Folder.DICTATION).map { app.storage.dir(profile, it).canonicalPath }
        app.storage.profileDir(profile).walkTopDown().maxDepth(5)
            .onEnter { d -> d.name.startsWith(".").not() && skip.none { d.canonicalPath == it } }
            .filter { it.isFile && !it.name.startsWith(".") && FileKinds.of(it) in readableFiles && it.length() in 1..60_000_000 }
            .sortedByDescending { it.lastModified() }.take(400)
            .forEach { f ->
                val rel = app.storage.relative(f)
                out += Item("file:$rel", Kind.FILE, f.name, "${FileKinds.of(f).label} · ${f.parentFile?.name ?: ""}", f.lastModified(), "view?path=" + Uri.encode(rel))
            }
        out.sortedByDescending { it.time }
    }

    /** Reads the text of one item for summarizing (null if it no longer exists). */
    suspend fun load(app: AppContainer, item: Item): Summarizer.Source? = withContext(Dispatchers.IO) {
        val id = item.key.substringAfter(':')
        runCatching {
            when (item.kind) {
                Kind.DOCUMENT -> app.db.documents().all().firstOrNull { it.id == id.toLong() }?.let { d ->
                    Summarizer.Source("Document", d.title, ContentStore.plainText(app.content.readBody(d.filePath)).ifBlank { d.preview }, d.updatedAt, item.route)
                }
                Kind.NOTE -> app.db.notes().all().firstOrNull { it.id == id.toLong() }?.let { n -> Summarizer.Source("Note", item.title, NoteOps.plainText(n), n.updatedAt, item.route) }
                Kind.PAGE -> app.db.brainstorm().allPages().firstOrNull { it.id == id.toLong() }?.let { pg ->
                    Summarizer.Source("Notebook page", item.title, pg.searchText.ifBlank { pg.preview.ifBlank { "(This page is in a locked section.)" } }, pg.updatedAt, item.route)
                }
                Kind.JOURNAL -> app.db.journal().allFor(app.settings.current.activeProfile.name).plus(app.db.journal().allFor(app.settings.current.activeProfile.other.name))
                    .firstOrNull { it.id == id.toLong() }?.let { j -> Summarizer.Source("Journal", item.title, ContentStore.plainText(app.content.readBody(j.filePath)), item.time, item.route) }
                Kind.EVENT -> app.db.events().all().firstOrNull { it.id == id.toLong() }?.let { e ->
                    val text = listOf(
                        "${e.title} on ${Time.dateTime(e.start)}" + (if (e.location.isNotBlank()) " at ${e.location}." else "."),
                        e.notes, if (e.attendees.isNotBlank()) "Attendees: ${e.attendees}." else "", if (e.url.isNotBlank()) "Link: ${e.url}" else "",
                    ).filter { it.isNotBlank() }.joinToString("\n")
                    Summarizer.Source("Event", e.title, text, e.start, item.route, due = e.start)
                }
                Kind.TASK -> app.db.tasks().all().firstOrNull { it.id == id.toLong() }?.let { t ->
                    val due = t.dueDay?.let { Time.millis(LocalDate.ofEpochDay(it), t.dueMinute ?: (9 * 60)) }
                    val text = listOf(
                        "Task: ${t.title}" + (if (t.completed) " (done)." else " (open)."),
                        due?.let { "Due ${Time.dateTime(it)}." } ?: "", if (t.priority >= 3) "High priority." else "", t.notes,
                    ).filter { it.isNotBlank() }.joinToString(" ")
                    Summarizer.Source("Task", t.title, text, t.updatedAt, item.route, due = due)
                }
                Kind.RECORDING -> app.db.recordings().allFor(app.settings.current.activeProfile.name).plus(app.db.recordings().allFor(app.settings.current.activeProfile.other.name))
                    .firstOrNull { it.id == id.toLong() }?.let { r -> Summarizer.Source("Recording", r.title, (r.transcript + "\n" + r.notes).trim(), r.createdAt, item.route) }
                Kind.DICTATION -> DictationStore.read(app.storage.resolve(id))?.let { m -> Summarizer.Source("Dictation", m.title, DictationStore.plainText(m, false), m.startedAt, item.route) }
                Kind.FILE -> {
                    val f = app.storage.resolve(id)
                    if (!f.exists()) null else Summarizer.Source("File", f.name, fileText(f), f.lastModified(), item.route)
                }
            }
        }.getOrNull()
    }

    fun fileText(f: File): String = when (FileKinds.of(f)) {
        FileKind.PDF -> PdfTools.text(f)
        FileKind.TEXT, FileKind.CODE, FileKind.CALENDAR -> f.readText().take(400_000)
        FileKind.SHEET -> if (f.extension.lowercase() in setOf("csv", "tsv", "txt")) f.readText().take(200_000) else ""
        else -> ContentStore.plainText(FileOps.toDocumentHtml(f))
    }.take(400_000)

    // ---------------------------------------------------------------- ready-made selections (assistant & car)

    enum class Preset(val label: String, val hint: String) {
        TODAY("My day", "Today's events, tasks and anything you wrote today"),
        TOMORROW("Tomorrow", "Tomorrow's calendar and what's due"),
        WEEK("The week ahead", "Next 7 days of events and deadlines"),
        LAST_MEETING("Latest meeting", "Your most recent recording or dictation"),
        RECENT_NOTES("Recent notes", "Notes, pages and documents from the last 48 hours"),
        OPEN_TASKS("Open tasks", "Everything still on your list"),
        JOURNAL_WEEK("My week in the journal", "Journal entries from the past 7 days"),
    }

    suspend fun preset(app: AppContainer, preset: Preset): List<Summarizer.Source> = withContext(Dispatchers.IO) {
        val profile = app.settings.current.activeProfile
        val today = Time.today()
        val todayEpoch = today.toEpochDay()
        val tasks = app.db.tasks().all().filter { it.profile == profile.name && !it.completed }
        fun taskSrc(t: com.blacklab.app.data.TaskEntity): Summarizer.Source {
            val due = t.dueDay?.let { Time.millis(LocalDate.ofEpochDay(it), t.dueMinute ?: (9 * 60)) }
            return Summarizer.Source("Task", t.title, "Task: ${t.title}." + (due?.let { " Due ${Time.dateTime(it)}." } ?: "") + (if (t.priority >= 3) " High priority." else "") + " ${t.notes}".trimEnd(), t.updatedAt, "task/${t.id}", due)
        }
        suspend fun events(from: LocalDate, to: LocalDate) = app.db.events().all().filter { it.profile == profile.name }.flatMap { CalendarLogic.expand(it, from, to) }.map { o ->
            val ms = Time.millis(o.start.toLocalDate(), o.start.hour * 60 + o.start.minute)
            Summarizer.Source("Event", o.event.title, "${o.event.title} on ${Time.dateTime(ms)}" + (if (o.event.location.isNotBlank()) " at ${o.event.location}." else ".") + " ${o.event.notes}".trimEnd(), ms, "event/${o.event.id}", ms)
        }
        suspend fun recentWritten(sinceMs: Long): List<Summarizer.Source> {
            val docs = app.db.documents().all().filter { it.profile == profile.name && it.updatedAt >= sinceMs }
                .map { Summarizer.Source("Document", it.title, ContentStore.plainText(app.content.readBody(it.filePath)), it.updatedAt, "doc/${it.id}") }
            val notes = app.db.notes().allFor(profile.name).filter { it.trashedAt == null && it.updatedAt >= sinceMs }
                .map { Summarizer.Source("Note", it.title.ifBlank { "Note" }, NoteOps.plainText(it), it.updatedAt, "note/${it.id}") }
            val pages = app.db.brainstorm().allPages().filter { it.profile == profile.name && it.deletedAt == null && it.updatedAt >= sinceMs && it.searchText.isNotBlank() }
                .map { Summarizer.Source("Notebook page", it.title.ifBlank { "Page" }, it.searchText, it.updatedAt, "page/${it.id}") }
            return docs + notes + pages
        }
        when (preset) {
            Preset.TODAY -> events(today, today) +
                tasks.filter { (it.dueDay != null && it.dueDay <= todayEpoch) || it.myDayDay == todayEpoch }.map(::taskSrc) +
                recentWritten(Time.millis(today, 0))
            Preset.TOMORROW -> events(today.plusDays(1), today.plusDays(1)) + tasks.filter { it.dueDay == todayEpoch + 1 }.map(::taskSrc)
            Preset.WEEK -> events(today, today.plusDays(6)) + tasks.filter { it.dueDay != null && it.dueDay <= todayEpoch + 6 }.map(::taskSrc)
            Preset.LAST_MEETING -> listOfNotNull(com.blacklab.app.assistant.MeetingActions.latestSource(app)?.let { Summarizer.Source("Recording", it.title, it.text, it.time, it.route) })
            Preset.RECENT_NOTES -> recentWritten(System.currentTimeMillis() - 48 * 3_600_000L)
            Preset.OPEN_TASKS -> tasks.sortedWith(compareBy({ it.dueDay ?: Long.MAX_VALUE }, { -it.priority })).take(40).map(::taskSrc)
            Preset.JOURNAL_WEEK -> app.db.journal().allFor(profile.name).filter { it.entryDay >= todayEpoch - 6 }
                .map { Summarizer.Source("Journal", it.title.ifBlank { "Journal · " + Time.date(LocalDate.ofEpochDay(it.entryDay)) }, ContentStore.plainText(app.content.readBody(it.filePath)), Time.millis(LocalDate.ofEpochDay(it.entryDay), 12 * 60), "journal/${it.id}") }
        }
    }
}

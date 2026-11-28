package com.blacklab.app.assistant

import android.content.Context
import com.blacklab.app.AppContainer
import com.blacklab.app.core.Profile
import com.blacklab.app.core.Time
import com.blacklab.app.data.CalendarLogic
import com.blacklab.app.data.ContentStore
import com.blacklab.app.data.DocumentEntity
import com.blacklab.app.data.TaskEntity
import com.blacklab.app.data.TaskOps
import com.blacklab.app.reminders.Notifications
import com.blacklab.app.ui.tasks.QuickParse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Hand-off jobs: work the phone gives the computer and picks up later (BlackLab Desktop companion 1.4+).
 *
 *  - New recordings are transcribed offline on the computer (Whisper); the minutes come back as a
 *    document in Meetings, the transcript fills in the recording, and the action items become tasks.
 *  - Nameplate photos go into the computer's equipment registry with each unit's history.
 *  - The computer writes a morning brief (05:30) and a Friday weekly summary from the snapshot this
 *    phone sends: open and recently finished tasks, the next 3 days of events, coursework and exams,
 *    flashcards due and weather alerts. Nothing from the Behavioral Health workspace is included.
 *  - "In the background: …" runs any desktop-assistant request without waiting for it.
 *
 * Work waits in an outbox while the computer is out of reach and goes the next time the link is up;
 * results arrive on the next check-in (every minute while connected) as notifications.
 */
object DesktopJobs {
    data class Job(val id: String, val kind: String, val title: String, val status: String, val progress: String, val error: String?,
                   val origin: String = "", val private: Boolean = false)

    private const val PREFS = "desktop_jobs"
    private const val TAG = "DesktopJobs"
    private const val CONTEXT_EVERY_MS = 30 * 60_000L
    private val lock = Mutex()
    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    /** Jobs the computer is working on, from the last check-in. */
    val jobs: StateFlow<List<Job>> = _jobs.asStateFlow()
    private val _outbox = MutableStateFlow(0)
    val outbox: StateFlow<Int> = _outbox.asStateFlow()

    private fun enabled(app: AppContainer) = app.settings.current.let { it.asstDesktopAi && it.asstDesktopJobs }

    // ---------------------------------------------------------------- outbox

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun pending(c: Context): JSONArray = runCatching { JSONArray(prefs(c).getString("outbox", "[]")) }.getOrDefault(JSONArray())

    private fun savePending(c: Context, a: JSONArray) {
        prefs(c).edit().putString("outbox", a.toString()).apply()
        _outbox.value = a.length()
    }

    /** Queues work for the computer. A file in the app cache is copied so it outlives the cache. */
    fun enqueue(app: AppContainer, kind: String, file: File?, title: String, params: JSONObject = JSONObject(), private: Boolean = false) {
        if (!enabled(app)) return
        val c = app.context
        val keep = file?.let { f ->
            if (f.absolutePath.startsWith(c.cacheDir.absolutePath)) {
                val dir = File(c.filesDir, "desktop-outbox").apply { mkdirs() }
                File(dir, "${System.currentTimeMillis()}_${f.name}").also { f.copyTo(it, overwrite = true) }
            } else f
        }
        synchronized(this) {
            val a = pending(c)
            a.put(JSONObject().put("kind", kind).put("title", title).put("params", params).put("private", private).put("path", keep?.absolutePath ?: ""))
            savePending(c, a)
        }
        app.appScope.launch { sync(app) }
    }

    /**
     * Queues one call to the computer (vault capture, log, node add, private-drive save…) to be made as soon as it's
     * reachable. Used when the computer is away, so nothing you ask it to save is lost.
     */
    fun queueCall(app: AppContainer, path: String, body: JSONObject, title: String) {
        val c = app.context
        synchronized(this) {
            val a = pending(c)
            a.put(JSONObject().put("kind", "__call").put("title", title).put("call", path).put("params", body).put("path", ""))
            savePending(c, a)
        }
        DesktopAi.kick()
    }

    /** Starts a job that needs no file and says whether the computer took it. */
    suspend fun start(app: AppContainer, kind: String, title: String, params: JSONObject = JSONObject()): Boolean {
        if (!enabled(app) || DesktopAi.probe().state != DesktopAi.State.ONLINE) return false
        return withContext(Dispatchers.IO) {
            runCatching { DesktopAi.request("POST", "/phone/jobs", JSONObject().put("kind", kind).put("title", title).put("params", params), 15_000) }.isSuccess
        }.also { if (it) app.appScope.launch { sync(app) } }
    }

    fun onRecordingSaved(app: AppContainer, recordingId: Long, file: File, title: String) {
        if (!enabled(app)) return
        enqueue(app, "transcribe", file, title, JSONObject().put("recording_id", recordingId))
    }

    // ---------------------------------------------------------------- check-in

    /** Called by DesktopAi's link check while the computer is reachable. */
    suspend fun sync(app: AppContainer) {
        if (!app.settings.current.asstDesktopAi || !DesktopAi.usable) return
        _outbox.value = pending(app.context).length()
        lock.withLock {
            withContext(Dispatchers.IO) {
                // queued saves go out whenever the link is on; the rest needs "Desktop jobs"
                runCatching { sendOutbox(app) }.onFailure { android.util.Log.w(TAG, "outbox", it) }
                if (!enabled(app)) return@withContext
                runCatching { sendContext(app) }.onFailure { android.util.Log.w(TAG, "context", it) }
                runCatching { collect(app) }.onFailure { android.util.Log.w(TAG, "collect", it) }
                runCatching { com.blacklab.app.link.VaultFeed.sync(app) }.onFailure { android.util.Log.w(TAG, "vault", it) }
            }
        }
    }

    private fun sendOutbox(app: AppContainer) {
        val c = app.context
        while (true) {
            val item = synchronized(this) { pending(c).optJSONObject(0) } ?: return
            val path = item.optString("path")
            val f = path.takeIf { it.isNotBlank() }?.let(::File)
            val upload = if (f != null) {
                if (!f.isFile) null else DesktopAi.upload(f)
            } else null
            if (item.optString("kind") == "__call") {
                val r = DesktopAi.request("POST", item.optString("call"), item.optJSONObject("params") ?: JSONObject(), 60_000)
                Notifications.simple(c, 7200 + (item.optString("title").hashCode() and 0x3ff), Notifications.CH_DESKTOP, "Sent to your computer",
                    item.optString("title") + (r.optString("message").takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""), null)
            } else if (f == null || upload != null) {
                DesktopAi.request("POST", "/phone/jobs", JSONObject().put("kind", item.optString("kind")).put("title", item.optString("title"))
                    .put("params", item.optJSONObject("params") ?: JSONObject()).put("private", item.optBoolean("private")).apply { upload?.let { put("upload", it) } }, 30_000)
            }
            synchronized(this) {
                val a = pending(c)
                val rest = JSONArray()
                for (i in 1 until a.length()) rest.put(a.get(i))
                savePending(c, rest)
            }
            if (f != null && f.absolutePath.contains("/desktop-outbox/")) f.delete()
        }
    }

    private suspend fun sendContext(app: AppContainer) {
        val p = prefs(app.context)
        if (System.currentTimeMillis() - p.getLong("context_at", 0) < CONTEXT_EVERY_MS) return
        DesktopAi.request("POST", "/phone/context", snapshot(app), 30_000)
        p.edit().putLong("context_at", System.currentTimeMillis()).apply()
    }

    private suspend fun collect(app: AppContainer) {
        val j = DesktopAi.request("GET", "/phone/jobs", null, 15_000)
        val arr = j.optJSONArray("jobs") ?: JSONArray()
        val active = ArrayList<Job>()
        val done = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val job = Job(o.optString("id"), o.optString("kind"), o.optString("title"), o.optString("status"), o.optString("progress"), o.optString("error").takeIf { it.isNotBlank() && it != "null" },
                o.optString("origin"), o.optBoolean("private"))
            if (job.status == "done" || job.status == "failed") {
                runCatching { apply(app, job, o.optJSONObject("result") ?: JSONObject()) }.onFailure { android.util.Log.w(TAG, "apply ${job.kind}", it) }
                done += job.id
            } else active += job
        }
        _jobs.value = active
        TaskWatcher.ensure(app, active)
        if (done.isNotEmpty()) DesktopAi.request("POST", "/phone/jobs/ack", JSONObject().put("ids", JSONArray(done)), 15_000)
        j.optJSONArray("ops")?.let { if (it.length() > 0) applyOps(app, it) }
    }

    // ---------------------------------------------------------------- Obsidian → phone (5.17)

    /**
     * Changes made in Obsidian, carried out here: a box checked in Tasks.md, a new task line, a #task / #workorder /
     * #event / #remind trigger tag, a flashcard written as `front :: back`. Each op is applied once (its id is
     * remembered) and acked, so the computer stops sending it.
     */
    private suspend fun applyOps(app: AppContainer, ops: JSONArray) {
        val p = prefs(app.context)
        val seen = p.getString("ops_seen", "")!!.split(',').filter { it.isNotBlank() }.toMutableList()
        val acked = JSONArray()
        val failed = JSONArray()
        var applied = 0
        for (i in 0 until ops.length()) {
            val o = ops.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            if (id in seen) { acked.put(id); continue }
            val r = runCatching { applyOp(app, o) }
            if (r.isSuccess) { applied++; seen += id; acked.put(id) }
            else { failed.put(JSONObject().put("id", id).put("error", r.exceptionOrNull()?.message ?: "failed")); acked.put(id) }
        }
        p.edit().putString("ops_seen", seen.takeLast(800).joinToString(",")).apply()
        if (acked.length() > 0) DesktopAi.request("POST", "/phone/ops/ack", JSONObject().put("ids", acked).put("failed", failed), 15_000)
        if (applied > 0) {
            Notifications.simple(app.context, 7280, Notifications.CH_DESKTOP, "From Obsidian", "$applied change${if (applied == 1) "" else "s"} from your vault applied", "tasks")
            app.appScope.launch { runCatching { com.blacklab.app.link.VaultFeed.sync(app, force = true) } }
        }
    }

    internal suspend fun applyOp(app: AppContainer, o: JSONObject) {
        when (o.optString("op")) {
            "task.complete", "task.reopen" -> {
                val t = app.db.tasks().get(o.optLong("task_id")) ?: error("task ${o.optLong("task_id")} isn't on the phone any more")
                TaskOps.setCompleted(app, t, o.optString("op") == "task.complete")
            }
            "task.add" -> {
                val profile = runCatching { Profile.valueOf(o.optString("profile", "PROFESSIONAL")) }.getOrDefault(Profile.PROFESSIONAL)
                val listName = o.optString("list").ifBlank { "Tasks" }
                val lists = app.db.tasks().lists(profile.name)
                val listId = (lists.firstOrNull { it.name.equals(listName, true) }
                    ?: lists.firstOrNull { it.name.equals("Tasks", true) })?.id
                val parsed = QuickParse.parse(o.optString("title") + (o.optString("due").takeIf { it.isNotBlank() }?.let { " by $it" } ?: ""))
                val due = o.optString("due").takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it).toEpochDay() }.getOrNull() } ?: parsed.dueDay?.toEpochDay()
                TaskOps.save(app, TaskEntity(profile = profile.name, listId = listId, title = parsed.title.ifBlank { o.optString("title") }.take(140),
                    notes = o.optString("notes"), dueDay = due, dueMinute = parsed.dueMinute, tags = listOf(o.optString("tags"), "obsidian").filter { it.isNotBlank() }.joinToString(", ")))
            }
            "command" -> app.assistant.handle(o.optString("text"), com.blacklab.app.assistant.Origin.DESKTOP)
            "card.add" -> {
                val ed = app.db.education()
                val name = o.optString("deck").ifBlank { "Obsidian" }.take(80)
                val deck = ed.allDecks().firstOrNull { it.name.equals(name, true) }
                val deckId = deck?.id ?: ed.insertDeck(com.blacklab.app.data.DeckEntity(profile = app.settings.current.activeProfile.name, name = name))
                val front = o.optString("front").trim()
                if (front.isBlank()) error("empty card")
                if (ed.allCards().none { it.deckId == deckId && it.front.equals(front, true) })
                    ed.insertCard(com.blacklab.app.data.FlashcardEntity(deckId = deckId, front = front.take(500), back = o.optString("back").take(1000), dueDay = LocalDate.now().toEpochDay()))
            }
            "project.status" -> {} // the Home card reads /phone/projects
            else -> error("unknown op ${o.optString("op")}")
        }
    }

    // ---------------------------------------------------------------- results

    private suspend fun apply(app: AppContainer, job: Job, r: JSONObject) {
        val c = app.context
        val nid = 7300 + (job.id.hashCode() and 0x3ff)
        if (job.status == "failed") {
            Notifications.big(c, nid, Notifications.CH_DESKTOP, "Your computer couldn't finish: ${job.title}".take(80), job.error ?: "Unknown error", null)
            return
        }
        val note = r.optJSONObject("notify")
        val nTitle = note?.optString("title").orEmpty().ifBlank { "Your computer finished: ${job.title}" }
        val nText = note?.optString("text").orEmpty().ifBlank { r.optString("text").take(300) }
        when (job.kind) {
            "transcribe" -> {
                val profile = app.settings.current.activeProfile
                r.optLong("recording_id").takeIf { it > 0 }?.let { id ->
                    app.db.recordings().get(id)?.let { rec ->
                        val t = r.optString("transcript")
                        val generic = rec.title.startsWith("Recording ")
                        app.db.recordings().update(rec.copy(transcript = t.ifBlank { rec.transcript }, title = if (generic) r.optString("title", rec.title).take(80) else rec.title))
                    }
                }
                val docId = saveDoc(app, profile, "Minutes — " + r.optString("title", job.title), "Meetings", r.optString("minutes_md"), "meeting")
                val lists = app.db.tasks().lists(profile.name)
                val listId = (lists.firstOrNull { it.name.equals("Follow-ups", true) } ?: lists.firstOrNull { it.name.equals(app.settings.current.asstWorkOrderList, true) })?.id
                val tasks = r.optJSONArray("tasks") ?: JSONArray()
                for (i in 0 until tasks.length()) {
                    val t = tasks.getJSONObject(i)
                    val due = t.optString("due")
                    val p = QuickParse.parse(t.optString("title") + if (due.isNotBlank()) " by $due" else "")
                    TaskOps.save(app, TaskEntity(profile = profile.name, listId = listId, title = t.optString("title").take(140),
                        notes = t.optString("notes"), dueDay = p.dueDay?.toEpochDay(), dueMinute = p.dueMinute, tags = "meeting"))
                }
                Notifications.big(c, nid, Notifications.CH_DESKTOP, nTitle, nText, "doc/$docId")
            }
            "equipment" -> {
                r.optLong("task_id").takeIf { it > 0 }?.let { tid ->
                    app.db.tasks().get(tid)?.let { t ->
                        val tag = r.optJSONObject("asset")?.optString("tag").orEmpty()
                        if (tag.isNotBlank() && !t.notes.contains("Equipment: $tag")) TaskOps.save(app, t.copy(notes = t.notes + "\nEquipment: $tag (registry on the computer)"))
                    }
                }
                Notifications.big(c, nid, Notifications.CH_DESKTOP, nTitle, nText, r.optLong("task_id").takeIf { it > 0 }?.let { "task/$it" })
            }
            "reverse" -> {
                val docId = saveDoc(app, Profile.PROFESSIONAL, r.optString("title", job.title), "Reverse Engineering", r.optString("text"), "reverse")
                Notifications.big(c, nid, Notifications.CH_DESKTOP, nTitle, nText, "doc/$docId")
            }
            "brief", "weekly" -> {
                val docId = saveDoc(app, Profile.PROFESSIONAL, r.optString("title", job.title), "Briefs", r.optString("text"), "brief")
                Notifications.big(c, nid, Notifications.CH_AGENDA, nTitle, nText, "doc/$docId")
            }
            "health" -> {
                // the nightly system check: shown in-app the next time BlackLab opens; a notification only when something's wrong
                val docId = saveDoc(app, Profile.PROFESSIONAL, r.optString("title", job.title), "System Health", r.optString("text"), "health")
                val show = com.blacklab.app.link.SystemHealth.receive(c, r, docId)
                if (show && r.optString("status") != "ok") Notifications.big(c, 7290, Notifications.CH_DESKTOP, nTitle, nText, "doc/$docId")
            }
            else -> Notifications.big(c, nid, Notifications.CH_DESKTOP, nTitle, nText, "assistant")
        }
    }

    private suspend fun saveDoc(app: AppContainer, profile: Profile, title: String, folder: String, markdown: String, tag: String): Long {
        val html = markdownToHtml(markdown)
        val rel = app.content.writeDocument(profile, title.take(80), folder, "", html)
        val text = ContentStore.plainText(html)
        return app.db.documents().insert(DocumentEntity(profile = profile.name, title = title.take(80), folder = folder, filePath = rel,
            preview = ContentStore.preview(text), wordCount = ContentStore.wordCount(text), tags = "$tag, desktop"))
    }

    /** Just enough Markdown for minutes, briefs and reverse-engineering reports: headings, lists, checkboxes, bold, code, paragraphs. */
    fun markdownToHtml(md: String): String {
        val e = ContentStore::escape
        fun inline(s: String) = e(s).replace(Regex("`([^`]+)`"), "<code>$1</code>").replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
            .replace(Regex("(?<![*\\w])_(.+?)_(?![\\w])"), "<i>$1</i>")
        val out = StringBuilder()
        var list: String? = null
        var code: StringBuilder? = null
        fun close() { list?.let { out.append(if (it == "cl") "</ul>" else "</$it>") }; list = null }
        md.lines().forEach { raw ->
            val l = raw.trimEnd()
            if (l.trimStart().startsWith("```")) {
                code = if (code == null) { close(); StringBuilder() } else { out.append("<pre><code>").append(e(code.toString().trimEnd('\n'))).append("</code></pre>"); null }
                return@forEach
            }
            code?.let { it.append(raw).append('\n'); return@forEach }
            when {
                l.startsWith("# ") -> { close(); out.append("<h1 class=\"title\">${inline(l.drop(2))}</h1>") }
                l.startsWith("## ") -> { close(); out.append("<h2>${inline(l.drop(3))}</h2>") }
                l.startsWith("### ") -> { close(); out.append("<h3>${inline(l.drop(4))}</h3>") }
                Regex("^\\s*[-*] \\[[ xX]] ").containsMatchIn(l) -> {
                    if (list != "cl") { close(); out.append("<ul class=\"checklist\">"); list = "cl" }
                    out.append("<li>${inline(l.substringAfter("] "))}</li>")
                }
                Regex("^\\s*[-*•] ").containsMatchIn(l) -> { if (list != "ul") { close(); out.append("<ul>"); list = "ul" }; out.append("<li>${inline(l.trim().drop(2))}</li>") }
                Regex("^\\s*\\d+[.)] ").containsMatchIn(l) -> { if (list != "ol") { close(); out.append("<ol>"); list = "ol" }; out.append("<li>${inline(l.trim().substringAfter(' '))}</li>") }
                l.startsWith(">") -> { close(); out.append("<blockquote>${inline(l.trimStart('>', ' '))}</blockquote>") }
                l.isBlank() -> close()
                else -> { close(); out.append("<p>${inline(l)}</p>") }
            }
        }
        close()
        code?.let { out.append("<pre><code>").append(e(it.toString().trimEnd('\n'))).append("</code></pre>") }
        return out.toString()
    }

    // ---------------------------------------------------------------- context snapshot

    /** What the overnight analyst works from. Tasks, events, coursework and weather only; never Behavioral Health data. */
    suspend fun snapshot(app: AppContainer): JSONObject {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val now = System.currentTimeMillis()
        val woList = app.settings.current.asstWorkOrderList
        val lists = Profile.values().flatMap { app.db.tasks().lists(it.name) }.associateBy { it.id }
        val all = app.db.tasks().all()
        fun task(t: TaskEntity) = JSONObject().apply {
            val wo = t.tags.contains("workorder", true) || lists[t.listId]?.name.equals(woList, true)
            put("title", t.title); put("profile", t.profile); put("priority", t.priority); put("created", t.createdAt)
            put("due", t.dueDay?.let { LocalDate.ofEpochDay(it).toString() } ?: JSONObject.NULL)
            put("list", lists[t.listId]?.name ?: ""); put("tags", t.tags); put("workorder", wo); put("completed", t.completed)
            if (wo) put("notes", t.notes.take(300))
        }
        val open = JSONArray().apply { all.filter { !it.completed }.sortedBy { it.dueDay ?: Long.MAX_VALUE }.take(150).forEach { put(task(it)) } }
        val done = JSONArray().apply { all.filter { it.completed && (it.completedAt ?: 0) >= now - 7 * 86_400_000L }.take(150).forEach { put(task(it)) } }
        val events = JSONArray().apply {
            app.db.events().all().flatMap { CalendarLogic.expand(it, today, today.plusDays(2)) }.sortedBy { it.start }.take(40).forEach { o ->
                put(JSONObject().put("title", o.event.title).put("start", o.start.toString()).put("end", o.end.toString()).put("location", o.event.location).put("profile", o.event.profile))
            }
        }
        val ed = app.db.education()
        val courses = ed.allCourses().associateBy { it.id }
        val assignments = JSONArray().apply {
            ed.allAssignments().filter { it.status !in setOf("SUBMITTED", "GRADED") && it.dueAt != null && it.dueAt in now..(now + 14 * 86_400_000L) }
                .sortedBy { it.dueAt }.take(30).forEach { a ->
                    put(JSONObject().put("course", courses[a.courseId]?.let { listOf(it.code, it.title).filter(String::isNotBlank).joinToString(" ") } ?: "")
                        .put("title", a.title).put("kind", a.kind).put("status", a.status)
                        .put("due", Instant.ofEpochMilli(a.dueAt!!).atZone(zone).toLocalDate().toString()))
                }
        }
        val exams = JSONArray().apply {
            ed.allCerts().filter { it.examAt != null && it.examAt in now..(now + 30 * 86_400_000L) }.forEach { c ->
                put(JSONObject().put("course", c.name).put("title", "${c.name} exam").put("kind", "Exam").put("due", Instant.ofEpochMilli(c.examAt!!).atZone(zone).toLocalDate().toString()))
            }
        }
        val cardsDue = Profile.values().sumOf { p -> ed.observeAllCards(p.name).first().count { it.dueDay <= today.toEpochDay() } }
        val weather = Weather.report.value?.let { r ->
            JSONObject().put("summary", Weather.summary(r)).put("alerts", JSONArray().apply {
                Weather.alerts(r).forEach { a -> put(JSONObject().put("title", a.title).put("detail", a.detail).put("checklist", JSONArray(a.checklist))) }
            })
        }
        return JSONObject().put("generated", now).put("user", app.settings.current.userName).put("tasks_open", open).put("tasks_done_7d", done)
            .put("events", events).put("assignments", assignments).put("exams", exams).put("flashcards_due", cardsDue)
            .put("weather", weather ?: JSONObject.NULL).put("phone_time", Time.dateTime(now))
    }
}

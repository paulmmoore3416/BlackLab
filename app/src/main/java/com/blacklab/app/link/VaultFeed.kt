package com.blacklab.app.link

import com.blacklab.app.AppContainer
import com.blacklab.app.assistant.DesktopAi
import com.blacklab.app.core.ActivityFeed
import com.blacklab.app.core.Profile
import com.blacklab.app.data.CalendarLogic
import com.blacklab.app.data.ContentStore
import com.blacklab.app.facility.Facility
import com.blacklab.app.vehicle.Garage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The phone's side of the Obsidian vault on the computer (desktop/companion/vault.py): a feed of what BlackLab
 * holds, sent to BlackLab Desktop (`POST /phone/vault`) while the desktop link is up, at most every 15 minutes
 * and only when something changed. Settings › Obsidian vault chooses what goes.
 *
 * Never sent: Behavioral Health (it never leaves its encrypted store), API keys, the VIN and plate, trip places,
 * locked notebook sections, and the journal unless you turn it on. The desktop refuses clinical text as well.
 */
object VaultFeed {
    private const val EVERY_MS = 15 * 60_000L
    private val lock = Mutex()
    private val iso = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    data class Status(val ok: Boolean, val message: String, val json: JSONObject? = null)

    private fun prefs(app: AppContainer) = app.context.getSharedPreferences("vault_feed", 0)

    fun enabled(app: AppContainer) = app.settings.current.let { it.vaultEnabled && it.asstDesktopAi }

    /** Called from the desktop link check (DesktopJobs.sync). [force] skips the interval and the unchanged check. */
    suspend fun sync(app: AppContainer, force: Boolean = false): Status = lock.withLock {
        if (!enabled(app)) return@withLock Status(false, "The Obsidian vault is off (Settings › Obsidian vault).")
        if (!DesktopAi.usable) return@withLock Status(false, "The computer isn't reachable right now.")
        val p = prefs(app)
        if (!force && System.currentTimeMillis() - p.getLong("at", 0) < EVERY_MS) return@withLock Status(true, "Synced recently.")
        withContext(Dispatchers.IO) {
            val feed = build(app)
            val hash = sha(feed.toString())
            if (!force && hash == p.getString("hash", "")) {
                p.edit().putLong("at", System.currentTimeMillis()).apply()
                return@withContext Status(true, "Nothing new for the vault.")
            }
            val r = runCatching { DesktopAi.request("POST", "/phone/vault", feed, 120_000) }
            r.fold({ j ->
                p.edit().putLong("at", System.currentTimeMillis()).putString("hash", hash).putString("last", j.optJSONObject("status")?.toString() ?: "").apply()
                Status(true, "Vault synced.", j)
            }, { e -> Status(false, "Couldn't reach the vault: ${e.message ?: e.javaClass.simpleName}") })
        }
    }

    fun lastSyncAt(app: AppContainer): Long = prefs(app).getLong("at", 0)

    /** The desktop's own view of the vault (notes, commits, last AI visitor…). */
    suspend fun status(): JSONObject? = withContext(Dispatchers.IO) { runCatching { DesktopAi.request("POST", "/phone/vault/status", JSONObject(), 15_000) }.getOrNull() }

    /** "save this to Obsidian: …" — a note in 00 Inbox. */
    suspend fun capture(text: String, title: String = ""): Status = withContext(Dispatchers.IO) {
        runCatching { DesktopAi.request("POST", "/phone/vault/capture", JSONObject().put("text", text).put("title", title).put("source", "Phone"), 20_000) }
            .fold({ j -> if (j.optBoolean("ok", true)) Status(true, "Saved to your vault: ${j.optString("path").substringAfterLast('/').removeSuffix(".md")}.", j) else Status(false, j.optString("error")) },
                { Status(false, "Couldn't reach the vault: ${it.message}") })
    }

    /** "save to my private drive (under taxes): …" — the computer files it into Google Drive › BlackLab › Paul's Private. */
    suspend fun privateDrive(said: String): Status = withContext(Dispatchers.IO) {
        runCatching { DesktopAi.request("POST", "/phone/vault/drive_save", JSONObject().put("say", said).put("source", "Phone"), 30_000) }
            .fold({ j -> if (j.optBoolean("ok")) Status(true, j.optString("message").ifBlank { "Saved to your private drive." }, j) else Status(false, j.optString("error").ifBlank { "The private drive didn't take that." }) },
                { Status(false, "Couldn't reach your computer to save to the private drive: ${it.message}") })
    }

    /** "log to the vault: …" — a line on today's timeline. */
    suspend fun log(title: String, detail: String = ""): Status = withContext(Dispatchers.IO) {
        runCatching { DesktopAi.request("POST", "/phone/vault/log", JSONObject().put("title", title).put("detail", detail).put("kind", "note").put("source", "Phone"), 20_000) }
            .fold({ j -> if (j.optBoolean("ok")) Status(true, "Logged on today's timeline.") else Status(false, "The vault didn't take that (private text never goes there).") },
                { Status(false, "Couldn't reach the vault: ${it.message}") })
    }

    /** "add to the facility node: …" — knowledge for one of the 13 main nodes. */
    suspend fun addToNode(node: String, text: String): Status = withContext(Dispatchers.IO) {
        runCatching { DesktopAi.request("POST", "/phone/vault/node_add", JSONObject().put("node", node).put("content", text).put("author", "Phone"), 20_000) }
            .fold({ j -> if (j.optBoolean("ok")) Status(true, "Added to ◆ ${j.optString("node")}.") else Status(false, j.optString("error").ifBlank { "The vault didn't take that." }) },
                { Status(false, "Couldn't reach the vault: ${it.message}") })
    }

    /** The computer's offline library (~50 GB of references) — "look it up in the library: …". */
    suspend fun library(query: String): Status = withContext(Dispatchers.IO) {
        runCatching { DesktopAi.request("POST", "/phone/vault/library", JSONObject().put("query", query).put("limit", 4), 30_000) }
            .fold({ j -> Status(true, "", j) }, { Status(false, "Couldn't reach the library on your computer: ${it.message}") })
    }

    /** "what do my notes say about …": kb-core recall on the computer + an answer from its local model, with the notes it used. */
    suspend fun ask(query: String): Status = withContext(Dispatchers.IO) {
        runCatching { DesktopAi.request("POST", "/phone/vault/ask", JSONObject().put("query", query).put("answer", true).put("limit", 5), 120_000) }
            .fold({ j -> if (j.optBoolean("ok")) Status(true, j.optString("answer"), j) else Status(false, j.optString("error").ifBlank { "Your notes didn't answer." }) },
                { Status(false, "Couldn't reach your computer to ask your notes: ${it.message}") })
    }

    /** Facility ops on the computer (shift_prep, round, panel, sds, credentials): the computer writes the sentence. */
    suspend fun facility(op: String, body: JSONObject): Status = withContext(Dispatchers.IO) {
        runCatching { DesktopAi.request("POST", "/phone/vault/$op", body.put("source", "Phone"), 30_000) }
            .fold({ j -> if (j.optBoolean("ok", true)) Status(true, j.optString("message"), j) else Status(false, j.optString("error").ifBlank { "The computer didn't take that." }) },
                { Status(false, "Couldn't reach your computer: ${it.message}") })
    }

    suspend fun search(query: String): Status = withContext(Dispatchers.IO) {
        runCatching { DesktopAi.request("POST", "/phone/vault/search", JSONObject().put("query", query).put("limit", 5), 20_000) }
            .fold({ j -> Status(true, "", j) }, { Status(false, "Couldn't reach the vault: ${it.message}") })
    }

    /** Opens Obsidian on the computer at a note's local graph (or the whole graph when [query] is blank). */
    suspend fun showInGraph(query: String): Status = withContext(Dispatchers.IO) {
        runCatching { DesktopAi.request("POST", "/phone/vault/show", JSONObject().put("query", query.trim()), 20_000) }
            .fold({ j -> Status(true, j.optString("message", "Opening the graph on your computer."), j) }, { Status(false, "Couldn't reach the vault: ${it.message}") })
    }

    suspend fun openOnComputer(): Status = withContext(Dispatchers.IO) {
        runCatching { DesktopAi.request("POST", "/phone/vault/open", JSONObject(), 15_000) }
            .fold({ Status(true, "Opening your vault in Obsidian on the computer.") }, { Status(false, "Couldn't reach the computer: ${it.message}") })
    }

    // ---------------------------------------------------------------- the feed

    suspend fun build(app: AppContainer): JSONObject {
        val s = app.settings.current
        val ctx = app.context
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val now = System.currentTimeMillis()
        val out = JSONObject().put("v", 1).put("generated", now)
            .put("user", JSONObject().put("name", s.userName).put("title", s.jobTitle).put("org", s.organization))

        if (s.vaultFacility) out.put("facility", facility(Facility.load(ctx)))
        if (s.vaultGarage) out.put("garage", garage(app))

        val lists = Profile.entries.flatMap { app.db.tasks().lists(it.name) }.associateBy { it.id }
        val allTasks = app.db.tasks().all()
        if (s.vaultTasks) {
            val woList = s.asstWorkOrderList
            out.put("tasks", JSONArray().apply {
                allTasks.filter { !it.completed || (it.completedAt ?: 0) > now - 30 * 86_400_000L }.take(1500).forEach { t ->
                    val list = lists[t.listId]?.name ?: ""
                    put(JSONObject().put("id", t.id).put("title", t.title).put("notes", t.notes.take(2000)).put("list", list).put("profile", t.profile)
                        .put("due", t.dueDay?.let { LocalDate.ofEpochDay(it).toString() } ?: JSONObject.NULL).put("priority", t.priority)
                        .put("completed", t.completed).put("created", t.createdAt).put("tags", t.tags)
                        .put("workorder", t.tags.contains("workorder", true) || t.tags.contains("work-order", true) || list.equals(woList, true)))
                }
            })
            out.put("events", JSONArray().apply {
                app.db.events().all().flatMap { CalendarLogic.expand(it, today.minusDays(7), today.plusDays(30)) }.sortedBy { it.start }.take(600).forEach { o ->
                    put(JSONObject().put("title", o.event.title).put("start", o.start.format(iso)).put("end", o.end.format(iso))
                        .put("location", o.event.location).put("profile", o.event.profile))
                }
            })
        }
        if (s.vaultNotes) {
            out.put("notes", JSONArray().apply {
                app.db.notes().all().filter { it.trashedAt == null }.take(1500).forEach { n ->
                    put(JSONObject().put("id", n.id).put("title", n.title).put("text", com.blacklab.app.brainstorm.NoteOps.plainText(n).take(20_000))
                        .put("labels", JSONArray(com.blacklab.app.brainstorm.NoteOps.labels(n))).put("pinned", n.pinned).put("archived", n.archived)
                        .put("updatedAt", n.updatedAt))
                }
            })
            // Same notebook shape the desktop already reads; locked sections arrive with no text
            out.put("notebooks", Snapshot.brainstorm(app).optJSONArray("notebooks") ?: JSONArray())
        }
        if (s.vaultLearning) {
            val ed = app.db.education()
            out.put("learning", JSONObject()
                .put("courses", JSONArray().apply {
                    ed.allCourses().forEach { c ->
                        put(JSONObject().put("id", c.id).put("code", c.code).put("title", c.title).put("institution", c.institution).put("instructor", c.instructor)
                            .put("officeHours", c.officeHours).put("location", c.location))
                    }
                })
                .put("assignments", JSONArray().apply {
                    ed.allAssignments().forEach { a ->
                        put(JSONObject().put("courseId", a.courseId).put("title", a.title).put("kind", a.kind).put("status", a.status)
                            .put("due", a.dueAt?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toString() } ?: JSONObject.NULL))
                    }
                })
                .put("certs", JSONArray().apply {
                    ed.allCerts().forEach { c ->
                        put(JSONObject().put("name", c.name).put("issuer", c.issuer).put("status", c.status.lowercase().replace('_', ' '))
                            .put("expires", c.expiresDay?.let { LocalDate.ofEpochDay(it).toString() } ?: "")
                            .put("exam", c.examAt?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toString() } ?: "")
                            .put("notes", c.notes.take(2000)))
                    }
                }))
        }
        if (s.vaultRecordings) {
            out.put("recordings", JSONArray().apply {
                Profile.entries.flatMap { app.db.recordings().allFor(it.name) }.filter { it.transcript.isNotBlank() || it.notes.isNotBlank() }
                    .sortedByDescending { it.createdAt }.take(300).forEach { r ->
                        put(JSONObject().put("id", r.id).put("title", r.title).put("createdAt", r.createdAt).put("durationMs", r.durationMs)
                            .put("transcript", r.transcript.take(60_000)).put("notes", r.notes.take(5000)))
                    }
            })
        }
        if (s.vaultJournal) {
            out.put("journal", JSONArray().apply {
                Profile.entries.flatMap { app.db.journal().allFor(it.name) }.sortedByDescending { it.entryDay }.take(400).forEach { j ->
                    put(JSONObject().put("day", j.entryDay).put("title", j.title).put("text", ContentStore.plainText(app.content.readBody(j.filePath)).take(30_000)))
                }
            })
        }
        // What happened since yesterday, for the daily timeline
        val items = ActivityFeed.build(since = now - 36 * 3_600_000L, now = now, tasks = allTasks, events = app.db.events().all(),
            docs = app.db.documents().all(), recordings = Profile.entries.flatMap { app.db.recordings().allFor(it.name) }, notes = app.db.notes().all(), limit = 200)
        out.put("activity", JSONArray().apply {
            items.forEach { a ->
                val kind = when (a.kind) {
                    ActivityFeed.Kind.TASK_DONE, ActivityFeed.Kind.TASK_NEW, ActivityFeed.Kind.REMINDER -> "task"
                    ActivityFeed.Kind.EVENT_NEW -> "event"
                    ActivityFeed.Kind.RECORDING -> "meeting"
                    else -> "note"
                }
                put(JSONObject().put("time", a.time).put("kind", kind).put("title", a.title).put("detail", a.detail))
            }
        })
        return out
    }

    /** Facility in the vault's shape (pure; unit tested). */
    fun facility(f: Facility.State): JSONObject {
        val due = Facility.due(f).associateBy { it.pm.id }
        return JSONObject().put("site", f.site)
            .put("assets", JSONArray().apply {
                f.assets.forEach { a ->
                    put(JSONObject().put("id", a.id).put("name", a.name).put("kind", a.kind).put("tag", a.tag).put("location", a.location)
                        .put("make", a.make).put("model", a.model).put("serial", a.serial)
                        .put("installed", a.installedDay?.let { LocalDate.ofEpochDay(it).toString() } ?: "").put("notes", a.notes)
                        .put("pms", JSONArray().apply {
                            a.pms.forEach { p ->
                                put(JSONObject().put("id", p.id).put("title", p.title).put("everyDays", p.everyDays).put("standard", p.standard)
                                    .put("lastDay", p.lastDay ?: JSONObject.NULL).put("dueDay", p.dueDay).put("status", due[p.id]?.status?.name ?: "OK"))
                            }
                        }))
                }
            })
            .put("log", JSONArray().apply {
                f.log.sortedByDescending { it.day }.take(5000).forEach { e ->
                    put(JSONObject().put("id", e.id).put("assetId", e.assetId).put("pmId", e.pmId ?: "").put("day", e.day).put("kind", e.kind.label)
                        .put("result", e.result.label).put("text", e.text).put("by", e.by))
                }
            })
    }

    /** The car without its VIN, plate or where it has been. */
    fun garage(app: AppContainer): JSONObject {
        val g = Garage.load(app.context)
        val v = g.vehicle
        val snap = Snapshot.vehicle(app)
        return JSONObject()
            .put("vehicle", JSONObject().put("name", v.name).put("year", v.year).put("make", v.make).put("model", v.model).put("trim", v.trim)
                .put("engine", v.engine).put("drive", v.drive).put("odometer", v.odometerNow).put("tirePsi", v.tirePsi).put("oil", v.oil)
                .put("dealer", v.dealer).put("insurance", v.insurance))
            .put("due", snap.optJSONArray("due") ?: JSONArray())
            .put("recalls", snap.optJSONArray("recalls") ?: JSONArray())
            .put("services", JSONArray().apply {
                g.services.forEach { r ->
                    put(JSONObject().put("day", r.day).put("miles", r.miles ?: JSONObject.NULL).put("title", r.title).put("cost", r.cost ?: JSONObject.NULL)
                        .put("shop", r.shop).put("notes", r.notes)
                        .put("items", JSONArray(r.items.mapNotNull { id -> g.items.firstOrNull { it.id == id }?.name })))
                }
            })
            .put("fuel", JSONArray().apply {
                g.fuel.takeLast(200).forEach { f ->
                    put(JSONObject().put("time", f.time).put("miles", f.miles ?: JSONObject.NULL).put("gallons", f.gallons).put("cost", f.cost ?: JSONObject.NULL).put("station", f.station))
                }
            })
    }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

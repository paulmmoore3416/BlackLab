package com.blacklab.app.link

import com.blacklab.app.brainstorm.NoteOps
import com.blacklab.app.brainstorm.NoteStyle
import com.blacklab.app.core.Settings
import com.blacklab.app.core.Time
import com.blacklab.app.data.AssignmentEntity
import com.blacklab.app.data.CertificationEntity
import com.blacklab.app.data.CourseEntity
import com.blacklab.app.data.DeckEntity
import com.blacklab.app.data.DocumentEntity
import com.blacklab.app.data.EventCalendarEntity
import com.blacklab.app.data.FlashcardEntity
import com.blacklab.app.data.JournalEntity
import com.blacklab.app.data.NoteEntity
import com.blacklab.app.data.NotebookEntity
import com.blacklab.app.data.Occurrence
import com.blacklab.app.data.PageEntity
import com.blacklab.app.data.RecordingEntity
import com.blacklab.app.data.SectionEntity
import com.blacklab.app.data.TaskEntity
import com.blacklab.app.data.TaskListEntity
import com.blacklab.app.dictation.DictationMeta
import com.blacklab.app.vehicle.Dtcs
import com.blacklab.app.vehicle.Garage
import com.blacklab.app.vehicle.Nhtsa
import com.blacklab.app.vehicle.Telemetry
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * The snapshot's JSON, item by item, from plain values — no Context, no database — so the
 * snapshot's shape is unit tested and exported as desktop/web/tests/snapshot.fixture.json, which
 * the desktop's view tests render against (SnapshotContractTest). [Snapshot.build] gathers the
 * values and calls these.
 */
object SnapshotJson {
    private val NULL = JSONObject.NULL
    private fun dayMillis(epochDay: Long) = Time.millis(LocalDate.ofEpochDay(epochDay))

    /** What [info] reports: the phone, the app and the link (read from Android by [Snapshot.info]). */
    data class InfoInputs(
        val settings: Settings,
        val version: String, val versionCode: Int,
        val device: String, val android: String, val battery: Int, val charging: Boolean,
        val storageFree: Long, val storagePublic: Boolean,
        val rev: Long,
        val profiles: Map<String, String>,
        val nano: String, val nanoModel: String, val elevenCharsUsed: Int,
        val cloudLastSync: Long, val cloudError: String?,
        val keys: Map<String, Boolean>,
        val now: Long,
        val weather: JSONObject?,
    )

    fun info(i: InfoInputs): JSONObject {
        val s = i.settings
        return JSONObject().apply {
            put("app", "BlackLab"); put("version", i.version); put("versionCode", i.versionCode)
            put("device", i.device); put("android", i.android)
            put("battery", i.battery); put("charging", i.charging)
            put("storageFree", i.storageFree); put("storagePublic", i.storagePublic)
            put("rev", i.rev)
            put("activeProfile", s.activeProfile.name)
            put("profiles", JSONObject().apply { i.profiles.forEach { (k, v) -> put(k, v) } })
            put("user", JSONObject().put("name", s.userName).put("title", s.jobTitle).put("org", s.organization))
            put("assistant", JSONObject().put("name", s.asstName).put("nano", i.nano).put("nanoModel", i.nanoModel)
                .put("elevenCharsUsed", i.elevenCharsUsed).put("elevenBudget", s.asstElevenBudget))
            put("google", JSONObject().put("account", s.googleAccount))
            put("cloud", JSONObject().put("enabled", s.cloudSync).put("lastSync", i.cloudLastSync).put("error", i.cloudError ?: NULL))
            put("keys", JSONObject().apply { i.keys.forEach { (k, v) -> put(k, v) } })
            put("time", i.now)
            i.weather?.let { put("weather", it) }
        }
    }

    /** Everything the snapshot is made from, already read from the database and stores. */
    data class Inputs(
        val info: JSONObject,
        val generatedAt: Long,
        val todayEpoch: Long,
        val lists: List<TaskListEntity> = emptyList(),
        val tasks: List<TaskEntity> = emptyList(),
        val events: List<Occurrence> = emptyList(),
        val calendars: Map<Long, EventCalendarEntity> = emptyMap(),
        val courses: List<CourseEntity> = emptyList(),
        val assignments: List<AssignmentEntity> = emptyList(),
        val certifications: List<CertificationEntity> = emptyList(),
        val documents: List<DocumentEntity> = emptyList(),
        val journal: List<JournalEntity> = emptyList(),
        val recordings: List<RecordingEntity> = emptyList(),
        /** profile, meta, path relative to the BlackLab folder */
        val dictations: List<Triple<String, DictationMeta, String>> = emptyList(),
        val vehicle: JSONObject = JSONObject(),
        val brainstorm: JSONObject = JSONObject(),
        val decks: List<DeckEntity> = emptyList(),
        val cards: List<FlashcardEntity> = emptyList(),
        /** name, size, time */
        val backups: List<Triple<String, Long, Long>> = emptyList(),
        val assistant: JSONArray = JSONArray(),
        val facility: JSONObject? = null,
        val timeline: JSONObject? = null,
        val health: JSONObject? = null,
    )

    /** The whole snapshot. Every top-level key BlackLab Desktop can read is put here and nowhere else. */
    fun assemble(i: Inputs, only: Set<String>? = null): JSONObject {
        val listNames = i.lists.associate { it.id to it.name }
        val courseById = i.courses.associateBy { it.id }
        val recentDone = i.generatedAt - 14 * 86_400_000L
        val all = JSONObject().apply {
            put("info", i.info)
            put("generatedAt", i.generatedAt)
            put("lists", JSONArray().apply { i.lists.forEach { put(list(it)) } })
            put("tasks", JSONArray().apply {
                i.tasks.filter { !it.completed || (it.completedAt ?: 0) > recentDone }.forEach { put(task(it, listNames[it.listId] ?: "", i.todayEpoch)) }
            })
            put("events", JSONArray().apply { i.events.sortedBy { it.start }.take(600).forEach { o -> put(event(o, o.event.calendarId?.let { i.calendars[it] })) } })
            put("courses", JSONArray().apply { i.courses.forEach { put(course(it)) } })
            put("assignments", JSONArray().apply {
                i.assignments.filter { it.status != "GRADED" }.sortedBy { it.dueAt ?: Long.MAX_VALUE }.take(80).forEach { put(assignment(it, courseById[it.courseId])) }
            })
            put("certifications", JSONArray().apply { i.certifications.forEach { put(certification(it)) } })
            put("documents", JSONArray().apply { i.documents.sortedByDescending { it.updatedAt }.take(300).forEach { put(document(it)) } })
            put("journal", JSONArray().apply { i.journal.sortedByDescending { it.entryDay }.take(120).forEach { put(journal(it)) } })
            put("recordings", JSONArray().apply { i.recordings.sortedByDescending { it.createdAt }.take(100).forEach { put(recording(it)) } })
            put("dictations", JSONArray().apply { i.dictations.forEach { (p, m, path) -> put(dictation(p, m, path)) } })
            put("vehicle", i.vehicle)
            put("brainstorm", i.brainstorm)
            put("decks", JSONArray().apply {
                val cards = i.cards.groupBy { it.deckId }
                i.decks.sortedBy { it.name.lowercase() }.forEach { d -> put(deck(d, cards[d.id].orEmpty(), d.courseId?.let { courseById[it] }, i.todayEpoch)) }
            })
            put("backups", JSONArray().apply { i.backups.take(14).forEach { (n, s, t) -> put(backup(n, s, t)) } })
            put("assistant", i.assistant)
            // Optional: a failure building one of these never costs the desktop the rest of the snapshot
            i.facility?.let { put("facility", it) }
            i.timeline?.let { put("timeline", it) }
            i.health?.let { put("health", it) }
        }
        if (only == null) return all
        // A partial snapshot: just the sections that changed, plus what every snapshot carries
        return JSONObject().apply { (only + Sections.ALWAYS).forEach { k -> if (all.has(k)) put(k, all.get(k)) } }
    }

    fun list(l: TaskListEntity): JSONObject =
        JSONObject().put("id", l.id).put("profile", l.profile).put("name", l.name).put("emoji", l.emoji).put("color", l.color)

    fun task(t: TaskEntity, listName: String, todayEpoch: Long): JSONObject =
        JSONObject().put("id", t.id).put("profile", t.profile).put("title", t.title).put("notes", t.notes)
            .put("dueDay", t.dueDay?.let { dayMillis(it) } ?: NULL).put("dueMinute", t.dueMinute ?: NULL)
            .put("overdue", t.dueDay != null && t.dueDay < todayEpoch && !t.completed)
            .put("priority", t.priority).put("important", t.important).put("list", listName).put("tags", t.tags)
            .put("completed", t.completed).put("completedAt", t.completedAt ?: NULL).put("repeat", t.repeatRule ?: "")

    fun event(o: Occurrence, cal: EventCalendarEntity?): JSONObject =
        JSONObject().put("id", o.event.id).put("profile", o.event.profile).put("title", o.event.title).put("location", o.event.location)
            .put("start", Time.millis(o.start)).put("end", Time.millis(o.end)).put("allDay", o.event.allDay)
            .put("calendar", cal?.name ?: "").put("color", o.event.color ?: cal?.color ?: 0).put("notes", o.event.notes)

    fun course(c: CourseEntity): JSONObject =
        JSONObject().put("id", c.id).put("profile", c.profile).put("code", c.code).put("title", c.title).put("kind", c.kind)
            .put("status", c.status).put("credits", c.credits).put("instructor", c.instructor).put("color", c.color).put("finalGrade", c.finalGrade)

    private fun courseLabel(c: CourseEntity?) = c?.let { it.code.ifBlank { it.title } } ?: ""

    fun assignment(a: AssignmentEntity, course: CourseEntity?): JSONObject =
        JSONObject().put("id", a.id).put("course", courseLabel(course)).put("profile", course?.profile ?: "")
            .put("title", a.title).put("kind", a.kind).put("dueAt", a.dueAt ?: NULL).put("status", a.status)

    fun certification(c: CertificationEntity): JSONObject =
        JSONObject().put("id", c.id).put("profile", c.profile).put("name", c.name).put("issuer", c.issuer).put("status", c.status)
            .put("examAt", c.examAt ?: NULL).put("expiresDay", c.expiresDay?.let { dayMillis(it) } ?: NULL)
            .put("ceuRequired", c.ceuRequired)

    fun document(d: DocumentEntity): JSONObject =
        JSONObject().put("id", d.id).put("profile", d.profile).put("title", d.title).put("folder", d.folder).put("preview", d.preview)
            .put("words", d.wordCount).put("updatedAt", d.updatedAt).put("path", d.filePath).put("pinned", d.pinned)

    fun journal(j: JournalEntity): JSONObject =
        JSONObject().put("id", j.id).put("profile", j.profile).put("day", dayMillis(j.entryDay))
            .put("title", j.title).put("mood", j.mood).put("preview", j.preview).put("words", j.wordCount).put("favorite", j.favorite)

    fun recording(r: RecordingEntity): JSONObject =
        JSONObject().put("id", r.id).put("profile", r.profile).put("title", r.title).put("durationMs", r.durationMs).put("createdAt", r.createdAt)
            .put("hasTranscript", r.transcript.isNotBlank()).put("path", r.filePath)

    fun dictation(profile: String, m: DictationMeta, path: String): JSONObject =
        JSONObject().put("profile", profile).put("title", m.title).put("startedAt", m.startedAt).put("durationMs", m.durationMs)
            .put("words", m.wordCount).put("path", path)

    fun deck(d: DeckEntity, cards: List<FlashcardEntity>, course: CourseEntity?, todayEpoch: Long): JSONObject =
        JSONObject().put("id", d.id).put("profile", d.profile).put("name", d.name).put("course", courseLabel(course))
            .put("cards", cards.size).put("due", cards.count { it.dueDay <= todayEpoch }).put("learned", cards.count { it.repetitions >= 2 })

    fun backup(name: String, size: Long, time: Long): JSONObject = JSONObject().put("name", name).put("size", size).put("time", time)

    /** The car for the desktop's Garage page: profile, what's due, fuel, trips, live values, codes, recalls. */
    fun vehicle(g: Garage.State, t: Telemetry.State, n: Nhtsa.State, pace: Double?, now: Long): JSONObject {
        val v = g.vehicle
        val stats = Garage.fuelStats(g.fuel)
        val Tm = Telemetry
        return JSONObject().apply {
            put("name", v.name); put("year", v.year); put("make", v.make); put("model", v.model); put("trim", v.trim); put("engine", v.engine); put("drive", v.drive)
            put("vin", v.vin); put("odometer", v.odometerNow); put("odometerEstimated", v.estMiles > 0); put("odometerAt", v.odometerAt); put("tankGallons", v.tankGallons)
            put("milesPerDay", pace ?: NULL)
            put("fuelStats", JSONObject().put("avgMpg", stats.avgMpg ?: NULL).put("lastMpg", stats.lastMpg ?: NULL)
                .put("costPerMile", stats.costPerMile ?: NULL).put("monthCost", stats.monthCost))
            put("due", JSONArray().apply {
                Garage.due(g, milesPerDay = pace).forEach { d ->
                    put(JSONObject().put("name", d.item.name).put("status", d.status.name).put("text", d.describe()).put("milesLeft", d.milesLeft ?: NULL)
                        .put("expected", d.expectedDay?.let { dayMillis(it) } ?: NULL))
                }
            })
            put("fuel", JSONArray().apply { g.fuel.take(40).forEach { f -> put(JSONObject().put("time", f.time).put("miles", f.miles ?: NULL).put("gallons", f.gallons).put("cost", f.cost ?: NULL).put("full", f.full).put("station", f.station)) } })
            put("mpgSeries", JSONArray().apply { Garage.mpgSeries(g.fuel).takeLast(24).forEach { (time, mpg) -> put(JSONArray().put(time).put(mpg)) } })
            put("weekly", JSONArray().apply { Tm.weeklyMiles(t.trips, 12).forEach { (d, b, o) -> put(JSONArray().put(Time.millis(d)).put(b).put(o)) } })
            put("totals", JSONObject().apply {
                listOf("week" to Tm.weekStart(), "month" to Tm.monthStart(), "year" to Tm.yearStart()).forEach { (k, from) ->
                    val x = Tm.totals(t.trips, from, now + 1, t.link.ratePerMile)
                    put(k, JSONObject().put("miles", x.miles).put("trips", x.trips).put("business", x.byPurpose[Telemetry.Purpose.BUSINESS] ?: 0.0).put("deductible", x.deductible))
                }
            })
            put("trips", JSONArray().apply {
                t.trips.filter { it.end != null }.take(60).forEach { tr ->
                    put(JSONObject().put("start", tr.start).put("end", tr.end).put("miles", tr.miles).put("from", tr.startPlace).put("to", tr.endPlace).put("purpose", tr.purpose.label)
                        .put("mpg", tr.mpg(v.tankGallons) ?: NULL).put("sim", tr.simulated).put("note", tr.note))
                }
            })
            put("live", JSONArray().apply {
                t.snapshot.values.sortedBy { m -> Tm.DEFS.indexOfFirst { it.key == m.key } }.forEach { m ->
                    val d = Tm.def(m.key)
                    put(JSONObject().put("key", m.key).put("label", d?.label ?: m.key).put("group", d?.group?.label ?: "").put("value", Tm.format(m))
                        .put("num", m.num ?: NULL).put("source", m.source.label).put("time", m.time).put("alert", Tm.isAlert(m)))
                }
            })
            put("codes", JSONArray().apply { t.activeDtcs.forEach { d -> put(JSONObject().put("code", d.code).put("kind", d.kind).put("text", Dtcs.describe(d.code)).put("since", d.firstSeen)) } })
            t.parked?.let { p -> put("parked", JSONObject().put("place", p.place).put("time", p.time)) }
            t.pendingFill?.let { f -> put("pendingFill", JSONObject().put("gallons", f.gallons).put("from", f.fromPct).put("to", f.toPct).put("time", f.time)) }
            put("lastSession", t.lastSession)
            put("recalls", JSONArray().apply {
                n.recalls.forEach { r -> put(JSONObject().put("campaign", r.campaign).put("date", r.date).put("component", r.component).put("summary", r.summary)
                    .put("remedy", r.remedy).put("resolved", r.campaign in n.resolved).put("parkIt", r.parkIt || r.parkOutside)) }
            })
            put("recallsCheckedAt", n.checkedAt)
            put("specs", JSONArray().apply { n.decoded?.specs?.forEach { (k, value) -> put(JSONArray().put(k).put(value)) } })
        }
    }

    /**
     * Brainstorm for the desktop: notebooks › sections › pages (title, preview, tagged lines and a
     * text excerpt) and Keep-style notes. Locked sections share only page titles; trashed notes and
     * deleted pages are left out, and note places are never shared.
     */
    fun brainstorm(notebooks: List<NotebookEntity>, allSections: List<SectionEntity>, allPages: List<PageEntity>, allNotes: List<NoteEntity>): JSONObject {
        val sections = allSections.groupBy { it.notebookId }
        val pages = allPages.filter { it.deletedAt == null }.groupBy { it.sectionId }
        fun hex(c: Long) = if (c == 0L) "" else "#%06X".format(c and 0xFFFFFF)
        return JSONObject().apply {
            put("notebooks", JSONArray().apply {
                notebooks.sortedWith(compareBy({ it.profile }, { it.sortOrder }, { it.createdAt })).forEach { nb ->
                    put(JSONObject().put("id", nb.id).put("profile", nb.profile).put("name", nb.name).put("emoji", nb.emoji).put("color", nb.color).put("updatedAt", nb.updatedAt)
                        .put("sections", JSONArray().apply {
                            sections[nb.id].orEmpty().sortedBy { it.sortOrder }.forEach { s ->
                                put(JSONObject().put("id", s.id).put("name", s.name).put("color", s.color).put("group", s.groupName).put("locked", s.locked)
                                    .put("pages", JSONArray().apply {
                                        pages[s.id].orEmpty().sortedBy { it.sortOrder }.take(300).forEach { p -> put(page(p, s.locked)) }
                                    }))
                            }
                        }))
                }
            })
            put("notes", JSONArray().apply {
                allNotes.filter { it.trashedAt == null }.sortedWith(compareByDescending<NoteEntity> { it.pinned }.thenByDescending { it.sortOrder }.thenByDescending { it.updatedAt })
                    .take(400).forEach { n ->
                        val col = NoteStyle.colors.getOrNull(n.color)
                        put(JSONObject().put("id", n.id).put("profile", n.profile).put("title", n.title).put("body", n.body.take(4000)).put("checklist", n.checklist)
                            .put("items", JSONArray().apply { NoteOps.items(n).forEach { put(JSONObject().put("t", it.text).put("d", it.done).put("i", it.indent)) } })
                            .put("light", hex(col?.light ?: 0)).put("dark", hex(col?.dark ?: 0)).put("pinned", n.pinned).put("archived", n.archived)
                            .put("labels", JSONArray(NoteOps.labels(n))).put("reminderAt", n.reminderAt ?: NULL)
                            .put("attachments", NoteOps.attachments(n).size).put("updatedAt", n.updatedAt))
                    }
            })
        }
    }

    private fun page(p: PageEntity, locked: Boolean): JSONObject =
        JSONObject().put("id", p.id).put("title", p.title).put("level", p.level).put("pinned", p.pinned).put("words", p.wordCount)
            .put("updatedAt", p.updatedAt).put("reminderAt", p.reminderAt ?: NULL)
            .put("preview", if (locked) "" else p.preview)
            .put("text", if (locked) "" else p.searchText.take(1500))
            .put("tags", JSONArray().apply {
                if (!locked) p.tagIndex.lines().filter { it.isNotBlank() }.take(40).forEach { l ->
                    val parts = l.split('|', limit = 3)
                    if (parts.size == 3) put(JSONObject().put("tag", parts[0]).put("done", parts[1] == "1" || parts[1] == "true").put("text", parts[2]))
                }
            })
}

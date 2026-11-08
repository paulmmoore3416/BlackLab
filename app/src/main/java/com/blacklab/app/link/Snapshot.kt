package com.blacklab.app.link

import android.os.BatteryManager
import android.os.Build
import com.blacklab.app.AppContainer
import com.blacklab.app.BuildConfig
import com.blacklab.app.assistant.Nano
import com.blacklab.app.assistant.Secrets
import com.blacklab.app.assistant.toPublicJson
import com.blacklab.app.core.Folder
import com.blacklab.app.core.Profile
import com.blacklab.app.core.Time
import com.blacklab.app.data.CalendarLogic
import com.blacklab.app.dictation.DictationStore
import org.json.JSONArray
import org.json.JSONObject

/** The data BlackLab Desktop shows: a JSON picture of both accounts at a point in time. */
object Snapshot {

    fun info(app: AppContainer): JSONObject {
        val s = app.settings.current
        val bm = app.context.getSystemService(BatteryManager::class.java)
        return JSONObject().apply {
            put("app", "BlackLab"); put("version", BuildConfig.VERSION_NAME); put("versionCode", BuildConfig.VERSION_CODE)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}"); put("android", Build.VERSION.RELEASE)
            put("battery", bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1)
            put("charging", bm?.isCharging ?: false)
            put("storageFree", app.storage.freeBytes()); put("storagePublic", app.storage.isPublic)
            put("rev", app.link.rev.get())
            put("activeProfile", s.activeProfile.name)
            put("profiles", JSONObject().put("PERSONAL", app.settings.profileName(Profile.PERSONAL)).put("PROFESSIONAL", app.settings.profileName(Profile.PROFESSIONAL)))
            put("user", JSONObject().put("name", s.userName).put("title", s.jobTitle).put("org", s.organization))
            put("assistant", JSONObject().put("name", s.asstName).put("nano", Nano.status.value.name).put("nanoModel", Nano.modelName)
                .put("elevenCharsUsed", app.speaker.charsUsedThisMonth()).put("elevenBudget", s.asstElevenBudget))
            put("google", JSONObject().put("account", s.googleAccount))
            put("keys", JSONObject().apply { Secrets.all.forEach { put(it, app.secrets.has(it)) } })
            put("time", Time.now())
            com.blacklab.app.assistant.Weather.report.value?.let { put("weather", com.blacklab.app.assistant.Weather.toJson(it)) }
        }
    }

    /** The car for the desktop's Garage page: profile, what's due, fuel, trips, live values, codes, recalls. */
    fun vehicle(app: AppContainer): JSONObject = runCatching {
        val ctx = app.context
        val vehicleGarage = com.blacklab.app.vehicle.Garage
        val g = vehicleGarage.load(ctx)
        val t = com.blacklab.app.vehicle.VehicleLink.load(ctx)
        val n = com.blacklab.app.vehicle.Nhtsa.load(ctx)
        val pace = com.blacklab.app.vehicle.ServiceReminders.pace(ctx)
        val v = g.vehicle
        val stats = vehicleGarage.fuelStats(g.fuel)
        val Tm = com.blacklab.app.vehicle.Telemetry
        JSONObject().apply {
            put("name", v.name); put("year", v.year); put("make", v.make); put("model", v.model); put("trim", v.trim); put("engine", v.engine); put("drive", v.drive)
            put("vin", v.vin); put("odometer", v.odometerNow); put("odometerEstimated", v.estMiles > 0); put("odometerAt", v.odometerAt); put("tankGallons", v.tankGallons)
            put("milesPerDay", pace ?: JSONObject.NULL)
            put("fuelStats", JSONObject().put("avgMpg", stats.avgMpg ?: JSONObject.NULL).put("lastMpg", stats.lastMpg ?: JSONObject.NULL)
                .put("costPerMile", stats.costPerMile ?: JSONObject.NULL).put("monthCost", stats.monthCost))
            put("due", JSONArray().apply {
                vehicleGarage.due(g, milesPerDay = pace).forEach { d ->
                    put(JSONObject().put("name", d.item.name).put("status", d.status.name).put("text", d.describe()).put("milesLeft", d.milesLeft ?: JSONObject.NULL)
                        .put("expected", d.expectedDay?.let { Time.millis(java.time.LocalDate.ofEpochDay(it)) } ?: JSONObject.NULL))
                }
            })
            put("fuel", JSONArray().apply { g.fuel.take(40).forEach { f -> put(JSONObject().put("time", f.time).put("miles", f.miles ?: JSONObject.NULL).put("gallons", f.gallons).put("cost", f.cost ?: JSONObject.NULL).put("full", f.full).put("station", f.station)) } })
            put("mpgSeries", JSONArray().apply { vehicleGarage.mpgSeries(g.fuel).takeLast(24).forEach { (time, mpg) -> put(JSONArray().put(time).put(mpg)) } })
            put("weekly", JSONArray().apply { Tm.weeklyMiles(t.trips, 12).forEach { (d, b, o) -> put(JSONArray().put(Time.millis(d)).put(b).put(o)) } })
            val now = System.currentTimeMillis()
            put("totals", JSONObject().apply {
                listOf("week" to Tm.weekStart(), "month" to Tm.monthStart(), "year" to Tm.yearStart()).forEach { (k, from) ->
                    val x = Tm.totals(t.trips, from, now + 1, t.link.ratePerMile)
                    put(k, JSONObject().put("miles", x.miles).put("trips", x.trips).put("business", x.byPurpose[com.blacklab.app.vehicle.Telemetry.Purpose.BUSINESS] ?: 0.0).put("deductible", x.deductible))
                }
            })
            put("trips", JSONArray().apply {
                t.trips.filter { it.end != null }.take(60).forEach { tr ->
                    put(JSONObject().put("start", tr.start).put("end", tr.end).put("miles", tr.miles).put("from", tr.startPlace).put("to", tr.endPlace).put("purpose", tr.purpose.label)
                        .put("mpg", tr.mpg(v.tankGallons) ?: JSONObject.NULL).put("sim", tr.simulated).put("note", tr.note))
                }
            })
            put("live", JSONArray().apply {
                t.snapshot.values.sortedBy { m -> Tm.DEFS.indexOfFirst { it.key == m.key } }.forEach { m ->
                    val d = Tm.def(m.key)
                    put(JSONObject().put("key", m.key).put("label", d?.label ?: m.key).put("group", d?.group?.label ?: "").put("value", Tm.format(m))
                        .put("num", m.num ?: JSONObject.NULL).put("source", m.source.label).put("time", m.time).put("alert", Tm.isAlert(m)))
                }
            })
            put("codes", JSONArray().apply { t.activeDtcs.forEach { d -> put(JSONObject().put("code", d.code).put("kind", d.kind).put("text", com.blacklab.app.vehicle.Dtcs.describe(d.code)).put("since", d.firstSeen)) } })
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
    }.getOrElse { JSONObject().put("error", it.message ?: "unavailable") }

    /**
     * Brainstorm for the desktop: notebooks › sections › pages (title, preview, tagged lines and a
     * text excerpt) and Keep-style notes. Locked sections share only page titles; trashed notes and
     * deleted pages are left out, and note places are never shared.
     */
    suspend fun brainstorm(app: AppContainer): JSONObject {
        val b = app.db.brainstorm()
        val sections = b.allSections().groupBy { it.notebookId }
        val pages = b.allPages().filter { it.deletedAt == null }.groupBy { it.sectionId }
        fun hex(c: Long) = if (c == 0L) "" else "#%06X".format(c and 0xFFFFFF)
        return JSONObject().apply {
            put("notebooks", JSONArray().apply {
                b.allNotebooks().sortedWith(compareBy({ it.profile }, { it.sortOrder }, { it.createdAt })).forEach { nb ->
                    put(JSONObject().put("id", nb.id).put("profile", nb.profile).put("name", nb.name).put("emoji", nb.emoji).put("color", nb.color).put("updatedAt", nb.updatedAt)
                        .put("sections", JSONArray().apply {
                            sections[nb.id].orEmpty().sortedBy { it.sortOrder }.forEach { s ->
                                put(JSONObject().put("id", s.id).put("name", s.name).put("color", s.color).put("group", s.groupName).put("locked", s.locked)
                                    .put("pages", JSONArray().apply {
                                        pages[s.id].orEmpty().sortedBy { it.sortOrder }.take(300).forEach { p ->
                                            put(JSONObject().put("id", p.id).put("title", p.title).put("level", p.level).put("pinned", p.pinned).put("words", p.wordCount)
                                                .put("updatedAt", p.updatedAt).put("reminderAt", p.reminderAt ?: JSONObject.NULL)
                                                .put("preview", if (s.locked) "" else p.preview)
                                                .put("text", if (s.locked) "" else p.searchText.take(1500))
                                                .put("tags", JSONArray().apply {
                                                    if (!s.locked) p.tagIndex.lines().filter { it.isNotBlank() }.take(40).forEach { l ->
                                                        val parts = l.split('|', limit = 3)
                                                        if (parts.size == 3) put(JSONObject().put("tag", parts[0]).put("done", parts[1] == "1" || parts[1] == "true").put("text", parts[2]))
                                                    }
                                                }))
                                        }
                                    }))
                            }
                        }))
                }
            })
            put("notes", JSONArray().apply {
                app.db.notes().all().filter { it.trashedAt == null }.sortedWith(compareByDescending<com.blacklab.app.data.NoteEntity> { it.pinned }.thenByDescending { it.sortOrder }.thenByDescending { it.updatedAt })
                    .take(400).forEach { n ->
                        val col = com.blacklab.app.brainstorm.NoteStyle.colors.getOrNull(n.color)
                        put(JSONObject().put("id", n.id).put("profile", n.profile).put("title", n.title).put("body", n.body.take(4000)).put("checklist", n.checklist)
                            .put("items", JSONArray().apply { com.blacklab.app.brainstorm.NoteOps.items(n).forEach { put(JSONObject().put("t", it.text).put("d", it.done).put("i", it.indent)) } })
                            .put("light", hex(col?.light ?: 0)).put("dark", hex(col?.dark ?: 0)).put("pinned", n.pinned).put("archived", n.archived)
                            .put("labels", JSONArray(com.blacklab.app.brainstorm.NoteOps.labels(n))).put("reminderAt", n.reminderAt ?: JSONObject.NULL)
                            .put("attachments", com.blacklab.app.brainstorm.NoteOps.attachments(n).size).put("updatedAt", n.updatedAt))
                    }
            })
        }
    }

    suspend fun build(app: AppContainer): JSONObject {
        val db = app.db
        val today = Time.today()
        val todayEpoch = today.toEpochDay()
        val lists = Profile.entries.flatMap { db.tasks().lists(it.name) }
        val listNames = lists.associate { it.id to it.name }
        val cals = db.events().allCalendars().associateBy { it.id }
        val tasks = db.tasks().all().filter { !it.completed || (it.completedAt ?: 0) > Time.now() - 14 * 86_400_000L }
        val events = db.events().all().flatMap { CalendarLogic.expand(it, today.minusDays(7), today.plusDays(60)) }.sortedBy { it.start }
        val edu = db.education()
        val courses = edu.allCourses()
        val courseById = courses.associateBy { it.id }
        return JSONObject().apply {
            put("info", info(app))
            put("generatedAt", Time.now())
            put("lists", JSONArray().apply { lists.forEach { put(JSONObject().put("id", it.id).put("profile", it.profile).put("name", it.name).put("emoji", it.emoji).put("color", it.color)) } })
            put("tasks", JSONArray().apply {
                tasks.forEach { t ->
                    put(JSONObject().put("id", t.id).put("profile", t.profile).put("title", t.title).put("notes", t.notes)
                        .put("dueDay", t.dueDay?.let { Time.millis(java.time.LocalDate.ofEpochDay(it)) } ?: JSONObject.NULL).put("dueMinute", t.dueMinute ?: JSONObject.NULL)
                        .put("overdue", t.dueDay != null && t.dueDay < todayEpoch && !t.completed)
                        .put("priority", t.priority).put("important", t.important).put("list", listNames[t.listId] ?: "").put("tags", t.tags)
                        .put("completed", t.completed).put("completedAt", t.completedAt ?: JSONObject.NULL).put("repeat", t.repeatRule ?: ""))
                }
            })
            put("events", JSONArray().apply {
                events.take(600).forEach { o ->
                    val c = o.event.calendarId?.let { cals[it] }
                    put(JSONObject().put("id", o.event.id).put("profile", o.event.profile).put("title", o.event.title).put("location", o.event.location)
                        .put("start", Time.millis(o.start)).put("end", Time.millis(o.end)).put("allDay", o.event.allDay)
                        .put("calendar", c?.name ?: "").put("color", o.event.color ?: c?.color ?: 0).put("notes", o.event.notes))
                }
            })
            put("courses", JSONArray().apply {
                courses.forEach { c -> put(JSONObject().put("id", c.id).put("profile", c.profile).put("code", c.code).put("title", c.title).put("kind", c.kind)
                    .put("status", c.status).put("credits", c.credits).put("instructor", c.instructor).put("color", c.color).put("finalGrade", c.finalGrade)) }
            })
            put("assignments", JSONArray().apply {
                edu.allAssignments().filter { it.status != "GRADED" }.sortedBy { it.dueAt ?: Long.MAX_VALUE }.take(80).forEach { a ->
                    put(JSONObject().put("id", a.id).put("course", courseById[a.courseId]?.let { it.code.ifBlank { it.title } } ?: "").put("profile", courseById[a.courseId]?.profile ?: "")
                        .put("title", a.title).put("kind", a.kind).put("dueAt", a.dueAt ?: JSONObject.NULL).put("status", a.status))
                }
            })
            put("certifications", JSONArray().apply {
                edu.allCerts().forEach { c -> put(JSONObject().put("id", c.id).put("profile", c.profile).put("name", c.name).put("issuer", c.issuer).put("status", c.status)
                    .put("examAt", c.examAt ?: JSONObject.NULL).put("expiresDay", c.expiresDay?.let { Time.millis(java.time.LocalDate.ofEpochDay(it)) } ?: JSONObject.NULL)
                    .put("ceuRequired", c.ceuRequired)) }
            })
            put("documents", JSONArray().apply {
                db.documents().all().sortedByDescending { it.updatedAt }.take(300).forEach { d ->
                    put(JSONObject().put("id", d.id).put("profile", d.profile).put("title", d.title).put("folder", d.folder).put("preview", d.preview)
                        .put("words", d.wordCount).put("updatedAt", d.updatedAt).put("path", d.filePath).put("pinned", d.pinned))
                }
            })
            put("journal", JSONArray().apply {
                Profile.entries.flatMap { db.journal().allFor(it.name) }.sortedByDescending { it.entryDay }.take(120).forEach { j ->
                    put(JSONObject().put("id", j.id).put("profile", j.profile).put("day", Time.millis(java.time.LocalDate.ofEpochDay(j.entryDay)))
                        .put("title", j.title).put("mood", j.mood).put("preview", j.preview).put("words", j.wordCount).put("favorite", j.favorite))
                }
            })
            put("recordings", JSONArray().apply {
                Profile.entries.flatMap { db.recordings().allFor(it.name) }.sortedByDescending { it.createdAt }.take(100).forEach { r ->
                    put(JSONObject().put("id", r.id).put("profile", r.profile).put("title", r.title).put("durationMs", r.durationMs).put("createdAt", r.createdAt)
                        .put("hasTranscript", r.transcript.isNotBlank()).put("path", r.filePath))
                }
            })
            put("dictations", JSONArray().apply {
                Profile.entries.forEach { p ->
                    DictationStore.list(app.storage.dir(p, Folder.DICTATION)).take(60).forEach { (folder, m) ->
                        put(JSONObject().put("profile", p.name).put("title", m.title).put("startedAt", m.startedAt).put("durationMs", m.durationMs)
                            .put("words", m.wordCount).put("path", app.storage.relative(folder)))
                    }
                }
            })
            put("vehicle", vehicle(app))
            put("brainstorm", brainstorm(app))
            put("decks", JSONArray().apply {
                val cards = edu.allCards().groupBy { it.deckId }
                edu.allDecks().sortedBy { it.name.lowercase() }.forEach { d ->
                    val c = cards[d.id].orEmpty()
                    put(JSONObject().put("id", d.id).put("profile", d.profile).put("name", d.name)
                        .put("course", d.courseId?.let { courseById[it] }?.let { it.code.ifBlank { it.title } } ?: "")
                        .put("cards", c.size).put("due", c.count { it.dueDay <= todayEpoch }).put("learned", c.count { it.repetitions >= 2 }))
                }
            })
            put("backups", JSONArray().apply { app.backups.listBackups().take(14).forEach { put(JSONObject().put("name", it.name).put("size", it.length()).put("time", it.lastModified())) } })
            put("assistant", JSONArray().apply { app.assistant.messages.value.takeLast(60).forEach { put(it.toPublicJson()) } })
        }
    }
}

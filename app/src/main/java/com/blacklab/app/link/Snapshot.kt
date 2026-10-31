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
            put("backups", JSONArray().apply { app.backups.listBackups().take(14).forEach { put(JSONObject().put("name", it.name).put("size", it.length()).put("time", it.lastModified())) } })
            put("assistant", JSONArray().apply { app.assistant.messages.value.takeLast(60).forEach { put(it.toPublicJson()) } })
        }
    }
}

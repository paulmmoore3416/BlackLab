package com.blacklab.app.link

import android.os.BatteryManager
import android.os.Build
import com.blacklab.app.AppContainer
import com.blacklab.app.BuildConfig
import com.blacklab.app.assistant.Nano
import com.blacklab.app.assistant.Secrets
import com.blacklab.app.assistant.toPublicJson
import com.blacklab.app.core.DayTimeline
import com.blacklab.app.core.Folder
import com.blacklab.app.core.Health
import com.blacklab.app.core.Profile
import com.blacklab.app.core.Time
import com.blacklab.app.data.CalendarLogic
import com.blacklab.app.dictation.DictationStore
import org.json.JSONArray
import org.json.JSONObject

/** The data BlackLab Desktop shows: a JSON picture of both accounts at a point in time. */
object Snapshot {

    fun info(app: AppContainer): JSONObject {
        val bm = app.context.getSystemService(BatteryManager::class.java)
        return SnapshotJson.info(SnapshotJson.InfoInputs(
            settings = app.settings.current,
            version = BuildConfig.VERSION_NAME, versionCode = BuildConfig.VERSION_CODE,
            device = "${Build.MANUFACTURER} ${Build.MODEL}", android = Build.VERSION.RELEASE,
            battery = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1, charging = bm?.isCharging ?: false,
            storageFree = app.storage.freeBytes(), storagePublic = app.storage.isPublic,
            rev = app.link.rev.get(),
            profiles = mapOf(Profile.PERSONAL.name to app.settings.profileName(Profile.PERSONAL), Profile.PROFESSIONAL.name to app.settings.profileName(Profile.PROFESSIONAL)),
            nano = Nano.status.value.name, nanoModel = Nano.modelName, elevenCharsUsed = app.speaker.charsUsedThisMonth(),
            cloudLastSync = CloudSync.lastSync.value, cloudError = CloudSync.lastError.value,
            keys = Secrets.all.associateWith { app.secrets.has(it) },
            now = Time.now(),
            weather = com.blacklab.app.assistant.Weather.report.value?.let { com.blacklab.app.assistant.Weather.toJson(it) },
        ))
    }

    /** The car for the desktop's Garage page (see [SnapshotJson.vehicle]). */
    fun vehicle(app: AppContainer): JSONObject = runCatching {
        val ctx = app.context
        SnapshotJson.vehicle(
            com.blacklab.app.vehicle.Garage.load(ctx), com.blacklab.app.vehicle.VehicleLink.load(ctx), com.blacklab.app.vehicle.Nhtsa.load(ctx),
            com.blacklab.app.vehicle.ServiceReminders.pace(ctx), System.currentTimeMillis(),
        )
    }.getOrElse { JSONObject().put("error", it.message ?: "unavailable") }

    /** Brainstorm for the desktop (see [SnapshotJson.brainstorm]). */
    suspend fun brainstorm(app: AppContainer): JSONObject {
        val b = app.db.brainstorm()
        return SnapshotJson.brainstorm(b.allNotebooks(), b.allSections(), b.allPages(), app.db.notes().all())
    }

    /** The snapshot, or only the sections in [only] (plus [Sections.ALWAYS]) — nothing else is read from the database. */
    suspend fun build(app: AppContainer, only: Set<String>? = null): JSONObject {
        val db = app.db
        val today = Time.today()
        val todayEpoch = today.toEpochDay()
        val edu = db.education()
        fun want(vararg k: String) = only == null || k.any { it in only }
        return SnapshotJson.assemble(SnapshotJson.Inputs(
            info = info(app),
            generatedAt = Time.now(),
            todayEpoch = todayEpoch,
            lists = if (want("lists", "tasks")) Profile.entries.flatMap { db.tasks().lists(it.name) } else emptyList(),
            tasks = if (want("tasks")) db.tasks().all() else emptyList(),
            events = if (want("events")) db.events().all().flatMap { CalendarLogic.expand(it, today.minusDays(7), today.plusDays(60)) } else emptyList(),
            calendars = if (want("events")) db.events().allCalendars().associateBy { it.id } else emptyMap(),
            courses = if (want("courses", "assignments", "decks")) edu.allCourses() else emptyList(),
            assignments = if (want("assignments")) edu.allAssignments() else emptyList(),
            certifications = if (want("certifications")) edu.allCerts() else emptyList(),
            documents = if (want("documents")) db.documents().all() else emptyList(),
            journal = if (want("journal")) Profile.entries.flatMap { db.journal().allFor(it.name) } else emptyList(),
            recordings = if (want("recordings")) Profile.entries.flatMap { db.recordings().allFor(it.name) } else emptyList(),
            dictations = if (!want("dictations")) emptyList() else Profile.entries.flatMap { p ->
                DictationStore.list(app.storage.dir(p, Folder.DICTATION)).take(60).map { (folder, m) -> Triple(p.name, m, app.storage.relative(folder)) }
            },
            vehicle = if (want("vehicle")) vehicle(app) else JSONObject(),
            brainstorm = if (want("brainstorm")) brainstorm(app) else JSONObject(),
            decks = if (want("decks")) edu.allDecks() else emptyList(),
            cards = if (want("decks")) edu.allCards() else emptyList(),
            backups = if (want("backups")) app.backups.listBackups().map { Triple(it.name, it.length(), it.lastModified()) } else emptyList(),
            assistant = if (!want("assistant")) JSONArray() else JSONArray().apply { app.assistant.messages.value.takeLast(60).forEach { put(it.toPublicJson()) } },
            facility = if (!want("facility")) null else runCatching { DesktopSections.facility(com.blacklab.app.facility.Facility.load(app.context), todayEpoch) }.getOrNull(),
            timeline = if (!want("timeline")) null else runCatching {
                val i = com.blacklab.app.ui.home.DayTimelineData.inputs(app, today)
                DesktopSections.timeline(today, DayTimeline.build(i), com.blacklab.app.ui.home.DayTimelineData.handoff(app, today))
            }.getOrNull(),
            health = runCatching { DesktopSections.health(Health.evaluate(com.blacklab.app.ui.settings.HealthData.gather(app, ensureIndex = false))) }.getOrNull(),
        ), only)
    }
}

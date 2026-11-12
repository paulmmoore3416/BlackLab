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

    suspend fun build(app: AppContainer): JSONObject {
        val db = app.db
        val today = Time.today()
        val todayEpoch = today.toEpochDay()
        val edu = db.education()
        return SnapshotJson.assemble(SnapshotJson.Inputs(
            info = info(app),
            generatedAt = Time.now(),
            todayEpoch = todayEpoch,
            lists = Profile.entries.flatMap { db.tasks().lists(it.name) },
            tasks = db.tasks().all(),
            events = db.events().all().flatMap { CalendarLogic.expand(it, today.minusDays(7), today.plusDays(60)) },
            calendars = db.events().allCalendars().associateBy { it.id },
            courses = edu.allCourses(),
            assignments = edu.allAssignments(),
            certifications = edu.allCerts(),
            documents = db.documents().all(),
            journal = Profile.entries.flatMap { db.journal().allFor(it.name) },
            recordings = Profile.entries.flatMap { db.recordings().allFor(it.name) },
            dictations = Profile.entries.flatMap { p ->
                DictationStore.list(app.storage.dir(p, Folder.DICTATION)).take(60).map { (folder, m) -> Triple(p.name, m, app.storage.relative(folder)) }
            },
            vehicle = vehicle(app),
            brainstorm = brainstorm(app),
            decks = edu.allDecks(),
            cards = edu.allCards(),
            backups = app.backups.listBackups().map { Triple(it.name, it.length(), it.lastModified()) },
            assistant = JSONArray().apply { app.assistant.messages.value.takeLast(60).forEach { put(it.toPublicJson()) } },
            facility = runCatching { DesktopSections.facility(com.blacklab.app.facility.Facility.load(app.context), todayEpoch) }.getOrNull(),
            timeline = runCatching {
                val i = com.blacklab.app.ui.home.DayTimelineData.inputs(app, today)
                DesktopSections.timeline(today, DayTimeline.build(i), com.blacklab.app.ui.home.DayTimelineData.handoff(app, today))
            }.getOrNull(),
            health = runCatching { DesktopSections.health(Health.evaluate(com.blacklab.app.ui.settings.HealthData.gather(app, ensureIndex = false))) }.getOrNull(),
        ))
    }
}

package com.blacklab.app.link

import com.blacklab.app.core.DayTimeline
import com.blacklab.app.core.Health
import com.blacklab.app.core.Settings
import com.blacklab.app.data.AssignmentEntity
import com.blacklab.app.data.CertificationEntity
import com.blacklab.app.data.CourseEntity
import com.blacklab.app.data.DeckEntity
import com.blacklab.app.data.DocumentEntity
import com.blacklab.app.data.EventCalendarEntity
import com.blacklab.app.data.EventEntity
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
import com.blacklab.app.facility.Facility
import com.blacklab.app.vehicle.Garage
import com.blacklab.app.vehicle.Nhtsa
import com.blacklab.app.vehicle.Telemetry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The phone ↔ desktop contract. Builds a snapshot from sample data with the same code the phone
 * uses ([SnapshotJson.assemble]) and compares its shape (every key path and value type) with
 * desktop/web/tests/snapshot.fixture.json, which desktop/web/tests/views.test.mjs renders every
 * desktop view against. When the snapshot changes on purpose, regenerate the fixture and run the
 * desktop tests:
 *   BL_EXPORT=1 ./gradlew :app:testDebugUnitTest --tests com.blacklab.app.link.SnapshotContractTest
 *   node --test desktop/web/tests/
 * Sample data only — made-up names, a fake VIN, no coordinates.
 */
class SnapshotContractTest {
    private val root = listOf(File("../desktop/web"), File("desktop/web")).first { it.isDirectory }
    private val out = File(root, "tests/snapshot.fixture.json")

    private val today = LocalDate.of(2026, 11, 10)
    private val day = today.toEpochDay()
    private val now = LocalDateTime.of(2026, 11, 10, 12, 0).atZone(java.time.ZoneId.of("America/Chicago")).toInstant().toEpochMilli()
    private val hour = 3_600_000L

    fun sample(): JSONObject {
        val course = CourseEntity(id = 1, profile = "PROFESSIONAL", code = "FM-201", title = "Facility Systems", color = 0xFF3366CC.toInt())
        val list = TaskListEntity(id = 1, profile = "PROFESSIONAL", name = "Work orders", color = 0xFF2E7D32.toInt(), emoji = "🛠️")
        val cal = EventCalendarEntity(id = 1, profile = "PROFESSIONAL", name = "Work", color = 0xFF1565C0.toInt())
        val ev = EventEntity(id = 1, profile = "PROFESSIONAL", calendarId = 1, title = "Safety huddle", location = "Room 1", start = now + hour, end = now + 2 * hour, notes = "Agenda")
        val section = SectionEntity(id = 1, notebookId = 1, name = "Plans", color = 0xFF6A1B9A.toInt())
        val g = Garage.State(
            vehicle = Garage.Vehicle(nickname = "Test car", vin = "TESTVIN0000000000", odometer = 12_000, odometerAt = now - 24 * hour),
            fuel = listOf(
                Garage.Fuel("f2", now - 24 * hour, 12_000, 15.0, 52.5, station = "Station B"),
                Garage.Fuel("f1", now - 240 * hour, 11_620, 15.5, 54.0, station = "Station A"),
            ),
        )
        val t = Telemetry.State(
            snapshot = mapOf("fuel_pct" to Telemetry.Metric("fuel_pct", 62.0, null, Telemetry.Source.OBD, now)),
            trips = listOf(Telemetry.Trip(id = "t1", start = now - 3 * hour, end = now - 2 * hour, startPlace = "Home", endPlace = "Work", gpsMiles = 18.2, purpose = Telemetry.Purpose.BUSINESS, note = "Site visit")),
            dtcs = listOf(Telemetry.Dtc("P0420", "stored", now - 48 * hour, now)),
            parked = Telemetry.Parked(0.0, 0.0, "Work lot", now - 2 * hour, Telemetry.Source.PHONE),
            lastSession = now - 2 * hour,
            pendingFill = Telemetry.PendingFill(now - hour, 20.0, 95.0, 14.1, 12_010),
        )
        val n = Nhtsa.State(
            recalls = listOf(Nhtsa.Recall("26V001000", "01/02/2026", "ELECTRICAL", "A sample recall summary.", "Sample consequence.", "Dealers will fix it.")),
            checkedAt = now - 24 * hour,
        )
        val facility = Facility.State(
            site = "Sample campus",
            assets = listOf(Facility.Asset(id = "gen", name = "Generator 1", kind = "Generator", tag = "GEN-1", location = "Yard",
                pms = listOf(Facility.Pm(id = "load", title = "Monthly load test", everyDays = 30, lastDay = day - 40)))),
            log = listOf(Facility.Entry(id = "e1", assetId = "gen", pmId = "load", day = day - 2, kind = Facility.Kind.PM, result = Facility.Result.PASS, text = "ok", by = "J")),
        )
        val info = SnapshotJson.info(SnapshotJson.InfoInputs(
            settings = Settings(userName = "Sample User", jobTitle = "Director", organization = "Sample Org"),
            version = "0.0.0", versionCode = 1, device = "Google Pixel", android = "17", battery = 80, charging = false,
            storageFree = 64_000_000_000, storagePublic = true, rev = 42,
            profiles = mapOf("PERSONAL" to "Personal", "PROFESSIONAL" to "Work"),
            nano = "READY", nanoModel = "nano", elevenCharsUsed = 1200, cloudLastSync = now - hour, cloudError = null,
            keys = com.blacklab.app.assistant.Secrets.all.associateWith { it == "elevenlabs" }, now = now,
            weather = null,
        ))
        return SnapshotJson.assemble(SnapshotJson.Inputs(
            info = info,
            generatedAt = now,
            todayEpoch = day,
            lists = listOf(list),
            tasks = listOf(
                TaskEntity(id = 1, profile = "PROFESSIONAL", listId = 1, title = "WO P2: Leak in room 214", notes = "Ceiling tile", dueDay = day - 1, dueMinute = 540, priority = 3, important = true, tags = "wo", repeatRule = "WEEKLY"),
                TaskEntity(id = 2, profile = "PERSONAL", title = "Renew registration", completed = true, completedAt = now - hour),
            ),
            events = listOf(Occurrence(ev, LocalDateTime.of(2026, 11, 10, 13, 0), LocalDateTime.of(2026, 11, 10, 14, 0))),
            calendars = mapOf(1L to cal),
            courses = listOf(course),
            assignments = listOf(AssignmentEntity(id = 1, courseId = 1, title = "Case study", dueAt = now + 72 * hour)),
            certifications = listOf(CertificationEntity(id = 1, profile = "PROFESSIONAL", name = "CHFM", issuer = "AHA", examAt = now + 900 * hour, expiresDay = day + 60)),
            documents = listOf(DocumentEntity(id = 1, profile = "PROFESSIONAL", title = "EOC plan", folder = "Plans", preview = "Environment of care…", wordCount = 840, filePath = "Professional/Documents/eoc.md", updatedAt = now - hour)),
            journal = listOf(JournalEntity(id = 1, profile = "PERSONAL", entryDay = day, title = "A good day", mood = 4, preview = "Walked…", wordCount = 120, favorite = true)),
            recordings = listOf(RecordingEntity(id = 1, profile = "PROFESSIONAL", filePath = "Professional/Recordings/r1.m4a", title = "Huddle", durationMs = 600_000, createdAt = now - hour, transcript = "Notes")),
            dictations = listOf(Triple("PERSONAL", DictationMeta("Idea", now - hour, durationMs = 30_000), "Personal/Dictation/d1")),
            vehicle = SnapshotJson.vehicle(g, t, n, pace = 32.0, now = now),
            brainstorm = SnapshotJson.brainstorm(
                listOf(NotebookEntity(id = 1, profile = "PROFESSIONAL", name = "Projects", color = 0xFF00897B.toInt(), emoji = "📓")),
                listOf(section),
                listOf(PageEntity(id = 1, profile = "PROFESSIONAL", sectionId = 1, title = "Chiller swap", preview = "Phase 1…", searchText = "Phase 1 plan", tagIndex = "todo|0|Call vendor", wordCount = 50)),
                listOf(NoteEntity(id = 1, profile = "PERSONAL", title = "Groceries", body = "", checklist = true, items = """[{"t":"Milk","d":false,"i":0}]""", labels = ",home,", pinned = true)),
            ),
            decks = listOf(DeckEntity(id = 1, profile = "PROFESSIONAL", name = "NFPA 99", courseId = 1)),
            cards = listOf(FlashcardEntity(id = 1, deckId = 1, front = "Q", back = "A", dueDay = day)),
            backups = listOf(Triple("blacklab-2026-11-10.blbackup", 1_048_576L, now - hour)),
            assistant = JSONArray().put(JSONObject().put("role", "assistant").put("text", "Good morning").put("time", now)),
            facility = DesktopSections.facility(facility, day),
            timeline = DesktopSections.timeline(today, listOf(DayTimeline.Item(now - hour, DayTimeline.Kind.WORK_ORDER, "WO P2: Leak", "room 214", "task/1")), "Ops handoff — sample"),
            health = DesktopSections.health(listOf(Health.Check("backup", "Backups", Health.Level.WARN, "Last backup 9 days ago", "Open backups", "settings"))),
        ))
    }

    /** Every key path with its value type; arrays merge their items' shapes under `[]`. */
    private fun shape(v: Any?, path: String = "", acc: MutableSet<String> = sortedSetOf()): Set<String> {
        when (v) {
            is JSONObject -> { acc += "$path:object"; v.keys().forEach { shape(v.get(it), "$path.$it", acc) } }
            is JSONArray -> { acc += "$path:array"; for (i in 0 until v.length()) shape(v.get(i), "$path[]", acc) }
            JSONObject.NULL, null -> acc += "$path:null"
            is String -> acc += "$path:string"
            is Boolean -> acc += "$path:boolean"
            is Number -> acc += "$path:number"
            else -> acc += "$path:${v.javaClass.simpleName}"
        }
        return acc
    }

    @Test fun fixtureMatchesWhatThePhoneSends() {
        val snap = sample()
        if (System.getenv("BL_EXPORT") == "1") { out.parentFile.mkdirs(); out.writeText(snap.toString(2) + "\n") }
        assertTrue("Missing ${out.path} — generate it with BL_EXPORT=1 (see the class comment)", out.isFile)
        val want = shape(JSONObject(out.readText()))
        val got = shape(snap)
        assertEquals("The snapshot's shape changed. Regenerate the fixture with BL_EXPORT=1, then run `node --test desktop/web/tests/`.\n" +
            "added: ${got - want}\nremoved: ${want - got}", want, got)
    }

    @Test fun sampleCoversEveryTopLevelSection() {
        val keys = sample().keys().asSequence().toSet()
        listOf("info", "tasks", "events", "vehicle", "brainstorm", "decks", "facility", "timeline", "health").forEach { assertTrue("missing $it", it in keys) }
        // a section with no items would hide its field names from the contract
        keys.forEach { k -> (sample().get(k) as? JSONArray)?.let { assertTrue("sample has no $k items", it.length() > 0) } }
    }

    @Test fun fixtureCarriesNoCoordinatesOrRealVin() {
        val text = sample().toString()
        assertFalse(text.contains("\"lat\"") || text.contains("\"lon\""))
        assertTrue(text.contains("TESTVIN0000000000"))
    }
}

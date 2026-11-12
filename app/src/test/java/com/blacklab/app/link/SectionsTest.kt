package com.blacklab.app.link

import com.blacklab.app.core.JsonStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SectionsTest {
    private val src = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }

    /** A new table must say which desktop sections it feeds (or why it feeds none), or the desktop goes stale. */
    @Test fun everyRoomTableIsMappedOrDeliberatelyIgnored() {
        val tables = Regex("""tableName = "([a-z_]+)"""").findAll(File(src, "com/blacklab/app/data/Entities.kt").readText()).map { it.groupValues[1] }.toSet()
        assertEquals(tables, Sections.TABLES.keys + Sections.IGNORED_TABLES)
        assertTrue((Sections.TABLES.keys intersect Sections.IGNORED_TABLES).isEmpty())
    }

    @Test fun everyJsonStoreFeedsASection() {
        LinkWatch.stores().forEach { assertTrue("${it.name} feeds nothing", Sections.forStore(it.name).isNotEmpty()) }
    }

    @Test fun sectionsAreRealSnapshotKeys() {
        val keys = SnapshotJson.assemble(SnapshotJson.Inputs(info = JSONObject(), generatedAt = 0, todayEpoch = 0,
            facility = JSONObject(), timeline = JSONObject(), health = JSONObject())).keys().asSequence().toSet()
        val used = Sections.TABLES.values.flatten() + LinkWatch.stores().flatMap { Sections.forStore(it.name) } + Sections.SETTINGS + Sections.ASSISTANT
        (used - Sections.PSEUDO).forEach { assertTrue("$it is not a snapshot key", it in keys) }
    }

    @Test fun aTaskChangeAlsoRefreshesTheTimeline() {
        assertEquals(setOf("tasks", "timeline"), Sections.forTables(setOf("tasks")))
        assertTrue("facility" in Sections.forStore("Facility") && "timeline" in Sections.forStore("Facility"))
    }

    @Test fun revisionsReportOnlyWhatChangedSinceTheClientsRev() {
        val r = SectionRevs(baseline = 10)
        r.mark(setOf("tasks", "timeline"), 11)
        r.mark(setOf("vehicle"), 12)
        assertEquals(setOf("tasks", "timeline", "vehicle"), r.changedSince(10))
        assertEquals(setOf("vehicle"), r.changedSince(11))
        assertEquals(emptySet<String>(), r.changedSince(12))
    }

    @Test fun aClientOlderThanThisProcessGetsEverything() {
        val r = SectionRevs(baseline = 10)
        r.mark(setOf("tasks"), 11)
        assertNull("rev from before the app started: the client must reload in full", r.changedSince(9))
        assertNull(r.changedSince(0))
    }

    @Test fun assembleCanBuildOnlySomeSections() {
        val i = SnapshotJson.Inputs(info = JSONObject().put("rev", 5), generatedAt = 1, todayEpoch = 0, facility = JSONObject().put("site", "x"))
        val o = SnapshotJson.assemble(i, only = setOf("facility"))
        assertEquals(setOf("info", "generatedAt", "facility"), o.keys().asSequence().toSet())
    }

    @Test fun parsesTheSectionsQuery() {
        assertNull(Sections.parse(null))
        assertNull(Sections.parse(""))
        assertEquals(setOf("tasks", "vehicle"), Sections.parse("tasks, vehicle,"))
    }

    @Suppress("unused") private fun touch(s: JsonStore<*>) = s.name
}

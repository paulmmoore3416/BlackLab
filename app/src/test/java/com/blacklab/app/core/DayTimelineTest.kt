package com.blacklab.app.core

import com.blacklab.app.data.TaskEntity
import com.blacklab.app.facility.Facility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DayTimelineTest {
    private val zone = ZoneId.of("America/Chicago")
    private val day = LocalDate.of(2026, 11, 9)
    private fun at(h: Int, m: Int = 0) = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private val facility = Facility.State(
        assets = listOf(Facility.Asset(id = "gen", name = "Generator 1", kind = "Generator", pms = listOf(Facility.Pm(id = "load", title = "Monthly load test", everyDays = 30)))),
        log = listOf(Facility.Entry(assetId = "gen", pmId = "load", day = day.toEpochDay(), kind = Facility.Kind.PM, result = Facility.Result.PASS, text = "45 min at 40%", by = "J", time = at(9, 30))),
    )
    private val tasks = listOf(
        TaskEntity(id = 1, profile = "PROFESSIONAL", title = "WO P2: Leak under sink, room 214", tags = "facility,work-order", priority = 3, createdAt = at(10), dueDay = day.toEpochDay()),
        TaskEntity(id = 2, profile = "PROFESSIONAL", title = "Send vendor COIs", completed = true, completedAt = at(15), createdAt = at(8) - 86_400_000L),
        TaskEntity(id = 3, profile = "PROFESSIONAL", title = "WO P3: Ceiling tile office 12", tags = "work-order", priority = 2, createdAt = at(8) - 3 * 86_400_000L, dueDay = day.toEpochDay() - 1),
    )
    private val inputs = DayTimeline.Inputs(day, zone, tasks = tasks, events = listOf(DayTimeline.Event(at(13), "EOC committee", "Conf B")), facility = facility,
        bh = DayTimeline.BhCounts(checks = 32, eocRounds = 1, eocIssues = 2))

    @Test fun mergesTheDayInOrder() {
        val items = DayTimeline.build(inputs)
        assertEquals(listOf(DayTimeline.Kind.INSPECTION, DayTimeline.Kind.WORK_ORDER, DayTimeline.Kind.EVENT, DayTimeline.Kind.TASK_DONE, DayTimeline.Kind.BH), items.map { it.kind })
        assertEquals("Monthly load test · Generator 1", items.first().title)
        assertTrue(items.last().detail.contains("32 observation checks"))
        assertEquals("BH carries counts only, never people", "32 observation checks · 1 environmental round (2 issues)", items.last().detail)
    }

    @Test fun handoffListsOpenWorkOrdersAndNeverBh() {
        val items = DayTimeline.build(inputs)
        val h = DayTimeline.handoff(day, zone, items, tasks, emptyList(), listOf(DayTimeline.Event(at(8) + 86_400_000L, "Fire drill, 2nd shift")), "Paul")
        assertTrue(h, h.startsWith("Ops handoff — Monday, Nov 9, 2026 · Paul"))
        assertTrue(h.contains("Open work orders (2)"))
        assertTrue(h.indexOf("Leak under sink") < h.indexOf("Ceiling tile"))
        assertTrue(h.contains("Ceiling tile office 12 — OVERDUE"))
        assertTrue(h.contains("Tomorrow") && h.contains("Fire drill"))
        assertFalse(h.contains("observation") || h.contains("Behavioral"))
    }
}

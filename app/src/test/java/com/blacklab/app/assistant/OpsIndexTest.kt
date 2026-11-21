package com.blacklab.app.assistant

import com.blacklab.app.facility.Facility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class OpsIndexTest {
    private val day = LocalDate.of(2026, 10, 14).toEpochDay()

    private val facility = Facility.State(
        assets = listOf(
            Facility.Asset(id = "ahu2", name = "AHU-2", kind = "HVAC", tag = "FAC-0042", location = "B-wing mechanical room",
                make = "Trane", model = "M-Series", serial = "T12345",
                pms = listOf(Facility.Pm(id = "filters", title = "Filter change", everyDays = 90, standard = "ASHRAE 52.2", lastDay = day))),
        ),
        log = listOf(
            Facility.Entry(assetId = "ahu2", pmId = "filters", day = day, kind = Facility.Kind.PM, result = Facility.Result.PASS, text = "MERV 13 installed", by = "J. Rivera"),
            Facility.Entry(assetId = "ahu2", day = day + 3, kind = Facility.Kind.WORK_ORDER, text = "Belt squeal on start-up"),
        ),
    )

    private fun index(): Pair<List<LocalIndex.Passage>, (String) -> List<LocalIndex.Passage>> {
        val passages = LocalIndex.opsPassages(facility)
        // Same BM25 the app uses, over just these passages
        val search = { q: String ->
            val terms = LocalIndex.tokens(q).toSet()
            passages.map { p -> p to LocalIndex.tokens(p.title + " " + p.text).count { it in terms } }
                .filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
        }
        return passages to search
    }

    @Test fun assetsAndLogBecomePassages() {
        val (passages, _) = index()
        assertEquals(listOf("Facility asset", "Facility log", "Facility log"), passages.map { it.kind })
        assertTrue(passages.all { it.route.startsWith("facility/asset/ahu2") })
        assertTrue(passages.first().text.contains("FAC-0042"))
        assertTrue(passages.first { it.kind == "Facility log" && it.text.contains("Filter change") }.text.contains("J. Rivera"))
    }

    @Test fun questionsFindTheRightRecord() {
        val (_, search) = index()
        assertEquals("Facility log", search("when was the AHU-2 filter change inspection done").first().kind)
        assertTrue(search("belt squeal").first().text.contains("Belt squeal"))
    }
}

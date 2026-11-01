package com.blacklab.app.vehicle

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class GarageTest {
    private val today = LocalDate.of(2026, 11, 1)

    private fun state(odo: Int, vararg done: Pair<String, Pair<Int?, LocalDate?>>, severe: Boolean = false): Garage.State {
        val items = Garage.defaultSchedule().map { i -> done.firstOrNull { it.first == i.id }?.second?.let { (m, d) -> i.copy(lastMiles = m, lastDay = d?.toEpochDay()) } ?: i }
        return Garage.State(Garage.Vehicle(odometer = odo, severeService = severe), items)
    }

    @Test fun oilChangeDueByMilesOrMonths() {
        val s = state(31_000, "oil" to (24_000 to today.minusMonths(5)))
        val oil = Garage.due(s, today).first { it.item.id == "oil" }
        assertEquals(31_500, oil.dueMiles)
        assertEquals(500, oil.milesLeft)
        assertEquals(Garage.Status.DUE_SOON, oil.status)

        val overdueByTime = Garage.due(state(25_000, "oil" to (24_000 to today.minusMonths(13))), today).first { it.item.id == "oil" }
        assertEquals(Garage.Status.OVERDUE, overdueByTime.status)
    }

    @Test fun severeServiceUsesShorterIntervals() {
        val s = state(28_000, "oil" to (24_000 to today.minusMonths(2)), severe = true)
        assertEquals(Garage.Status.OVERDUE, Garage.due(s, today).first { it.item.id == "oil" }.status)
    }

    @Test fun neverLoggedItemsCountFromNewOnlyInsideFirstInterval() {
        val young = Garage.due(state(5_000), today).first { it.item.id == "oil" }
        assertEquals(2_500, young.milesLeft)
        val older = Garage.due(state(40_000), today).first { it.item.id == "oil" }
        assertNull(older.milesLeft)
        assertEquals(Garage.Status.UNKNOWN, older.status)
    }

    @Test fun mpgBetweenFullFillUps() {
        val f = listOf(
            Garage.Fuel("a", 1, 30_000, 15.0, 50.0, true),
            Garage.Fuel("b", 2, 30_200, 6.0, 20.0, false),
            Garage.Fuel("c", 3, 30_450, 9.0, 30.0, true),
            Garage.Fuel("d", 4, 30_800, 14.0, 46.0, true),
        )
        val st = Garage.fuelStats(f, now = 5)
        assertEquals(25.0, st.lastMpg!!, 0.01)          // 350 mi / 14 gal
        assertEquals(27.586, st.avgMpg!!, 0.01)         // 800 mi / 29 gal
        assertNotNull(st.costPerMile)
    }

    @Test fun parsesSpokenFuelLogs() {
        val f = Garage.parseFuel("log fuel 13.2 gallons for 48.75 at 31,250 miles at Costco")!!
        assertEquals(13.2, f.gallons, 0.001)
        assertEquals(48.75, f.cost!!, 0.001)
        assertEquals(31_250, f.miles)
        assertEquals("Costco", f.station)
        assertTrue(f.full)
        val g = Garage.parseFuel("partial fill 6 gallons $22 odometer 31400")!!
        assertEquals(22.0, g.cost!!, 0.001)
        assertEquals(31_400, g.miles)
        assertTrue(!g.full)
        assertNull(Garage.parseFuel("filled up for 40 bucks"))
    }

    @Test fun matchesServiceItemsFromSpeech() {
        val items = Garage.defaultSchedule()
        assertEquals(setOf("oil", "rotate"), Garage.matchItems("oil change and tire rotation at 31,000", items).toSet())
        assertEquals(listOf("cabin"), Garage.matchItems("replaced the cabin air filter", items))
        assertEquals(listOf("brakefluid"), Garage.matchItems("brake fluid flush", items))
    }

    @Test fun jsonRoundTrip() {
        val s = Garage.State(Garage.Vehicle(trim = "Limited", odometer = 31_000, vin = "5NMS"), Garage.defaultSchedule(),
            listOf(Garage.ServiceRecord("r", 20_000, 30_000, listOf("oil"), "Oil", 79.0, "Dealer", "")),
            listOf(Garage.Fuel("f", 10, 30_900, 14.1, null, true, "Shell")))
        val back = Garage.fromJson(JSONObject(Garage.toJson(s).toString()))
        assertEquals(s, back)
    }
}

package com.blacklab.app.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherTest {
    private val t0 = 1_800_000_000_000L
    private fun slot(h: Int, temp: Double, gust: Double = 5.0, rain: Double = 0.0, snow: Double = 0.0, id: Int = 800) =
        Weather.Slot(t0 + h * 3_600_000L, temp, temp, gust, rain, snow, id, "")
    private fun report(vararg s: Weather.Slot) = Weather.Report(Weather.Now(50.0, 50.0, "Clear", "", 5.0, 50, "Town"), s.toList(), t0)

    @Test fun mildWeatherRaisesNothing() {
        assertTrue(Weather.alerts(report(slot(3, 55.0), slot(6, 61.0), slot(9, 48.0))).isEmpty())
    }

    @Test fun freezeAndHardFreezeAreDistinct() {
        assertEquals(listOf("FREEZE"), Weather.alerts(report(slot(3, 40.0), slot(12, 30.0))).map { it.kind })
        val hard = Weather.alerts(report(slot(3, 25.0), slot(12, 14.0)))
        assertEquals(listOf("HARD_FREEZE"), hard.map { it.kind })
        assertTrue(hard.first().checklist.any { it.contains("heat trace", true) })
    }

    @Test fun stormWindRainSnowAndHeat() {
        val kinds = Weather.alerts(report(slot(3, 97.0, gust = 45.0, rain = 15.0, id = 211), slot(6, 90.0, rain = 15.0), slot(9, 80.0, snow = 0.0))).map { it.kind }.toSet()
        assertEquals(setOf("HEAT", "STORM", "WIND", "RAIN"), kinds)
        assertEquals(listOf("SNOW"), Weather.alerts(report(slot(3, 34.0, snow = 8.0), slot(6, 33.0, snow = 8.0))).map { it.kind })
    }

    @Test fun onlyTheWindowCounts() {
        assertTrue(Weather.alerts(report(slot(3, 50.0), slot(60, 10.0)), hours = 48).isEmpty())
    }

    @Test fun parsesOpenWeatherMapJson() {
        val cur = JSONObject("""{"name":"Northampton","main":{"temp":41.2,"feels_like":36.0,"humidity":70},"wind":{"speed":9.2},"weather":[{"description":"light rain","icon":"10d"}]}""")
        val fc = JSONObject().put("list", JSONArray()
            .put(JSONObject("""{"dt":1800003600,"main":{"temp":38.0,"feels_like":33.0},"wind":{"speed":10,"gust":22},"rain":{"3h":2.5},"weather":[{"id":500,"description":"light rain"}]}"""))
            .put(JSONObject("""{"dt":1800014400,"main":{"temp":29.0,"feels_like":22.0},"wind":{"speed":8},"weather":[{"id":600,"description":"light snow"}],"snow":{"3h":4.0}}""")))
        val r = Weather.parse(cur, fc, t0)
        assertEquals("Light rain", r.now.condition)
        assertEquals("Northampton", r.now.place)
        assertEquals(2, r.slots.size)
        assertEquals(22.0, r.slots[0].gustMph, 0.01)
        assertEquals(8.0, r.slots[1].gustMph, 0.01)
        assertEquals(29.0, r.lowF, 0.01)
        assertTrue(Weather.summary(r).startsWith("Northampton: 41°F and light rain, feels like 36°"))
    }
}

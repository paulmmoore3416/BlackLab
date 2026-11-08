package com.blacklab.app.vehicle

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** 5.8: simulator, NHTSA parsing, fill-up detection, trip rules, predicted service dates, charts data, car widget. */
class CarFeaturesTest {
    private val zone = ZoneId.of("America/Chicago")
    private val day = 86_400_000L

    // ------------------------------------------------------------ simulator

    @Test fun simulatorAnswersLikeAnElm327AndNeverReportsVinOrOdometer() = runBlocking {
        var now = 0L
        val port = SimObdPort(withCode = true) { now }
        assertTrue(port.send("ATZ").contains("ELM327"))
        val supported = Obd.supported(port.send("0100"), 0x00) + Obd.supported(port.send("0120"), 0x20) + Obd.supported(port.send("0140"), 0x40)
        assertTrue(0x0C in supported && 0x0D in supported && 0x10 in supported && 0x2F in supported && 0x42 in supported)
        assertFalse(0xA6 in supported)
        assertTrue(Obd.isNoData(port.send("0902")))
        assertNull(Obd.response(port.send("01A6"), 0x01, 0xA6))
        now = 60_000 // cruising in town
        val mph = Obd.decode(0x0D, Obd.response(port.send("010D"), 0x01, 0x0D)!!)!!
        assertTrue("mph $mph", mph in 24.0..40.0)
        val rpm = Obd.decode(0x0C, Obd.response(port.send("010C"), 0x01, 0x0C)!!)!!
        assertTrue(rpm > 1000)
        assertEquals(listOf("P0420"), Obd.dtcs(port.send("03"), 0x03))
        val (mil, n) = Obd.monitor(Obd.response(port.send("0101"), 0x01, 0x01)!!)!!
        assertTrue(mil); assertEquals(1, n)
        assertEquals(14.1, Obd.voltage(port.send("ATRV"))!!, 0.2)
    }

    @Test fun simulatedDriveCoversAboutTwoMilesAndEndsStopped() {
        var miles = 0.0
        var t = 0.0
        while (t < SimCar.DURATION_S) { miles += SimCar.mph(t) / 3600.0; t += 1.0 }
        assertTrue("miles $miles", miles in 1.5..3.0)
        assertEquals(0.0, SimCar.mph(SimCar.DURATION_S), 0.0)
    }

    @Test fun simulatedTripsStayOutOfTotalsAndExports() {
        val real = Telemetry.Trip("a", 1000, 2000, gpsMiles = 10.0)
        val sim = Telemetry.Trip("b", 1500, 2500, gpsMiles = 3.0, simulated = true)
        assertEquals(10.0, Telemetry.totals(listOf(real, sim), 0, 10_000, 0.7).miles, 0.001)
        assertEquals(2, Telemetry.tripsCsv(listOf(real, sim)).trim().lines().size) // header + the real trip
    }

    // ------------------------------------------------------------ NHTSA

    /** A made-up VIN (check digit not real) shaped like vPIC's DecodeVinValues reply. */
    private val decodeJson = JSONObject("""{"Results":[{"VIN":"5XYZZ00X0PH000001","ModelYear":"2023","Make":"HYUNDAI","Model":"Santa Fe","Trim":"SEL",
        "DisplacementL":"2.5","EngineCylinders":"4","EngineModel":"GDI THETA III","EngineHP":"191.3","Turbo":"","ElectrificationLevel":"","DriveType":"4x2",
        "TransmissionSpeeds":"8","TransmissionStyle":"Automatic","FuelTypePrimary":"Gasoline","Seats":"5","SeatRows":"2","TPMS":"Direct",
        "PlantCity":"MONTGOMERY","PlantState":"ALABAMA","Note":"Curb Weight: 3,650 to 4,227 lbs"}]}""")

    @Test fun decodesVinIntoProfileFieldsAndSpecs() {
        val d = Nhtsa.parseDecode("5XYZZ00X0PH000001", decodeJson)
        assertEquals(2023, d.year); assertEquals("Hyundai", d.make); assertEquals("SEL", d.trim)
        assertEquals(Garage.ENGINES[0], d.engine)
        assertEquals("FWD", d.drive)
        val specs = d.specs.toMap()
        assertEquals("8-speed automatic", specs["Transmission"])
        assertEquals("Montgomery, Alabama", specs["Built in"])
        assertTrue(specs["Engine"]!!.contains("191 hp"))
        assertEquals("3,650 to 4,227 lbs", specs["Curb weight"])
    }

    @Test fun parsesRecallsNewestFirst() {
        val j = JSONObject("""{"Count":2,"results":[
            {"NHTSACampaignNumber":"22V100000","ReportReceivedDate":"01/02/2022","Component":"AIR BAGS","Summary":"a","Consequence":"b","Remedy":"c","parkIt":false,"parkOutSide":false},
            {"NHTSACampaignNumber":"23V181000","ReportReceivedDate":"17/03/2023","Component":"TRAILER HITCHES","Summary":"x","Consequence":"y","Remedy":"z","parkIt":false,"parkOutSide":true}]}""")
        val r = Nhtsa.parseRecalls(j)
        assertEquals(listOf("23V181000", "22V100000"), r.map { it.campaign })
        assertTrue(r[0].parkOutside)
    }

    // ------------------------------------------------------------ fill-ups

    @Test fun detectsFillUpsButNotGaugeWobble() {
        assertEquals(11.3, Telemetry.detectFill(22.0, 82.0, 18.8)!!, 0.05)
        assertNull(Telemetry.detectFill(40.0, 46.0, 18.8)) // wobble / source difference
        assertNull(Telemetry.detectFill(60.0, 40.0, 18.8)) // driving
        assertNull(Telemetry.detectFill(70.0, 86.0, 10.0)) // 1.6 gal: a top-off, not a fill
        assertNull(Telemetry.detectFill(null, 80.0, 18.8))
    }

    @Test fun pendingFillAndRulesSurviveJson() {
        val s = Telemetry.State(
            link = Telemetry.Link(rules = listOf(Telemetry.Rule("r1", "Work", 32.37, -86.30, 300.0, Telemetry.Purpose.COMMUTE, weekdaysOnly = true)), detectFills = false),
            trips = listOf(Telemetry.Trip("t", 1, 2, simulated = true, rule = "Work")),
            pendingFill = Telemetry.PendingFill(5, 20.0, 90.0, 13.2, 39_550, "Shell"),
        )
        val back = Telemetry.fromJson(Telemetry.toJson(s))
        assertEquals(s.link.rules, back.link.rules)
        assertFalse(back.link.detectFills)
        assertEquals(s.pendingFill, back.pendingFill)
        assertTrue(back.trips[0].simulated); assertEquals("Work", back.trips[0].rule)
    }

    // ------------------------------------------------------------ trip rules

    @Test fun rulesTagTripsByPlaceAndWeekday() {
        val work = Telemetry.Rule("w", "Work", 32.3668, -86.3000, 250.0, Telemetry.Purpose.COMMUTE, weekdaysOnly = true)
        val client = Telemetry.Rule("c", "Client", 32.4000, -86.2500, 250.0, Telemetry.Purpose.BUSINESS)
        val monday = LocalDate.of(2026, 11, 2).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        val saturday = LocalDate.of(2026, 11, 7).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        fun trip(start: Long, lat: Double, lon: Double) = Telemetry.Trip("x", start, start + 600_000, startLat = 32.30, startLon = -86.40, endLat = lat, endLon = lon)
        assertEquals("Work", Telemetry.matchRule(trip(monday, 32.3670, -86.3002), listOf(work, client), zone)?.name)
        assertNull(Telemetry.matchRule(trip(saturday, 32.3670, -86.3002), listOf(work), zone))
        assertEquals("Client", Telemetry.matchRule(trip(saturday, 32.4001, -86.2501), listOf(work, client), zone)?.name)
        assertNull(Telemetry.matchRule(trip(monday, 32.38, -86.30), listOf(work), zone)) // ~1.5 km away
        assertEquals(1500.0, Telemetry.distanceM(32.3668, -86.30, 32.3803, -86.30), 30.0)
    }

    // ------------------------------------------------------------ predicted service dates

    @Test fun milesPerDayComesFromTripsThenFillUps() {
        val now = LocalDate.of(2026, 11, 7).atStartOfDay(zone).toInstant().toEpochMilli()
        val trips = (1..14).map { (now - it * day) to 30.0 }
        assertEquals(30.0, Garage.milesPerDay(emptyList(), trips, now)!!, 0.01)
        val fuel = listOf(Garage.Fuel("a", now - 30 * day, 39_000, 12.0, null), Garage.Fuel("b", now, 39_900, 12.0, null))
        assertEquals(30.0, Garage.milesPerDay(fuel, emptyList(), now)!!, 0.01)
        assertNull(Garage.milesPerDay(emptyList(), trips.take(2), now))
    }

    @Test fun predictsWhenMilesRunOut() {
        val today = LocalDate.of(2026, 11, 7)
        val s = Garage.State(Garage.Vehicle(odometer = 39_550), items = listOf(Garage.ServiceItem("oil", "Engine oil & filter", 7_500, 12, lastMiles = 33_000, lastDay = today.minusMonths(5).toEpochDay())))
        val d = Garage.due(s, today, milesPerDay = 40.0).single()
        assertEquals(950, d.milesLeft)
        assertEquals(today.plusDays(24).toEpochDay(), d.predictedDay) // 950 / 40 ≈ 24 days
        assertEquals(d.predictedDay, d.expectedDay)               // before the 12-month date
        assertTrue(d.describe().contains("around Dec 1"))
        assertEquals(listOf(d), ServiceReminders.upcoming(listOf(d), today.plusDays(20)))
        assertTrue(ServiceReminders.upcoming(listOf(d), today).isEmpty())
        // a yearly-miles estimate in the profile works too
        val y = Garage.due(s.copy(vehicle = s.vehicle.copy(milesPerYear = 14_600)), today).single()
        assertEquals(today.plusDays(24).toEpochDay(), y.predictedDay)
    }

    // ------------------------------------------------------------ chart data

    @Test fun weeklyMilesSplitsBusinessAndSkipsSimulated() {
        val today = LocalDate.of(2026, 11, 7) // Saturday; weeks start Sunday
        val sun = LocalDate.of(2026, 11, 1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val trips = listOf(
            Telemetry.Trip("a", sun, sun + 1, gpsMiles = 12.0, purpose = Telemetry.Purpose.BUSINESS),
            Telemetry.Trip("b", sun + day, sun + day + 1, gpsMiles = 8.0),
            Telemetry.Trip("c", sun + day, sun + day + 1, gpsMiles = 99.0, simulated = true),
        )
        val w = Telemetry.weeklyMiles(trips, 4, today, zone)
        assertEquals(4, w.size)
        assertEquals(LocalDate.of(2026, 11, 1), w.last().first)
        assertEquals(12.0, w.last().second, 0.01); assertEquals(8.0, w.last().third, 0.01)
        assertEquals(0.0, w.first().second + w.first().third, 0.0)
    }

    @Test fun mpgSeriesAndMonthlySpend() {
        val t0 = LocalDate.of(2026, 9, 10).atStartOfDay(zone).toInstant().toEpochMilli()
        val fuel = listOf(
            Garage.Fuel("1", t0, 38_000, 15.0, 50.0),
            Garage.Fuel("2", t0 + 20 * day, 38_300, 12.0, 40.0),
            Garage.Fuel("3", t0 + 40 * day, 38_640, 14.0, 45.0, full = false),
            Garage.Fuel("4", t0 + 50 * day, 38_900, 10.0, 33.0),
        )
        val mpg = Garage.mpgSeries(fuel)
        assertEquals(2, mpg.size)
        assertEquals(25.0, mpg[0].second, 0.01)       // 300 mi / 12 gal
        assertEquals(600.0 / 24.0, mpg[1].second, 0.01) // partial rolls into the next full tank
        val spend = Garage.monthlySpend(fuel, 3, LocalDate.of(2026, 11, 7), zone)
        assertEquals(listOf(90.0, 78.0, 0.0), spend.map { it.second }) // the 4th fill lands on Oct 30
    }

    // ------------------------------------------------------------ car widget

    @Test fun carWidgetModelHidesThePlaceWhenPrivate() {
        val now = 10 * day
        val g = Garage.State(Garage.Vehicle(odometer = 39_550))
        val t = Telemetry.State(
            snapshot = mapOf("fuel_pct" to Telemetry.Metric("fuel_pct", 62.0, null, Telemetry.Source.BLUELINK, now), "locked" to Telemetry.Metric("locked", 0.0, null, Telemetry.Source.BLUELINK, now)),
            parked = Telemetry.Parked(1.0, 2.0, "Main St, Montgomery", now - 600_000, Telemetry.Source.PHONE),
        )
        val shown = com.blacklab.app.widgets.CarLogic.model(g, t, open = 1, driving = false, tripMiles = 0.0, hide = false, now = now)
        assertEquals("Parked · Main St · 10 min ago", shown.status)
        assertEquals("62% fuel", shown.fuel)
        assertEquals("Unlocked · 39,550 mi", shown.detail)
        assertEquals("1 open recall to check", shown.alert)
        val hidden = com.blacklab.app.widgets.CarLogic.model(g, t, 1, false, 0.0, hide = true, now = now)
        assertEquals("Parked", hidden.status)
        assertTrue(hidden.hidden)
        assertNotNull(hidden.fuel)
    }
}

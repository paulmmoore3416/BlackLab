package com.blacklab.app.vehicle

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdTest {
    @Test fun parsesSingleFramePids() {
        assertEquals(1726.0, Obd.decode(0x0C, Obd.response("410C1AF8\r\r>", 0x01, 0x0C)!!)!!, 0.01) // 0x1AF8 / 4
        assertEquals(Telemetry.kmToMi(100.0), Obd.decode(0x0D, Obd.response("41 0D 64", 0x01, 0x0D)!!)!!, 0.01)
        assertEquals(Telemetry.cToF(90.0), Obd.decode(0x05, intArrayOf(130))!!, 0.01) // 130 - 40 = 90 °C
        assertEquals(12.6, Obd.decode(0x42, intArrayOf(0x31, 0x38))!!, 0.001)
        assertEquals(50.2, Obd.decode(0x2F, intArrayOf(128))!!, 0.1)
        assertNull(Obd.response("NO DATA", 0x01, 0x0C))
        assertTrue(Obd.isNoData("SEARCHING...\rNO DATA\r\r>"))
        assertFalse(Obd.isNoData("SEARCHING...\r4100BE3EB811\r>"))
    }

    @Test fun parsesSupportedPidBitmapAcrossEcus() {
        val raw = "4100BE3EB811\r4100 98 18 80 01\r"
        val s = Obd.supported(raw, 0x00)
        assertTrue(0x01 in s); assertTrue(0x0C in s); assertTrue(0x0D in s); assertTrue(0x20 in s)
        assertFalse(0x02 in s)
    }

    @Test fun parsesCanVin() {
        val raw = "014\r0:490201314D38\r1:4A334341324E55\r2:31323334353637\r\r>"
        // bytes after 49 02 01: "1M8J3CA2NU1234567"
        assertEquals("1M8J3CA2NU1234567", Obd.vin(raw))
    }

    @Test fun parsesDtcsCanAndLegacy() {
        assertEquals(listOf("P0133", "P0144"), Obd.dtcs("430201330144", 0x03))
        assertEquals(listOf("P0420"), Obd.dtcs("43 04 20 00 00 00 00", 0x03))
        assertEquals(emptyList<String>(), Obd.dtcs("4300", 0x03))
        assertEquals("C0035", Obd.dtc(0x40, 0x35))
        assertEquals("U0100", Obd.dtc(0xC1, 0x00))
        assertEquals("B1602", Obd.dtc(0x96, 0x02))
        // multi-frame CAN reply with 4 codes
        assertEquals(listOf("P0301", "P0302", "P0303", "P0304"), Obd.dtcs("00A\r0:43040301030203\r1:03030400000000\r", 0x03))
    }

    @Test fun monitorAndVoltage() {
        assertEquals(true to 2, Obd.monitor(intArrayOf(0x82, 0, 0, 0)))
        assertEquals(false to 0, Obd.monitor(intArrayOf(0x00)))
        assertEquals(12.4, Obd.voltage("12.4V")!!, 0.001)
        assertNull(Obd.voltage("?"))
    }

    @Test fun describesCodes() {
        assertTrue(Dtcs.describe("P0420").contains("Catalytic"))
        assertEquals("ignition / misfire code", Dtcs.describe("P0399"))
        assertTrue(Dtcs.describe("P1234").startsWith("Hyundai-specific"))
        assertTrue(Dtcs.describe("U0399").contains("network"))
    }

    @Test fun fuelMath() {
        // 60 mph burning 2 gal/h = 30 mpg
        assertEquals(30.0, Telemetry.instantMpg(60.0, 2.0)!!, 0.001)
        assertNull(Telemetry.instantMpg(0.0, 1.0))
        // ~10 g/s of air ≈ 0.875 gal/h
        assertEquals(0.875, Telemetry.galPerHourFromMaf(10.0), 0.01)
    }
}

class TelemetryTest {
    private val now = 1_800_000_000_000L

    @Test fun mergeKeepsNewest() {
        val a = Telemetry.num("fuel_pct", 50.0, Telemetry.Source.BLUELINK, now - 1000)!!
        val b = Telemetry.num("fuel_pct", 48.0, Telemetry.Source.OBD, now)!!
        val old = Telemetry.num("fuel_pct", 70.0, Telemetry.Source.ANDROID_AUTO, now - 5000)!!
        val m = Telemetry.merge(Telemetry.merge(emptyMap(), listOf(a)), listOf(b, old))
        assertEquals(48.0, m["fuel_pct"]!!.num!!, 0.0)
        assertEquals("48%", Telemetry.format(m["fuel_pct"]!!))
        assertEquals("Locked", Telemetry.format(Telemetry.bool("locked", true, Telemetry.Source.BLUELINK)!!))
    }

    @Test fun tripMilesPreferOdometerWhenPlausible() {
        val t = Telemetry.Trip("a", now, now + 1_800_000, gpsMiles = 12.1, odoStart = 31_000, odoEnd = 31_012)
        assertEquals(12.0, t.miles, 0.0)
        val bogus = t.copy(odoEnd = 31_400)
        assertEquals(12.1, bogus.miles, 0.0)
        val noGps = Telemetry.Trip("b", now, now + 600_000, speedMiles = 4.2)
        assertEquals(4.2, noGps.miles, 0.0)
        assertEquals(24.0, t.copy(fuelGal = 0.5).mpg(18.8)!!, 0.1)
    }

    @Test fun totalsAndCsv() {
        val trips = listOf(
            Telemetry.Trip("1", now, now + 600_000, gpsMiles = 10.0, purpose = Telemetry.Purpose.BUSINESS, startPlace = "Home", endPlace = "Work, Inc."),
            Telemetry.Trip("2", now + 700_000, now + 900_000, gpsMiles = 3.0),
            Telemetry.Trip("3", now + 1_000_000, null, gpsMiles = 99.0), // still open: not counted
        )
        val t = Telemetry.totals(trips, now - 1, now + 2_000_000, 0.70)
        assertEquals(2, t.trips)
        assertEquals(13.0, t.miles, 0.0)
        assertEquals(7.0, t.deductible, 0.001)
        val csv = Telemetry.tripsCsv(trips, java.time.ZoneOffset.UTC)
        assertEquals(3, csv.trim().lines().size)
        assertTrue(csv.contains("\"Work, Inc.\""))
    }

    @Test fun dtcHistory() {
        val (first, added) = Telemetry.mergeDtcs(emptyList(), listOf("P0420"), listOf("P0171"), emptyList(), now)
        assertEquals(2, added.size)
        assertEquals(2, first.count { it.active })
        val (second, added2) = Telemetry.mergeDtcs(first, listOf("P0420"), emptyList(), emptyList(), now + 1000)
        assertEquals(0, added2.size)
        assertFalse(second.first { it.code == "P0171" }.active)
        val (third, added3) = Telemetry.mergeDtcs(second, listOf("P0420"), listOf("P0171"), emptyList(), now + 2000)
        assertEquals(listOf("P0171"), added3.map { it.code })
        assertEquals(now, third.first { it.code == "P0171" }.firstSeen)
    }

    @Test fun jsonRoundTrip() {
        val s = Telemetry.State(
            Telemetry.Link(carAddress = "AA:BB:CC:DD:EE:FF", carName = "Santa Fe", obdAddress = "11:22:33:44:55:66", obdBle = true, bluelink = true, ratePerMile = 0.67),
            Telemetry.merge(emptyMap(), listOfNotNull(Telemetry.num("odometer", 31_250.0, Telemetry.Source.OBD, now), Telemetry.text("vin", "KM8S1234567890123", Telemetry.Source.OBD, now))),
            listOf(Telemetry.Trip("t", now, now + 1000, "Santa Fe", 32.7, -96.8, "Home", null, null, "", 5.5, 5.2, 1, 6, 55.0, 600, 60, 0.2, 80.0, 75.0, 1, 0,
                Telemetry.Purpose.COMMUTE, "x", setOf(Telemetry.Source.PHONE, Telemetry.Source.OBD))),
            listOf(Telemetry.Dtc("P0420", "stored", now, now)),
            Telemetry.Parked(32.7, -96.8, "Lot B", now, Telemetry.Source.PHONE), now)
        val back = Telemetry.fromJson(JSONObject(Telemetry.toJson(s).toString()))
        assertEquals(s, back)
    }

    @Test fun summaryMentionsWhatMatters() {
        val s = Telemetry.State(snapshot = Telemetry.merge(emptyMap(), listOfNotNull(
            Telemetry.num("fuel_pct", 40.0, Telemetry.Source.BLUELINK, now), Telemetry.num("range", 210.0, Telemetry.Source.BLUELINK, now),
            Telemetry.bool("locked", false, Telemetry.Source.BLUELINK, now))),
            dtcs = listOf(Telemetry.Dtc("P0442", "stored", now, now)))
        val text = Telemetry.summary(s, now)
        assertTrue(text, text.contains("Fuel 40%") && text.contains("210 mi") && text.contains("P0442") && text.contains("unlocked"))
    }

    @Test fun garageAddsEstimatedMiles() {
        val v = Garage.Vehicle(odometer = 31_000, estMiles = 400)
        assertEquals(31_400, v.odometerNow)
        val s = Garage.State(v, Garage.defaultSchedule().map { if (it.id == "oil") it.copy(lastMiles = 24_000, lastDay = java.time.LocalDate.of(2026, 9, 1).toEpochDay()) else it })
        val oil = Garage.due(s, java.time.LocalDate.of(2026, 11, 1)).first { it.item.id == "oil" }
        assertEquals(100, oil.milesLeft)
        assertEquals(400, Garage.fromJson(Garage.toJson(s)).vehicle.estMiles)
    }
}

class BluelinkTest {
    private val enrollment = JSONObject("""
        {"enrolledVehicleDetails":[{"vehicleDetails":{"regid":"H00001","vin":"5NMS1234X67890123","nickName":"Santa Fe","modelCode":"SANTA FE","modelYear":"2023",
         "vehicleGeneration":"3","odometer":"31250"}}]}""")
    private val status = JSONObject("""
        {"dateTime":"2026-11-07T18:30:00Z","fuelLevel":42,"lowFuelLight":false,"dte":{"value":215,"unit":3},"engine":false,"doorLock":false,
         "doorOpen":{"frontLeft":0,"frontRight":1,"backLeft":0,"backRight":0},"windowOpen":{"frontLeft":0,"frontRight":0,"backLeft":0,"backRight":0},
         "trunkOpen":false,"hoodOpen":false,"airCtrlOn":false,"airTemp":{"value":"72","unit":1},"washerFluidStatus":true,"breakOilStatus":false,
         "smartKeyBatteryWarning":false,"battery":{"batSoc":81},
         "tirePressureLamp":{"tirePressureWarningLampAll":1,"tirePressureWarningLampRearLeft":1,"tirePressureWarningLampFrontLeft":0}}""")
    private val location = JSONObject("""{"coord":{"lat":32.7767,"lon":-96.797,"alt":140},"head":90,"speed":{"value":0,"unit":1},"time":"2026-11-07T18:29:00Z"}""")

    @Test fun parsesAccountAndStatus() {
        val car = Bluelink.parseCars(enrollment).single()
        assertEquals("H00001", car.regId)
        assertEquals("3", car.generation)
        assertEquals(31_250.0, car.odometer!!, 0.0)
        val r = Bluelink.parse(car, status, location)
        val m = r.metrics.associateBy { it.key }
        assertEquals(42.0, m["fuel_pct"]!!.num!!, 0.0)
        assertEquals(215.0, m["range"]!!.num!!, 0.0)
        assertEquals(false, m["locked"]!!.bool)
        assertEquals("passenger", m["doors_open"]!!.text)
        assertEquals("None", m["windows_open"]!!.text)
        assertEquals("Low: rear left", m["tire_warning"]!!.text)
        assertEquals(true, m["washer_low"]!!.bool)
        assertEquals(81.0, m["battery_soc"]!!.num!!, 0.0)
        assertEquals("72°", m["climate_set"]!!.text)
        assertEquals(31_250.0, m["odometer"]!!.num!!, 0.0)
        assertEquals(java.time.Instant.parse("2026-11-07T18:30:00Z").toEpochMilli(), r.statusAt)
        assertNotNull(r.parked)
        assertEquals(32.7767, r.parked!!.lat, 1e-6)
    }

    @Test fun rangeInKilometres() {
        val car = Bluelink.parseCars(enrollment).single()
        val r = Bluelink.parse(car, JSONObject("""{"dte":{"value":100,"unit":1}}"""), null)
        assertEquals(62.1, r.metrics.first { it.key == "range" }.num!!, 0.1)
        assertNull(r.parked)
    }
}

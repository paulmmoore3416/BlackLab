package com.blacklab.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class AutoThemeTest {
    @Test fun sunTimesMatchKnownValues() {
        // New York, 2026-06-21: sunrise ≈ 05:25, sunset ≈ 20:31 (EDT)
        val ny = AutoTheme.sunTimes(LocalDate.of(2026, 6, 21), 40.7, -74.0, ZoneId.of("America/New_York"))!!
        assertTrue(ny.toString(), kotlin.math.abs(ny.sunrise.toSecondOfDay() - LocalTime.of(5, 25).toSecondOfDay()) <= 5 * 60)
        assertTrue(ny.toString(), kotlin.math.abs(ny.sunset.toSecondOfDay() - LocalTime.of(20, 31).toSecondOfDay()) <= 5 * 60)
        // Tromsø in midwinter: polar night
        assertNull(AutoTheme.sunTimes(LocalDate.of(2026, 12, 21), 69.6, 18.9, ZoneId.of("Europe/Oslo")))
    }

    @Test fun darkWindowCrossesMidnight() {
        val from = LocalTime.of(19, 0); val to = LocalTime.of(7, 0)
        assertTrue(AutoTheme.inDarkWindow(LocalTime.of(23, 0), from, to))
        assertTrue(AutoTheme.inDarkWindow(LocalTime.of(6, 59), from, to))
        assertTrue(!AutoTheme.inDarkWindow(LocalTime.of(12, 0), from, to))
    }

    @Test fun resolveFallsBackToCustomHoursWithoutLocation() {
        val noon = LocalDateTime.of(2026, 11, 1, 12, 0); val night = LocalDateTime.of(2026, 11, 1, 22, 0)
        assertEquals("LIGHT", AutoTheme.resolve(AutoTheme.AUTO, noon, null, 19 * 60, 7 * 60))
        assertEquals("DARK", AutoTheme.resolve(AutoTheme.AUTO, night, null, 19 * 60, 7 * 60))
        val sun = AutoTheme.SunTimes(LocalTime.of(6, 30), LocalTime.of(17, 0))
        assertEquals("DARK", AutoTheme.resolve(AutoTheme.AUTO, LocalDateTime.of(2026, 11, 1, 18, 0), sun, 19 * 60, 7 * 60))
        assertEquals("DARK", AutoTheme.resolve("DARK", noon, sun, 0, 0))
    }
}

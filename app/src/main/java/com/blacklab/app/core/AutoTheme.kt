package com.blacklab.app.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Automatic light/dark: dark from sunset to sunrise where you are, or between two times you pick.
 * Sun times are computed on the phone (NOAA formula); the location used is the phone's last known
 * coarse position, rounded to ~10 km and kept only in BlackLab's settings. Nothing is looked up online.
 */
object AutoTheme {
    const val AUTO = "AUTO"          // sunset → sunrise (falls back to the custom hours without a location)
    const val SCHEDULE = "SCHEDULE"  // custom hours

    data class SunTimes(val sunrise: LocalTime, val sunset: LocalTime)

    // ----------------------------------------------------------------- pure (unit tested)

    /** Sunrise and sunset for [date] at [lat]/[lon] in [zone]; null during polar day or night. */
    fun sunTimes(date: LocalDate, lat: Double, lon: Double, zone: ZoneId): SunTimes? {
        val rad = Math.PI / 180
        val n = date.dayOfYear
        // Fractional year (radians), equation of time (minutes) and solar declination (radians)
        val g = 2 * Math.PI / (if (date.isLeapYear) 366 else 365) * (n - 1)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) - 0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) + 0.000907 * sin(2 * g) - 0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        val cosHa = cos(90.833 * rad) / (cos(lat * rad) * cos(decl)) - tan(lat * rad) * tan(decl)
        if (cosHa < -1 || cosHa > 1) return null
        val ha = acos(cosHa) / rad
        val riseUtcMin = 720 - 4 * (lon + ha) - eqTime
        val setUtcMin = 720 - 4 * (lon - ha) - eqTime
        val offsetMin = zone.rules.getOffset(date.atTime(12, 0)).totalSeconds / 60.0
        fun toLocal(utcMin: Double): LocalTime {
            val m = ((utcMin + offsetMin) % 1440 + 1440) % 1440
            return LocalTime.of(floor(m / 60).toInt(), floor(m % 60).toInt())
        }
        return SunTimes(toLocal(riseUtcMin), toLocal(setUtcMin))
    }

    /** True when [now] is inside the dark window [darkFrom, darkTo) (which may cross midnight). */
    fun inDarkWindow(now: LocalTime, darkFrom: LocalTime, darkTo: LocalTime): Boolean =
        if (darkFrom <= darkTo) now >= darkFrom && now < darkTo else now >= darkFrom || now < darkTo

    /**
     * Resolves the theme setting to DARK, LIGHT or SYSTEM. [scheduleFrom]/[scheduleTo] are minutes
     * after midnight; [sun] is today's sun times when known.
     */
    fun resolve(mode: String, now: LocalDateTime, sun: SunTimes?, scheduleFrom: Int, scheduleTo: Int): String = when (mode) {
        AUTO, SCHEDULE -> {
            val useSun = mode == AUTO && sun != null
            val from = if (useSun) sun!!.sunset else LocalTime.of(scheduleFrom / 60 % 24, scheduleFrom % 60)
            val to = if (useSun) sun!!.sunrise else LocalTime.of(scheduleTo / 60 % 24, scheduleTo % 60)
            if (inDarkWindow(now.toLocalTime(), from, to)) "DARK" else "LIGHT"
        }
        else -> mode
    }

    // ----------------------------------------------------------------- device

    private const val PREFS = "blacklab_autotheme"

    /** Last known coarse location, rounded to 0.1° (~10 km), refreshed when available and cached. */
    fun location(context: Context): Pair<Double, Double>? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val granted = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (granted) runCatching {
            val lm = context.getSystemService(LocationManager::class.java)
            val best = lm.getProviders(true).mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }.maxByOrNull { it.time }
            if (best != null) {
                val lat = Math.round(best.latitude * 10) / 10.0; val lon = Math.round(best.longitude * 10) / 10.0
                prefs.edit().putFloat("lat", lat.toFloat()).putFloat("lon", lon.toFloat()).apply()
                return lat to lon
            }
        }
        return if (prefs.contains("lat")) prefs.getFloat("lat", 0f).toDouble() to prefs.getFloat("lon", 0f).toDouble() else null
    }

}

package com.blacklab.app.assistant

import android.content.Context
import com.blacklab.app.AppContainer
import com.blacklab.app.core.Profile
import com.blacklab.app.data.TaskEntity
import com.blacklab.app.data.TaskOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Weather from OpenWeatherMap (free key, no card) for the briefing, Home, Android Auto, the
 * assistant and BlackLab Desktop — plus **facility alerts**: freeze, heat, storms, wind, heavy rain
 * and snow in the next 48 hours become checklists and (optionally) Professional To-Do tasks, so
 * heat trace, roof drains and generators get checked before they matter.
 *
 * Location is the phone's last known position rounded to ~10 km (the same one the automatic theme
 * uses), or a fixed site you set. Results are cached for 45 minutes.
 */
object Weather {
    data class Now(val tempF: Double, val feelsF: Double, val condition: String, val icon: String, val windMph: Double, val humidity: Int, val place: String)
    data class Slot(val time: Long, val tempF: Double, val feelsF: Double, val gustMph: Double, val rainMm: Double, val snowMm: Double, val id: Int, val condition: String)
    data class Report(val now: Now, val slots: List<Slot>, val fetchedAt: Long) {
        val next24: List<Slot> get() = slots.filter { it.time <= fetchedAt + 24 * 3_600_000L }
        val lowF: Double get() = next24.minOfOrNull { it.tempF } ?: now.tempF
        val highF: Double get() = next24.maxOfOrNull { it.tempF } ?: now.tempF
    }
    data class Alert(val kind: String, val title: String, val detail: String, val checklist: List<String>, val severity: Int)

    private val _report = MutableStateFlow<Report?>(null)
    val report: StateFlow<Report?> = _report.asStateFlow()
    val error = MutableStateFlow<String?>(null)
    @Volatile private var app: AppContainer? = null

    fun init(container: AppContainer) {
        if (app != null) return
        app = container
        load(container.context)?.let { _report.value = it }
        container.appScope.launch {
            while (true) {
                if (container.secrets.has(Secrets.OPENWEATHER)) runCatching { refresh() }
                delay(3 * 3_600_000L)
            }
        }
    }

    // ---------------------------------------------------------------- facility rules (pure; unit tested)

    fun alerts(r: Report, hours: Int = 48): List<Alert> {
        val window = r.slots.filter { it.time <= r.fetchedAt + hours * 3_600_000L }.ifEmpty { return emptyList() }
        val out = ArrayList<Alert>()
        val low = window.minOf { it.tempF }; val lowAt = window.minBy { it.tempF }.time
        val high = window.maxOf { maxOf(it.tempF, it.feelsF) }
        val gust = window.maxOf { it.gustMph }
        val rainIn = window.sumOf { it.rainMm } / 25.4
        val snowIn = window.sumOf { it.snowMm } / 25.4
        val storms = window.filter { it.id in 200..299 }
        if (low <= 20) out += Alert("HARD_FREEZE", "Hard freeze: low ${low.toInt()}°F", "Lowest ${at(lowAt)}.", listOf(
            "Verify heat trace on exposed piping, roof drains and backflow preventers", "Check boilers and space heaters in mechanical and sprinkler rooms",
            "Drain or protect exposed hose bibs and irrigation", "Generator block heater on; fuel level checked", "Ice melt and mats at every entrance; walks treated early",
            "Dry-pipe sprinkler low points drained"), 3)
        else if (low <= 32) out += Alert("FREEZE", "Freeze risk: low ${low.toInt()}°F", "Lowest ${at(lowAt)}.", listOf(
            "Heat trace on and working", "Exposed piping and backflow preventers protected", "Hose bibs and irrigation drained", "Ice melt ready at entrances"), 2)
        if (high >= 95) out += Alert("HEAT", "Heat: up to ${high.toInt()}°F (feels-like)", "Hottest part of the day is when equipment and people struggle.", listOf(
            "Chillers / RTUs running clean — filters and condenser coils checked", "IT and electrical rooms cooling verified", "Patient-area and med-room temperatures logged",
            "Outdoor crews: water, shade and breaks scheduled", "Courtyard / outdoor time adjusted for patients"), 2)
        if (storms.isNotEmpty()) out += Alert("STORM", "Thunderstorms possible", "First ${at(storms.first().time)}.", listOf(
            "Roof drains and gutters clear", "Generator tested and fuel checked; UPS healthy", "Courtyard furniture and loose items secured (projectile risk)",
            "Sump pumps working", "Emergency lighting and radios ready"), 2)
        if (gust >= 40) out += Alert("WIND", "High wind: gusts to ${gust.toInt()} mph", "Secure anything that can move.", listOf(
            "Outdoor furniture, signage and dumpsters secured", "Roof access hatches latched; rooftop equipment panels closed", "Trees near the building checked"), 2)
        if (rainIn >= 1.0) out += Alert("RAIN", "Heavy rain: about ${"%.1f".format(rainIn)} in", "Over the next $hours hours.", listOf(
            "Roof drains, scuppers and area drains clear", "Sump pumps and backflow tested", "Below-grade rooms checked for leaks"), 1)
        if (snowIn >= 0.5) out += Alert("SNOW", "Snow: about ${"%.1f".format(snowIn)} in", "Over the next $hours hours.", listOf(
            "Snow removal contractor notified", "Ice melt stocked at entrances", "Emergency exits and fire hydrants kept clear", "Roof load watched on flat roofs"), 2)
        return out.sortedByDescending { it.severity }
    }

    private fun at(ms: Long): String {
        val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        val day = when (t.toLocalDate()) { LocalDate.now() -> "today"; LocalDate.now().plusDays(1) -> "tomorrow"; else -> t.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() } }
        return "$day around ${com.blacklab.app.core.Time.time(t.toLocalTime().hour * 60)}"
    }

    fun summary(r: Report): String {
        val n = r.now
        return "${n.place.ifBlank { "Here" }}: ${n.tempF.toInt()}°F and ${n.condition.lowercase()}" +
            (if (kotlin.math.abs(n.feelsF - n.tempF) >= 5) ", feels like ${n.feelsF.toInt()}°" else "") +
            ". Next 24 hours: high ${r.highF.toInt()}°, low ${r.lowF.toInt()}°" + (if (n.windMph >= 15) ", wind ${n.windMph.toInt()} mph" else "") + "."
    }

    // ---------------------------------------------------------------- fetch

    suspend fun refresh(force: Boolean = false): Report? = withContext(Dispatchers.IO) {
        val a = app ?: return@withContext null
        val key = a.secrets.get(Secrets.OPENWEATHER) ?: run { error.value = "Add a free OpenWeatherMap key in Assistant settings"; return@withContext null }
        _report.value?.let { if (!force && System.currentTimeMillis() - it.fetchedAt < 45 * 60_000L) return@withContext it }
        val (lat, lon) = site(a) ?: run { error.value = "Turn on location for BlackLab, or set a site under Weather"; return@withContext null }
        runCatching {
            val cur = get("https://api.openweathermap.org/data/2.5/weather?lat=$lat&lon=$lon&units=imperial&appid=$key")
            val fc = get("https://api.openweathermap.org/data/2.5/forecast?lat=$lat&lon=$lon&units=imperial&appid=$key")
            val r = parse(cur, fc, System.currentTimeMillis())
            _report.value = r; error.value = null; save(a.context, cur, fc)
            if (a.settings.current.weatherTasks) createTasks(a, r)
            r
        }.getOrElse { e -> error.value = if (e is SecurityException) "OpenWeatherMap rejected the key (new keys can take up to 2 hours to activate)" else "Couldn't reach OpenWeatherMap"; null }
    }

    private fun site(a: AppContainer): Pair<Double, Double>? {
        val s = a.settings.current.weatherSite.trim()
        Regex("^(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)$").find(s)?.let { return it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }
        return com.blacklab.app.core.AutoTheme.location(a.context)
    }

    private fun get(url: String): JSONObject = com.blacklab.app.core.Net.retry {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8_000; c.readTimeout = 15_000
            if (c.responseCode == 401) throw SecurityException("key")
            if (c.responseCode !in 200..299) throw com.blacklab.app.core.Net.HttpStatus(c.responseCode, "OpenWeatherMap")
            JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }

    fun parse(cur: JSONObject, fc: JSONObject, fetchedAt: Long): Report {
        val w0 = cur.optJSONArray("weather")?.optJSONObject(0)
        val now = Now(cur.optJSONObject("main")?.optDouble("temp") ?: 0.0, cur.optJSONObject("main")?.optDouble("feels_like") ?: 0.0,
            w0?.optString("description")?.replaceFirstChar { it.uppercase() } ?: "", w0?.optString("icon") ?: "", cur.optJSONObject("wind")?.optDouble("speed") ?: 0.0,
            cur.optJSONObject("main")?.optInt("humidity") ?: 0, cur.optString("name"))
        val list = fc.optJSONArray("list") ?: JSONArray()
        val slots = (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            val w = o.optJSONArray("weather")?.optJSONObject(0)
            Slot(o.optLong("dt") * 1000, o.optJSONObject("main")?.optDouble("temp") ?: 0.0, o.optJSONObject("main")?.optDouble("feels_like") ?: 0.0,
                o.optJSONObject("wind")?.let { it.optDouble("gust", it.optDouble("speed", 0.0)) } ?: 0.0,
                o.optJSONObject("rain")?.optDouble("3h") ?: 0.0, o.optJSONObject("snow")?.optDouble("3h") ?: 0.0, w?.optInt("id") ?: 800, w?.optString("description") ?: "")
        }
        return Report(now, slots, fetchedAt)
    }

    private fun save(context: Context, cur: JSONObject, fc: JSONObject) =
        context.getSharedPreferences("blacklab_weather", Context.MODE_PRIVATE).edit().putString("cur", cur.toString()).putString("fc", fc.toString()).putLong("at", System.currentTimeMillis()).apply()

    private fun load(context: Context): Report? = runCatching {
        val p = context.getSharedPreferences("blacklab_weather", Context.MODE_PRIVATE)
        parse(JSONObject(p.getString("cur", null) ?: return null), JSONObject(p.getString("fc", null) ?: return null), p.getLong("at", 0))
    }.getOrNull()

    /** One Professional task per alert per day, with its checklist — never duplicates. */
    private suspend fun createTasks(a: AppContainer, r: Report) {
        val today = LocalDate.now()
        val existing = a.db.tasks().all().filter { it.tags.contains("weather") }.map { it.tags }.toSet() // done ones count too: no repeats the same day
        alerts(r).filter { it.severity >= 2 }.forEach { al ->
            val tag = "weather,weather-${al.kind.lowercase()}-$today"
            if (existing.any { it.contains("weather-${al.kind.lowercase()}-$today") }) return@forEach
            TaskOps.save(a, TaskEntity(profile = Profile.PROFESSIONAL.name, title = "Weather prep — ${al.title}", notes = al.detail + "\n\n" + al.checklist.joinToString("\n") { "☐ $it" },
                dueDay = today.toEpochDay(), priority = if (al.severity >= 3) 4 else 3, important = true, tags = tag))
        }
    }

    fun toJson(r: Report): JSONObject = JSONObject()
        .put("temp", r.now.tempF).put("feels", r.now.feelsF).put("condition", r.now.condition).put("place", r.now.place).put("wind", r.now.windMph)
        .put("high", r.highF).put("low", r.lowF).put("fetchedAt", r.fetchedAt)
        .put("alerts", JSONArray().apply { alerts(r).forEach { put(JSONObject().put("kind", it.kind).put("title", it.title).put("detail", it.detail).put("checklist", JSONArray(it.checklist))) } })
}

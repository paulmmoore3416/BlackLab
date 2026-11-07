package com.blacklab.app.vehicle

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Everything BlackLab knows about the car right now, where each value came from, the trip log,
 * check-engine codes and where the car was last parked. Pure data + calculations (unit-tested);
 * [VehicleLink] owns the live copy and the file.
 */
object Telemetry {

    enum class Source(val label: String) {
        CAR_BT("Car Bluetooth"), ANDROID_AUTO("Android Auto"), OBD("OBD-II"), BLUELINK("Bluelink"), PHONE("Phone GPS"), LOGGED("Logged");
        companion object { fun of(s: String?) = entries.firstOrNull { it.name == s } ?: LOGGED }
    }

    enum class Group(val label: String) { DRIVING("Driving"), FUEL("Fuel"), ENGINE("Engine"), ELECTRICAL("Battery & electrical"), BODY("Doors, tires & cabin"), DIAGNOSTICS("Diagnostics"), IDENTITY("Vehicle") }
    enum class Kind { NUM, BOOL, TEXT }

    /** How a value is labelled and shown. Values are stored in US units (mi, mph, °F, gal). */
    data class Def(val key: String, val label: String, val group: Group, val unit: String = "", val kind: Kind = Kind.NUM, val decimals: Int = 0,
                   val yes: String = "Yes", val no: String = "No", val alertWhenTrue: Boolean = false)

    val DEFS: List<Def> = listOf(
        Def("odometer", "Odometer", Group.DRIVING, "mi"),
        Def("speed", "Speed", Group.DRIVING, "mph"),
        Def("engine_on", "Engine", Group.DRIVING, kind = Kind.BOOL, yes = "Running", no = "Off"),
        Def("runtime", "Engine run time", Group.DRIVING, "min"),
        Def("fuel_pct", "Fuel level", Group.FUEL, "%"),
        Def("range", "Range", Group.FUEL, "mi"),
        Def("low_fuel", "Low fuel", Group.FUEL, kind = Kind.BOOL, yes = "Low", no = "OK", alertWhenTrue = true),
        Def("inst_mpg", "Instant MPG", Group.FUEL, "mpg", decimals = 1),
        Def("fuel_rate", "Fuel use", Group.FUEL, "gal/h", decimals = 2),
        Def("fuel_type", "Fuel type", Group.FUEL, kind = Kind.TEXT),
        Def("rpm", "RPM", Group.ENGINE),
        Def("load", "Engine load", Group.ENGINE, "%"),
        Def("throttle", "Throttle", Group.ENGINE, "%"),
        Def("coolant", "Coolant temp", Group.ENGINE, "°F"),
        Def("oil_temp", "Oil temp", Group.ENGINE, "°F"),
        Def("intake_temp", "Intake air temp", Group.ENGINE, "°F"),
        Def("maf", "Mass air flow", Group.ENGINE, "g/s", decimals = 1),
        Def("map", "Manifold pressure", Group.ENGINE, "kPa"),
        Def("baro", "Barometric pressure", Group.ENGINE, "kPa"),
        Def("battery_v", "Battery voltage", Group.ELECTRICAL, "V", decimals = 1),
        Def("module_v", "ECU voltage", Group.ELECTRICAL, "V", decimals = 1),
        Def("battery_soc", "12V battery charge", Group.ELECTRICAL, "%"),
        Def("hev_soc", "Hybrid battery", Group.ELECTRICAL, "%"),
        Def("locked", "Doors", Group.BODY, kind = Kind.BOOL, yes = "Locked", no = "Unlocked"),
        Def("doors_open", "Open doors", Group.BODY, kind = Kind.TEXT),
        Def("windows_open", "Open windows", Group.BODY, kind = Kind.TEXT),
        Def("trunk_open", "Liftgate", Group.BODY, kind = Kind.BOOL, yes = "Open", no = "Closed", alertWhenTrue = true),
        Def("hood_open", "Hood", Group.BODY, kind = Kind.BOOL, yes = "Open", no = "Closed", alertWhenTrue = true),
        Def("tire_warning", "Tire pressure", Group.BODY, kind = Kind.TEXT),
        Def("climate_on", "Climate", Group.BODY, kind = Kind.BOOL, yes = "On", no = "Off"),
        Def("climate_set", "Climate set to", Group.BODY, kind = Kind.TEXT),
        Def("ambient", "Outside temp", Group.BODY, "°F"),
        Def("washer_low", "Washer fluid", Group.BODY, kind = Kind.BOOL, yes = "Low", no = "OK", alertWhenTrue = true),
        Def("brake_fluid", "Brake fluid", Group.BODY, kind = Kind.BOOL, yes = "Check", no = "OK", alertWhenTrue = true),
        Def("smartkey_low", "Key fob battery", Group.BODY, kind = Kind.BOOL, yes = "Low", no = "OK", alertWhenTrue = true),
        Def("mil", "Check engine light", Group.DIAGNOSTICS, kind = Kind.BOOL, yes = "ON", no = "Off", alertWhenTrue = true),
        Def("dtc_count", "Trouble codes", Group.DIAGNOSTICS),
        Def("dist_mil", "Miles with light on", Group.DIAGNOSTICS, "mi"),
        Def("dist_clear", "Miles since codes cleared", Group.DIAGNOSTICS, "mi"),
        Def("vin", "VIN", Group.IDENTITY, kind = Kind.TEXT),
        Def("model", "Reported model", Group.IDENTITY, kind = Kind.TEXT),
        Def("ecu", "Engine computer", Group.IDENTITY, kind = Kind.TEXT),
        Def("car_bt", "Car Bluetooth", Group.IDENTITY, kind = Kind.TEXT),
        Def("bluelink_at", "Bluelink status from", Group.IDENTITY, kind = Kind.TEXT),
    )
    private val defsByKey = DEFS.associateBy { it.key }
    fun def(key: String): Def? = defsByKey[key]

    data class Metric(val key: String, val num: Double?, val text: String?, val source: Source, val time: Long) {
        val bool: Boolean? get() = num?.let { it != 0.0 }
    }

    fun num(key: String, v: Double?, source: Source, time: Long = System.currentTimeMillis()) = v?.takeIf { it.isFinite() }?.let { Metric(key, it, null, source, time) }
    fun bool(key: String, v: Boolean?, source: Source, time: Long = System.currentTimeMillis()) = v?.let { Metric(key, if (it) 1.0 else 0.0, null, source, time) }
    fun text(key: String, v: String?, source: Source, time: Long = System.currentTimeMillis()) = v?.trim()?.takeIf { it.isNotEmpty() }?.let { Metric(key, null, it, source, time) }

    fun format(m: Metric): String {
        val d = def(m.key)
        return when {
            m.text != null -> m.text
            d == null -> m.num?.toString() ?: "—"
            d.kind == Kind.BOOL -> if (m.bool == true) d.yes else d.no
            else -> {
                val n = m.num ?: return "—"
                val s = if (d.decimals == 0) "%,d".format(n.roundToInt()) else "%,.${d.decimals}f".format(n)
                if (d.unit.isEmpty()) s else if (d.unit == "%" || d.unit.startsWith("°")) "$s${d.unit}" else "$s ${d.unit}"
            }
        }
    }

    fun isAlert(m: Metric): Boolean {
        val d = def(m.key) ?: return false
        return when {
            d.alertWhenTrue -> m.bool == true
            m.key == "dtc_count" -> (m.num ?: 0.0) > 0
            m.key == "tire_warning" -> m.text != null && m.text != "OK"
            m.key == "battery_v" -> (m.num ?: 12.6) < 11.8
            m.key == "coolant" -> (m.num ?: 0.0) >= 240
            m.key == "fuel_pct" -> (m.num ?: 100.0) <= 12
            m.key == "doors_open" || m.key == "windows_open" -> m.text != null && m.text != "None"
            else -> false
        }
    }

    // ---------------------------------------------------------------- units

    fun kmToMi(km: Double) = km * 0.621371
    fun metersToMi(m: Double) = m / 1609.344
    fun mpsToMph(mps: Double) = mps * 2.236936
    fun cToF(c: Double) = c * 9.0 / 5.0 + 32.0

    /** Gasoline burned per hour from mass air flow (stoichiometric 14.7:1, 6.17 lb/gal). */
    fun galPerHourFromMaf(mafGs: Double): Double = mafGs / 14.7 * 3600.0 / 2798.6

    /** Miles per gallon right now from road speed and fuel flow; null when stopped or flow is unknown. */
    fun instantMpg(mph: Double, galPerHour: Double?): Double? {
        if (galPerHour == null || galPerHour <= 0.01 || mph < 3) return null
        return (mph / galPerHour).coerceAtMost(99.9)
    }

    // ---------------------------------------------------------------- trips

    enum class Purpose(val label: String) { PERSONAL("Personal"), BUSINESS("Business"), COMMUTE("Commute"), MEDICAL("Medical"), CHARITY("Charity");
        companion object { fun of(s: String?) = entries.firstOrNull { it.name == s } ?: PERSONAL }
    }

    data class Trip(
        val id: String,
        val start: Long,
        val end: Long? = null,
        val car: String = "",
        val startLat: Double? = null, val startLon: Double? = null, val startPlace: String = "",
        val endLat: Double? = null, val endLon: Double? = null, val endPlace: String = "",
        val gpsMiles: Double = 0.0,
        val speedMiles: Double = 0.0,
        val odoStart: Int? = null, val odoEnd: Int? = null,
        val maxMph: Double = 0.0,
        val movingSec: Long = 0, val idleSec: Long = 0,
        val fuelGal: Double? = null,
        val fuelPctStart: Double? = null, val fuelPctEnd: Double? = null,
        val hardBrakes: Int = 0, val hardAccels: Int = 0,
        val purpose: Purpose = Purpose.PERSONAL,
        val note: String = "",
        val sources: Set<Source> = emptySet(),
    ) {
        /** Odometer difference when the car reported both ends; otherwise GPS, then integrated road speed. */
        val miles: Double get() {
            val odo = if (odoStart != null && odoEnd != null) (odoEnd - odoStart).toDouble() else null
            val best = maxOf(gpsMiles, speedMiles)
            return when {
                odo != null && odo >= 0 && (best == 0.0 || odo in best * 0.6..best * 1.6 + 2) -> odo
                gpsMiles > 0.05 -> gpsMiles
                else -> speedMiles
            }
        }
        val durationSec: Long get() = ((end ?: System.currentTimeMillis()) - start) / 1000
        val avgMph: Double? get() = movingSec.takeIf { it > 60 }?.let { miles / (it / 3600.0) }
        /** From measured fuel flow when the OBD adapter was connected; otherwise from the fuel-gauge drop. */
        fun mpg(tankGallons: Double): Double? {
            val m = miles
            if (m < 2) return null
            fuelGal?.takeIf { it > 0.02 }?.let { return (m / it).takeIf { v -> v in 3.0..99.0 } }
            val drop = if (fuelPctStart != null && fuelPctEnd != null) fuelPctStart - fuelPctEnd else null
            if (drop == null || drop < 4 || m < 15) return null
            return (m / (drop / 100.0 * tankGallons)).takeIf { it in 3.0..99.0 }
        }
    }

    data class Totals(val trips: Int, val miles: Double, val byPurpose: Map<Purpose, Double>, val driveSec: Long, val fuelGal: Double, val deductible: Double)

    fun totals(trips: List<Trip>, from: Long, to: Long, ratePerMile: Double): Totals {
        val t = trips.filter { it.end != null && it.start in from until to }
        val by = Purpose.entries.associateWith { p -> t.filter { it.purpose == p }.sumOf { it.miles } }
        val deductible = (by[Purpose.BUSINESS] ?: 0.0) * ratePerMile
        return Totals(t.size, t.sumOf { it.miles }, by, t.sumOf { it.durationSec }, t.sumOf { it.fuelGal ?: 0.0 }, deductible)
    }

    fun monthStart(day: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): Long = day.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
    fun weekStart(day: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): Long =
        day.minusDays((day.dayOfWeek.value % 7).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
    fun yearStart(day: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): Long = day.withDayOfYear(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /** A mileage log for taxes or reimbursement. */
    fun tripsCsv(trips: List<Trip>, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        appendLine("Date,Start,End,From,To,Miles,Purpose,Note,Odometer start,Odometer end,Minutes,Fuel (gal)")
        trips.filter { it.end != null }.sortedBy { it.start }.forEach { t ->
            val s = Instant.ofEpochMilli(t.start).atZone(zone); val e = Instant.ofEpochMilli(t.end!!).atZone(zone)
            fun q(v: String) = "\"" + v.replace("\"", "\"\"") + "\""
            appendLine(listOf(s.toLocalDate().toString(), "%02d:%02d".format(s.hour, s.minute), "%02d:%02d".format(e.hour, e.minute), q(t.startPlace), q(t.endPlace),
                "%.1f".format(Locale.US, t.miles), t.purpose.label, q(t.note), t.odoStart?.toString().orEmpty(), t.odoEnd?.toString().orEmpty(),
                (t.durationSec / 60).toString(), t.fuelGal?.let { "%.2f".format(Locale.US, it) }.orEmpty()).joinToString(","))
        }
    }

    // ---------------------------------------------------------------- diagnostics & parking

    data class Dtc(val code: String, val kind: String, val firstSeen: Long, val lastSeen: Long, val active: Boolean = true)
    data class Parked(val lat: Double, val lon: Double, val place: String, val time: Long, val source: Source)

    /** Merges a fresh read of codes into the history: new ones are added, missing ones are marked cleared. */
    fun mergeDtcs(old: List<Dtc>, stored: List<String>, pending: List<String>, permanent: List<String>, now: Long): Pair<List<Dtc>, List<Dtc>> {
        val fresh = stored.map { it to "stored" } + pending.filter { it !in stored }.map { it to "pending" } + permanent.filter { it !in stored && it !in pending }.map { it to "permanent" }
        val byCode = old.associateBy { it.code }.toMutableMap()
        val added = ArrayList<Dtc>()
        fresh.forEach { (code, kind) ->
            val prev = byCode[code]
            if (prev == null || !prev.active) { val d = Dtc(code, kind, prev?.firstSeen ?: now, now, true); byCode[code] = d; added += d }
            else byCode[code] = prev.copy(kind = kind, lastSeen = now)
        }
        val codes = fresh.map { it.first }.toSet()
        byCode.replaceAll { k, d -> if (k !in codes && d.active) d.copy(active = false) else d }
        return byCode.values.sortedWith(compareByDescending<Dtc> { it.active }.thenByDescending { it.lastSeen }).take(100) to added
    }

    // ---------------------------------------------------------------- settings & state

    data class Link(
        val carAddress: String = "",
        val carName: String = "",
        val associationId: Int = -1,
        val obdAddress: String = "",
        val obdName: String = "",
        val obdBle: Boolean = false,
        val autoTrips: Boolean = true,
        val recordPlaces: Boolean = true,
        val tripSummary: Boolean = true,
        val bluelink: Boolean = false,
        val bluelinkVin: String = "",
        val ratePerMile: Double = 0.70,
        val defaultPurpose: Purpose = Purpose.PERSONAL,
    )

    data class State(
        val link: Link = Link(),
        val snapshot: Map<String, Metric> = emptyMap(),
        val trips: List<Trip> = emptyList(),
        val dtcs: List<Dtc> = emptyList(),
        val parked: Parked? = null,
        val lastSession: Long = 0,
    ) {
        operator fun get(key: String): Metric? = snapshot[key]
        val activeDtcs: List<Dtc> get() = dtcs.filter { it.active }
    }

    /** Newer readings replace older ones, whatever the source. */
    fun merge(snapshot: Map<String, Metric>, metrics: List<Metric>): Map<String, Metric> {
        if (metrics.isEmpty()) return snapshot
        val out = snapshot.toMutableMap()
        metrics.forEach { m -> val prev = out[m.key]; if (prev == null || m.time >= prev.time) out[m.key] = m }
        return out
    }

    /** One spoken/notification line: fuel, range, codes, battery, parked. */
    fun summary(s: State, now: Long = System.currentTimeMillis()): String {
        val parts = ArrayList<String>()
        val fuel = listOfNotNull(s["fuel_pct"]?.let { "fuel ${format(it)}" }, s["range"]?.let { "${format(it)} of range" }).joinToString(", ")
        if (fuel.isNotBlank()) parts += fuel.replaceFirstChar { it.uppercase() }
        val codes = s.activeDtcs
        when {
            codes.isNotEmpty() -> parts += "${codes.size} trouble code${if (codes.size > 1) "s" else ""}: " + codes.take(3).joinToString(", ") { "${it.code} (${Dtcs.describe(it.code)})" }
            s["mil"]?.bool == true -> parts += "The check-engine light is on"
            s["mil"] != null || s["dtc_count"] != null -> parts += "No trouble codes"
        }
        s["battery_v"]?.let { parts += "battery ${format(it)}" }
        s["tire_warning"]?.takeIf { it.text != "OK" }?.let { parts += "tire pressure warning (${it.text})" }
        s["locked"]?.let { if (it.bool == false) parts += "the doors were left unlocked" }
        s.parked?.let { p -> if (now - p.time < 3 * 86_400_000L && p.place.isNotBlank()) parts += "parked at ${p.place}" }
        return parts.joinToString(". ") { it.replaceFirstChar { c -> c.uppercase() } }.let { if (it.isEmpty()) it else "$it." }
    }

    fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
        val s = (now - ms) / 1000
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            else -> "${s / 86_400} d ago"
        }
    }

    fun duration(sec: Long): String = if (sec < 3600) "${(sec + 30) / 60} min" else "${sec / 3600} h ${(sec % 3600) / 60} min"

    // ---------------------------------------------------------------- JSON

    private fun JSONObject.dbl(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k).takeIf { it.isFinite() } else null
    private fun JSONObject.int(k: String): Int? = if (has(k) && !isNull(k)) optInt(k) else null
    private fun Any?.orNull(): Any = this ?: JSONObject.NULL

    fun toJson(s: State): JSONObject = JSONObject().apply {
        val l = s.link
        put("link", JSONObject().put("carAddress", l.carAddress).put("carName", l.carName).put("associationId", l.associationId).put("obdAddress", l.obdAddress)
            .put("obdName", l.obdName).put("obdBle", l.obdBle).put("autoTrips", l.autoTrips).put("recordPlaces", l.recordPlaces).put("tripSummary", l.tripSummary)
            .put("bluelink", l.bluelink).put("bluelinkVin", l.bluelinkVin).put("ratePerMile", l.ratePerMile).put("defaultPurpose", l.defaultPurpose.name))
        put("snapshot", JSONArray().apply { s.snapshot.values.forEach { m -> put(JSONObject().put("k", m.key).put("n", m.num.orNull()).put("t", m.text.orNull()).put("s", m.source.name).put("at", m.time)) } })
        put("trips", JSONArray().apply {
            s.trips.forEach { t ->
                put(JSONObject().put("id", t.id).put("start", t.start).put("end", t.end.orNull()).put("car", t.car)
                    .put("sLat", t.startLat.orNull()).put("sLon", t.startLon.orNull()).put("sPlace", t.startPlace)
                    .put("eLat", t.endLat.orNull()).put("eLon", t.endLon.orNull()).put("ePlace", t.endPlace)
                    .put("gps", t.gpsMiles).put("spd", t.speedMiles).put("odoS", t.odoStart.orNull()).put("odoE", t.odoEnd.orNull()).put("max", t.maxMph)
                    .put("moving", t.movingSec).put("idle", t.idleSec).put("gal", t.fuelGal.orNull()).put("fS", t.fuelPctStart.orNull()).put("fE", t.fuelPctEnd.orNull())
                    .put("brakes", t.hardBrakes).put("accels", t.hardAccels).put("purpose", t.purpose.name).put("note", t.note)
                    .put("sources", JSONArray(t.sources.map { it.name })))
            }
        })
        put("dtcs", JSONArray().apply { s.dtcs.forEach { d -> put(JSONObject().put("code", d.code).put("kind", d.kind).put("first", d.firstSeen).put("last", d.lastSeen).put("active", d.active)) } })
        s.parked?.let { p -> put("parked", JSONObject().put("lat", p.lat).put("lon", p.lon).put("place", p.place).put("time", p.time).put("source", p.source.name)) }
        put("lastSession", s.lastSession)
    }

    fun fromJson(o: JSONObject): State {
        val l = o.optJSONObject("link")?.let { j ->
            Link(j.optString("carAddress"), j.optString("carName"), j.optInt("associationId", -1), j.optString("obdAddress"), j.optString("obdName"), j.optBoolean("obdBle"),
                j.optBoolean("autoTrips", true), j.optBoolean("recordPlaces", true), j.optBoolean("tripSummary", true), j.optBoolean("bluelink"), j.optString("bluelinkVin"),
                j.optDouble("ratePerMile", 0.70).takeIf { it.isFinite() } ?: 0.70, Purpose.of(j.optString("defaultPurpose")))
        } ?: Link()
        fun arr(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
        val snap = arr("snapshot").mapNotNull { j ->
            val k = j.optString("k").ifBlank { return@mapNotNull null }
            Metric(k, j.dbl("n"), if (j.isNull("t")) null else j.optString("t"), Source.of(j.optString("s")), j.optLong("at"))
        }.associateBy { it.key }
        val trips = arr("trips").mapNotNull { j ->
            runCatching {
                Trip(j.getString("id"), j.getLong("start"), if (j.isNull("end")) null else j.optLong("end"), j.optString("car"),
                    j.dbl("sLat"), j.dbl("sLon"), j.optString("sPlace"), j.dbl("eLat"), j.dbl("eLon"), j.optString("ePlace"),
                    j.optDouble("gps", 0.0), j.optDouble("spd", 0.0), j.int("odoS"), j.int("odoE"), j.optDouble("max", 0.0), j.optLong("moving"), j.optLong("idle"),
                    j.dbl("gal"), j.dbl("fS"), j.dbl("fE"), j.optInt("brakes"), j.optInt("accels"), Purpose.of(j.optString("purpose")), j.optString("note"),
                    j.optJSONArray("sources")?.let { a -> (0 until a.length()).map { Source.of(a.optString(it)) }.toSet() }.orEmpty())
            }.getOrNull()
        }
        val dtcs = arr("dtcs").map { j -> Dtc(j.optString("code"), j.optString("kind", "stored"), j.optLong("first"), j.optLong("last"), j.optBoolean("active", true)) }
        val parked = o.optJSONObject("parked")?.let { j -> Parked(j.optDouble("lat"), j.optDouble("lon"), j.optString("place"), j.optLong("time"), Source.of(j.optString("source"))) }
        return State(l, snap, trips, dtcs, parked, o.optLong("lastSession"))
    }
}

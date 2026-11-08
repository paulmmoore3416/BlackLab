package com.blacklab.app.vehicle

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

/**
 * The Garage: your 2023 Hyundai Santa Fe — profile, odometer, maintenance schedule with what's due
 * next (by miles or months, whichever comes first), service history and a fuel log with MPG.
 * Kept in one JSON file in app storage; Android Auto reads and writes the same data.
 */
object Garage {

    data class Vehicle(
        val year: Int = 2023,
        val make: String = "Hyundai",
        val model: String = "Santa Fe",
        val trim: String = "",
        val engine: String = ENGINES.first(),
        val drive: String = "AWD",
        val nickname: String = "",
        val vin: String = "",
        val plate: String = "",
        val odometer: Int = 0,
        val odometerAt: Long = 0,
        val tankGallons: Double = 18.8,
        val tirePsi: String = "",
        val oil: String = "",
        val severeService: Boolean = false,
        val dealer: String = "",
        val insurance: String = "",
        val registrationDue: Long? = null,
        /** Miles driven (by GPS) since the last real odometer reading — keeps "what's due" current between readings. */
        val estMiles: Int = 0,
        /** Your own estimate of yearly miles (0 = learn it from trips and fill-ups). Used to predict service dates. */
        val milesPerYear: Int = 0,
    ) {
        /** Best guess at the odometer right now. */
        val odometerNow: Int get() = if (odometer > 0) odometer + estMiles else 0
        val name: String get() = nickname.ifBlank { "$year $make $model" + (trim.takeIf { it.isNotBlank() }?.let { " $it" } ?: "") }
    }

    data class ServiceItem(
        val id: String,
        val name: String,
        val miles: Int?,
        val months: Int?,
        val severeMiles: Int? = null,
        val severeMonths: Int? = null,
        val lastMiles: Int? = null,
        val lastDay: Long? = null,
        val note: String = "",
    )

    data class ServiceRecord(val id: String, val day: Long, val miles: Int?, val items: List<String>, val title: String, val cost: Double?, val shop: String, val notes: String)
    data class Fuel(val id: String, val time: Long, val miles: Int?, val gallons: Double, val cost: Double?, val full: Boolean = true, val station: String = "")

    data class State(val vehicle: Vehicle = Vehicle(), val items: List<ServiceItem> = defaultSchedule(), val services: List<ServiceRecord> = emptyList(), val fuel: List<Fuel> = emptyList())

    enum class Status { OVERDUE, DUE_SOON, OK, UNKNOWN }
    data class Due(val item: ServiceItem, val status: Status, val milesLeft: Int?, val daysLeft: Long?, val dueMiles: Int?, val dueDay: Long?,
                   /** When the miles will run out at your usual pace (epoch day), if we know your pace. */
                   val predictedDay: Long? = null) {
        /** The earlier of the calendar due date and the predicted mileage date. */
        val expectedDay: Long? get() = listOfNotNull(dueDay, predictedDay).minOrNull()
        fun describe(): String {
            val predicted = predictedDay?.takeIf { milesLeft != null && milesLeft >= 0 && (dueDay == null || it < dueDay) }
                ?.let { " (around ${LocalDate.ofEpochDay(it).let { d -> "${d.month.name.lowercase().replaceFirstChar { c -> c.uppercase() }.take(3)} ${d.dayOfMonth}" }})" }.orEmpty()
            val parts = listOfNotNull(
                milesLeft?.let { if (it < 0) "${fmtMiles(-it)} mi overdue" else "in ${fmtMiles(it)} mi$predicted" },
                daysLeft?.let { if (it < 0) "${-it} days overdue" else if (it < 60) "in $it days" else "by ${LocalDate.ofEpochDay(dueDay!!).let { d -> "${d.month.name.lowercase().replaceFirstChar { c -> c.uppercase() }.take(3)} ${d.year}" }}" },
            )
            return if (parts.isEmpty()) "Log when it was last done" else parts.joinToString(" or ")
        }
    }

    val ENGINES = listOf("2.5L 4-cylinder", "2.5L Turbo", "1.6L Turbo Hybrid", "1.6L Turbo Plug-in Hybrid")
    val TRIMS = listOf("SE", "SEL", "XRT", "Limited", "Calligraphy", "Blue (Hybrid)", "SEL Premium (Hybrid)", "Limited (Hybrid)", "SEL Convenience (PHEV)", "Limited (PHEV)")

    /**
     * Facts about the 2023 Santa Fe that hold across trims. Anything trim- or market-specific
     * (tire pressure, oil spec, intervals) lives in the editable profile/schedule instead.
     */
    val FACTS = listOf(
        "Engines" to "2.5L 4-cyl (191 hp, 8-speed automatic) · 2.5L Turbo (281 hp, 8-speed wet dual-clutch) · 1.6L Turbo Hybrid (226 hp total) · 1.6L Turbo Plug-in Hybrid (261 hp total)",
        "Towing" to "Up to 3,500 lb with the 2.5L Turbo and a trailer brake; about 2,000 lb for the 2.5L and the hybrids. Check the door-jamb and Owner's Manual for your exact rating.",
        "Fuel tank" to "18.8 gal on the gas models. Hybrids and the PHEV differ — set yours in the profile.",
        "Tire pressure" to "Use the placard on the driver's door jamb (cold tires), not the number on the tire sidewall.",
        "Android Auto" to "The 8-inch display supports wireless Android Auto; the 10.25-inch navigation display connects with a USB data cable. Hold the steering-wheel voice button to talk to Google Assistant.",
        "Warranty (original owner)" to "10-yr/100,000-mi powertrain, 5-yr/60,000-mi new-vehicle limited, 5-yr unlimited-mile roadside assistance, and 3-yr/36,000-mi complimentary maintenance (oil changes and tire rotations). Confirm in your warranty booklet.",
    )

    /** A typical Hyundai schedule — a starting point to confirm against the Owner's Manual. Every interval is editable. */
    fun defaultSchedule(): List<ServiceItem> = listOf(
        ServiceItem("oil", "Engine oil & filter", 7_500, 12, 3_750, 6, note = "Severe use (towing, short trips, extreme heat or cold, dusty roads, stop-and-go) uses the shorter interval."),
        ServiceItem("rotate", "Tire rotation", 7_500, null, note = "Check tread depth and pressures at the same time."),
        ServiceItem("cabin", "Cabin air filter", 15_000, 12, 7_500, 6),
        ServiceItem("airfilter", "Engine air filter", 30_000, null, 15_000, null, note = "Inspect every 15,000 mi; replace sooner on dusty roads."),
        ServiceItem("brakes", "Brake inspection (pads, rotors, lines)", 15_000, 12),
        ServiceItem("brakefluid", "Brake fluid", 30_000, 24),
        ServiceItem("battery", "12V battery test", null, 12),
        ServiceItem("wipers", "Wiper blades", null, 12),
        ServiceItem("coolant", "Engine coolant", 120_000, 120, note = "First replacement; then every 30,000 mi / 24 months."),
        ServiceItem("plugs", "Spark plugs", 100_000, null, 45_000, null),
        ServiceItem("atf", "Transmission fluid (inspect)", 60_000, null, 30_000, null),
        ServiceItem("awd", "AWD transfer case & rear differential (inspect)", 30_000, 24, note = "AWD models only."),
        ServiceItem("belt", "Drive belt (inspect)", 60_000, 72),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var loaded = false
    private var file: File? = null

    fun load(context: Context): State {
        if (loaded) return _state.value
        synchronized(this) {
            if (loaded) return _state.value
            val f = File(context.filesDir, "garage/garage.json").also { file = it }
            _state.value = runCatching { fromJson(JSONObject(f.readText())) }.getOrDefault(State())
            loaded = true
        }
        return _state.value
    }

    private fun commit(context: Context, s: State) {
        load(context)
        _state.value = s
        runCatching {
            val f = file ?: File(context.filesDir, "garage/garage.json")
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "garage.json.tmp")
            tmp.writeText(toJson(s).toString())
            tmp.renameTo(f)
        }
        com.blacklab.app.widgets.CarWidget.refreshSoon(context)
    }

    fun update(context: Context, change: (State) -> State) = synchronized(this) { commit(context, change(load(context))) }

    fun setVehicle(context: Context, v: Vehicle) = update(context) { it.copy(vehicle = v) }

    /** Records a new odometer reading (ignored if lower than what we already know). */
    fun setOdometer(context: Context, miles: Int, at: Long = System.currentTimeMillis()) = update(context) { s ->
        if (miles <= 0 || miles < s.vehicle.odometer) s else s.copy(vehicle = s.vehicle.copy(odometer = miles, odometerAt = at, estMiles = 0))
    }

    /** Adds GPS-measured miles from a drive that had no odometer reading at the end. */
    fun addDrivenMiles(context: Context, miles: Double) = update(context) { s ->
        if (miles < 0.5 || s.vehicle.odometer <= 0) s else s.copy(vehicle = s.vehicle.copy(estMiles = s.vehicle.estMiles + Math.round(miles).toInt()))
    }

    fun upsertItem(context: Context, item: ServiceItem) = update(context) { s ->
        s.copy(items = if (s.items.any { it.id == item.id }) s.items.map { if (it.id == item.id) item else it } else s.items + item)
    }

    fun deleteItem(context: Context, id: String) = update(context) { s -> s.copy(items = s.items.filterNot { it.id == id }) }

    fun resetSchedule(context: Context) = update(context) { s ->
        val done = s.items.associateBy { it.id }
        s.copy(items = defaultSchedule().map { d -> done[d.id]?.let { d.copy(lastMiles = it.lastMiles, lastDay = it.lastDay) } ?: d })
    }

    /** Logs a service visit: marks the matching schedule items done and advances the odometer. */
    fun logService(context: Context, itemIds: List<String>, miles: Int?, day: Long = LocalDate.now().toEpochDay(), title: String = "", cost: Double? = null, shop: String = "", notes: String = "") = update(context) { s ->
        val names = s.items.filter { it.id in itemIds }.map { it.name }
        val rec = ServiceRecord(UUID.randomUUID().toString(), day, miles, itemIds, title.ifBlank { names.joinToString(", ").ifBlank { "Service" } }, cost, shop, notes)
        val items = s.items.map { if (it.id in itemIds) it.copy(lastMiles = miles ?: it.lastMiles, lastDay = day) else it }
        val v = if (miles != null && miles > s.vehicle.odometer) s.vehicle.copy(odometer = miles, odometerAt = System.currentTimeMillis(), estMiles = 0) else s.vehicle
        s.copy(vehicle = v, items = items, services = (listOf(rec) + s.services).sortedByDescending { it.day })
    }

    fun deleteService(context: Context, id: String) = update(context) { s -> s.copy(services = s.services.filterNot { it.id == id }) }

    fun addFuel(context: Context, fuel: Fuel) = update(context) { s ->
        val v = if (fuel.miles != null && fuel.miles > s.vehicle.odometer) s.vehicle.copy(odometer = fuel.miles, odometerAt = fuel.time, estMiles = 0) else s.vehicle
        s.copy(vehicle = v, fuel = (listOf(fuel) + s.fuel).sortedByDescending { it.time })
    }.also { VehicleLink.clearPendingFill(context, fuel.time) }

    fun deleteFuel(context: Context, id: String) = update(context) { s -> s.copy(fuel = s.fuel.filterNot { it.id == id }) }

    // ---------------------------------------------------------------- calculations (pure; unit-tested)

    fun due(s: State, today: LocalDate = LocalDate.now(), milesPerDay: Double? = null): List<Due> {
        val odo = s.vehicle.odometerNow.takeIf { it > 0 }
        val pace = milesPerDay?.takeIf { it > 0.5 } ?: s.vehicle.milesPerYear.takeIf { it > 0 }?.let { it / 365.0 }
        return s.items.map { item ->
            val miles = if (s.vehicle.severeService) item.severeMiles ?: item.miles else item.miles
            val months = if (s.vehicle.severeService) item.severeMonths ?: item.months else item.months
            // Never logged: count from new while the car is still inside the first interval; after that we can't know
            val dueMiles = when {
                miles == null -> null
                item.lastMiles != null -> item.lastMiles + miles
                odo != null && odo < miles -> miles
                else -> null
            }
            val milesLeft = if (dueMiles != null && odo != null) dueMiles - odo else null
            val dueDay = if (months != null && item.lastDay != null) LocalDate.ofEpochDay(item.lastDay).plusMonths(months.toLong()).toEpochDay() else null
            val daysLeft = dueDay?.let { ChronoUnit.DAYS.between(today, LocalDate.ofEpochDay(it)) }
            val status = when {
                (milesLeft != null && milesLeft < 0) || (daysLeft != null && daysLeft < 0) -> Status.OVERDUE
                (milesLeft != null && milesLeft <= 750) || (daysLeft != null && daysLeft <= 30) -> Status.DUE_SOON
                milesLeft == null && daysLeft == null -> Status.UNKNOWN
                else -> Status.OK
            }
            val predicted = if (milesLeft != null && pace != null) today.plusDays(Math.round(milesLeft.coerceAtLeast(0) / pace)).toEpochDay() else null
            Due(item, status, milesLeft, daysLeft, dueMiles, dueDay, predicted)
        }.sortedWith(compareBy<Due> { it.status.ordinal }.thenBy { it.milesLeft ?: Int.MAX_VALUE }.thenBy { it.daysLeft ?: Long.MAX_VALUE })
    }

    /**
     * Your usual miles per day: from the trip log when it covers at least a week, otherwise from the
     * fill-up odometer readings over at least two weeks; null until there's enough to go on.
     */
    fun milesPerDay(fuel: List<Fuel>, trips: List<Pair<Long, Double>>, now: Long = System.currentTimeMillis()): Double? {
        val day = 86_400_000.0
        val recent = trips.filter { it.first > now - 90 * 86_400_000L }
        if (recent.size >= 3) {
            val span = (now - recent.minOf { it.first }) / day
            if (span >= 7) return recent.sumOf { it.second } / span
        }
        val f = fuel.filter { it.miles != null && it.time > now - 365 * 86_400_000L }.sortedBy { it.time }
        if (f.size >= 2) {
            val span = (f.last().time - f.first().time) / day
            val miles = (f.last().miles!! - f.first().miles!!).toDouble()
            if (span >= 14 && miles > 0) return miles / span
        }
        return null
    }

    /**
     * One-time import of `BlackLab/.provision/vehicle.json` (pushed with adb), so details like the VIN
     * and the current odometer never have to be typed or committed anywhere. Recognised keys: vin,
     * odometer, year, make, model, trim, engine, drive, tankGallons, nickname, plate, milesPerYear.
     * The file is deleted after it's read. Returns true when something was applied.
     */
    fun importProvisioning(context: Context, root: File): Boolean {
        val dir = File(root, ".provision")
        val f = File(dir, "vehicle.json")
        if (!f.exists()) return false
        val ok = runCatching {
            val j = JSONObject(f.readText())
            update(context) { s ->
                var v = s.vehicle
                j.optString("vin").uppercase().filter { it.isLetterOrDigit() }.takeIf { it.length == 17 }?.let { v = v.copy(vin = it) }
                if (j.has("year")) v = v.copy(year = j.optInt("year", v.year))
                j.optString("make").takeIf { it.isNotBlank() }?.let { v = v.copy(make = it) }
                j.optString("model").takeIf { it.isNotBlank() }?.let { v = v.copy(model = it) }
                j.optString("trim").takeIf { it.isNotBlank() }?.let { v = v.copy(trim = it) }
                j.optString("engine").takeIf { it.isNotBlank() }?.let { v = v.copy(engine = it) }
                j.optString("drive").takeIf { it == "AWD" || it == "FWD" }?.let { v = v.copy(drive = it) }
                j.optString("nickname").takeIf { it.isNotBlank() }?.let { v = v.copy(nickname = it) }
                j.optString("plate").takeIf { it.isNotBlank() }?.let { v = v.copy(plate = it.uppercase()) }
                j.optDouble("tankGallons").takeIf { it.isFinite() && it in 5.0..60.0 }?.let { v = v.copy(tankGallons = it) }
                j.optInt("milesPerYear", -1).takeIf { it >= 0 }?.let { v = v.copy(milesPerYear = it) }
                // A reading you give on purpose replaces the estimate even if it's lower than the GPS-padded one
                j.optInt("odometer", 0).takeIf { it > 0 }?.let { v = v.copy(odometer = it, odometerAt = System.currentTimeMillis(), estMiles = 0) }
                val items = if (v.drive == "FWD") s.items.filterNot { it.id == "awd" } else s.items
                s.copy(vehicle = v, items = items)
            }
            true
        }.getOrDefault(false)
        f.delete()
        dir.delete()
        return ok
    }

    data class FuelStats(val avgMpg: Double?, val lastMpg: Double?, val costPerMile: Double?, val monthCost: Double, val avgPrice: Double?)

    /** MPG between consecutive full fill-ups (partial fills roll into the next full one). */
    fun fuelStats(fuel: List<Fuel>, now: Long = System.currentTimeMillis()): FuelStats {
        val asc = fuel.filter { it.miles != null }.sortedBy { it.miles }
        val mpgs = ArrayList<Pair<Double, Double>>() // miles, gallons
        var lastFull: Fuel? = null
        var gallonsSince = 0.0
        asc.forEach { f ->
            if (lastFull == null) { if (f.full) lastFull = f; return@forEach }
            gallonsSince += f.gallons
            if (f.full) {
                val miles = (f.miles!! - lastFull!!.miles!!).toDouble()
                if (miles > 0 && gallonsSince > 0) mpgs += miles to gallonsSince
                lastFull = f; gallonsSince = 0.0
            }
        }
        val totalMiles = mpgs.sumOf { it.first }
        val totalGal = mpgs.sumOf { it.second }
        val avg = if (totalGal > 0) totalMiles / totalGal else null
        val last = mpgs.lastOrNull()?.let { it.first / it.second }
        val priced = fuel.filter { it.cost != null && it.gallons > 0 }
        val avgPrice = if (priced.isEmpty()) null else priced.sumOf { it.cost!! } / priced.sumOf { it.gallons }
        val cpm = if (avg != null && avgPrice != null) avgPrice / avg else null
        val monthAgo = now - 30L * 86_400_000
        return FuelStats(avg, last, cpm, fuel.filter { it.time >= monthAgo }.sumOf { it.cost ?: 0.0 }, avgPrice)
    }

    /** MPG of each full tank (time of the fill that closed it, mpg), oldest first. */
    fun mpgSeries(fuel: List<Fuel>): List<Pair<Long, Double>> {
        val asc = fuel.filter { it.miles != null }.sortedBy { it.miles }
        val out = ArrayList<Pair<Long, Double>>()
        var lastFull: Fuel? = null
        var gal = 0.0
        asc.forEach { f ->
            if (lastFull == null) { if (f.full) lastFull = f; return@forEach }
            gal += f.gallons
            if (f.full) {
                val miles = (f.miles!! - lastFull!!.miles!!).toDouble()
                if (miles > 0 && gal > 0) (miles / gal).takeIf { it in 5.0..80.0 }?.let { out += f.time to it }
                lastFull = f; gal = 0.0
            }
        }
        return out
    }

    /** Fuel spend per calendar month for the last [months] months, oldest first. */
    fun monthlySpend(fuel: List<Fuel>, months: Int = 6, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): List<Pair<java.time.YearMonth, Double>> {
        val now = java.time.YearMonth.from(today)
        return (months - 1 downTo 0).map { i ->
            val ym = now.minusMonths(i.toLong())
            ym to fuel.filter { java.time.YearMonth.from(Instant.ofEpochMilli(it.time).atZone(zone)) == ym }.sumOf { it.cost ?: 0.0 }
        }
    }

    /**
     * Understands spoken fuel logs: "12.4 gallons for 47.80 at 31250 miles", "filled up 14 gallons
     * $52, odometer 31,250", "half tank 6 gallons". Returns null when no gallons were heard.
     */
    fun parseFuel(text: String, now: Long = System.currentTimeMillis()): Fuel? {
        val t = text.lowercase(Locale.US).replace(Regex("(\\d),(\\d{3})"), "$1$2")
        val gallons = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:gallons?|gal\\b)").find(t)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val cost = Regex("\\$\\s*(\\d+(?:\\.\\d{1,2})?)|(\\d+(?:\\.\\d{1,2})?)\\s*(?:dollars?|bucks)|(?:for|cost|paid|total)\\s+(\\d+(?:\\.\\d{1,2})?)(?!\\s*(?:gal|mi))").find(t)
            ?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.toDoubleOrNull()
        val miles = Regex("(?:at|odometer|odo|mileage)\\s*(?:is|of|reads)?\\s*(\\d{3,6})|(\\d{3,6})\\s*(?:miles|mi)\\b").find(t)
            ?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.toIntOrNull()
        val partial = Regex("partial|half|top(ped)? off|not full").containsMatchIn(t)
        val station = Regex("(?:at|from) (costco|sam'?s club|shell|exxon|mobil|bp|chevron|speedway|wawa|sheetz|circle k|marathon|sunoco|quiktrip|qt|kroger|buc-?ee'?s|murphy|valero|citgo|7-eleven|racetrac)").find(t)?.groupValues?.get(1)?.replaceFirstChar { it.uppercase() }.orEmpty()
        return Fuel(UUID.randomUUID().toString(), now, miles, gallons, cost, !partial, station)
    }

    /** Maps "oil change", "rotated tires", "new wipers"… to schedule item ids. */
    fun matchItems(text: String, items: List<ServiceItem>): List<String> {
        val t = text.lowercase(Locale.US)
        val rules = listOf(
            "oil" to Regex("oil"), "rotate" to Regex("rotat|tire rotation"), "cabin" to Regex("cabin"),
            "airfilter" to Regex("engine air|air filter(?! cabin)"), "brakes" to Regex("brake (pad|inspect|job)|pads|rotors"), "brakefluid" to Regex("brake fluid"),
            "battery" to Regex("battery"), "wipers" to Regex("wiper"), "coolant" to Regex("coolant|antifreeze"), "plugs" to Regex("spark ?plug|plugs"),
            "atf" to Regex("transmission"), "awd" to Regex("transfer case|differential|diff fluid|awd"), "belt" to Regex("belt"),
        )
        val ids = rules.filter { (_, re) -> re.containsMatchIn(t) }.map { it.first }.toMutableSet()
        if ("cabin" in ids && !Regex("engine air").containsMatchIn(t)) ids.remove("airfilter")
        if ("brakefluid" in ids && !Regex("pad|rotor|inspect").containsMatchIn(t)) ids.remove("brakes")
        items.filter { it.id !in ids && it.name.lowercase().let { n -> n.length > 4 && t.contains(n) } }.forEach { ids += it.id }
        return ids.filter { id -> items.any { it.id == id } }
    }

    fun summary(s: State, today: LocalDate = LocalDate.now()): String {
        val d = due(s, today)
        val next = d.firstOrNull { it.status == Status.OVERDUE || it.status == Status.DUE_SOON } ?: d.firstOrNull { it.status == Status.OK }
        val stats = fuelStats(s.fuel)
        return buildString {
            append(s.vehicle.name)
            if (s.vehicle.odometer > 0) append(" · ${if (s.vehicle.estMiles > 0) "about " else ""}${fmtMiles(s.vehicle.odometerNow)} mi")
            append(". ")
            val overdue = d.count { it.status == Status.OVERDUE }
            if (overdue > 0) append("$overdue service item${if (overdue > 1) "s are" else " is"} overdue. ")
            next?.let { append("Next up: ${it.item.name} ${it.describe()}. ") }
            stats.avgMpg?.let { append("Averaging ${"%.1f".format(it)} mpg.") }
        }.trim()
    }

    fun fmtMiles(m: Int): String = "%,d".format(m)
    fun dayLabel(day: Long): String = LocalDate.ofEpochDay(day).let { "${it.month.name.lowercase().replaceFirstChar { c -> c.uppercase() }.take(3)} ${it.dayOfMonth}, ${it.year}" }
    fun timeLabel(ms: Long): String = dayLabel(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay())

    // ---------------------------------------------------------------- JSON

    private fun JSONObject.optIntOrNull(k: String): Int? = if (has(k) && !isNull(k)) optInt(k) else null
    private fun JSONObject.optLongOrNull(k: String): Long? = if (has(k) && !isNull(k)) optLong(k) else null
    private fun JSONObject.optDoubleOrNull(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k) else null

    fun toJson(s: State): JSONObject = JSONObject().apply {
        val v = s.vehicle
        put("vehicle", JSONObject().put("year", v.year).put("make", v.make).put("model", v.model).put("trim", v.trim).put("engine", v.engine).put("drive", v.drive)
            .put("nickname", v.nickname).put("vin", v.vin).put("plate", v.plate).put("odometer", v.odometer).put("odometerAt", v.odometerAt).put("tankGallons", v.tankGallons)
            .put("tirePsi", v.tirePsi).put("oil", v.oil).put("severe", v.severeService).put("dealer", v.dealer).put("insurance", v.insurance).put("registrationDue", v.registrationDue ?: JSONObject.NULL).put("estMiles", v.estMiles).put("milesPerYear", v.milesPerYear))
        put("items", JSONArray().apply {
            s.items.forEach { i ->
                put(JSONObject().put("id", i.id).put("name", i.name).put("miles", i.miles ?: JSONObject.NULL).put("months", i.months ?: JSONObject.NULL)
                    .put("severeMiles", i.severeMiles ?: JSONObject.NULL).put("severeMonths", i.severeMonths ?: JSONObject.NULL)
                    .put("lastMiles", i.lastMiles ?: JSONObject.NULL).put("lastDay", i.lastDay ?: JSONObject.NULL).put("note", i.note))
            }
        })
        put("services", JSONArray().apply {
            s.services.forEach { r ->
                put(JSONObject().put("id", r.id).put("day", r.day).put("miles", r.miles ?: JSONObject.NULL).put("items", JSONArray(r.items)).put("title", r.title)
                    .put("cost", r.cost ?: JSONObject.NULL).put("shop", r.shop).put("notes", r.notes))
            }
        })
        put("fuel", JSONArray().apply {
            s.fuel.forEach { f ->
                put(JSONObject().put("id", f.id).put("time", f.time).put("miles", f.miles ?: JSONObject.NULL).put("gallons", f.gallons).put("cost", f.cost ?: JSONObject.NULL)
                    .put("full", f.full).put("station", f.station))
            }
        })
    }

    fun fromJson(o: JSONObject): State {
        val v = o.optJSONObject("vehicle")?.let { j ->
            Vehicle(j.optInt("year", 2023), j.optString("make", "Hyundai"), j.optString("model", "Santa Fe"), j.optString("trim"), j.optString("engine", ENGINES.first()),
                j.optString("drive", "AWD"), j.optString("nickname"), j.optString("vin"), j.optString("plate"), j.optInt("odometer"), j.optLong("odometerAt"),
                j.optDouble("tankGallons", 18.8), j.optString("tirePsi"), j.optString("oil"), j.optBoolean("severe"), j.optString("dealer"), j.optString("insurance"),
                j.optLongOrNull("registrationDue"), j.optInt("estMiles"), j.optInt("milesPerYear"))
        } ?: Vehicle()
        fun arr(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        val items = arr("items")?.map { j ->
            ServiceItem(j.getString("id"), j.getString("name"), j.optIntOrNull("miles"), j.optIntOrNull("months"), j.optIntOrNull("severeMiles"), j.optIntOrNull("severeMonths"),
                j.optIntOrNull("lastMiles"), j.optLongOrNull("lastDay"), j.optString("note"))
        } ?: defaultSchedule()
        val services = arr("services")?.map { j ->
            val ids = j.optJSONArray("items")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            ServiceRecord(j.getString("id"), j.getLong("day"), j.optIntOrNull("miles"), ids, j.optString("title"), j.optDoubleOrNull("cost"), j.optString("shop"), j.optString("notes"))
        }.orEmpty()
        val fuel = arr("fuel")?.map { j ->
            Fuel(j.getString("id"), j.getLong("time"), j.optIntOrNull("miles"), j.getDouble("gallons"), j.optDoubleOrNull("cost"), j.optBoolean("full", true), j.optString("station"))
        }.orEmpty()
        return State(v, items, services, fuel)
    }
}

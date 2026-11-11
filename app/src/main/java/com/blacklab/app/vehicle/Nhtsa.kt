package com.blacklab.app.vehicle

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * The US safety agency's free public data (no key, no account): decodes the VIN into the car's
 * exact build (trim, engine, drivetrain, plant) and lists safety recalls for its make, model and
 * year. Checked weekly; a new recall raises a notification. The VIN goes to NHTSA only to decode it;
 * the recall list is looked up by year, make and model.
 */
object Nhtsa {

    data class Decoded(
        val vin: String, val year: Int?, val make: String, val model: String, val trim: String, val engine: String, val drive: String,
        /** Label → value, for the "From your VIN" card. */
        val specs: List<Pair<String, String>>,
    )

    data class Recall(
        val campaign: String, val date: String, val component: String, val summary: String, val consequence: String, val remedy: String,
        val parkIt: Boolean = false, val parkOutside: Boolean = false, val overTheAir: Boolean = false,
    )

    data class State(
        val decoded: Decoded? = null,
        val recalls: List<Recall> = emptyList(),
        val checkedAt: Long = 0,
        /** Campaigns you've marked done or not for your car. */
        val resolved: Set<String> = emptySet(),
        /** Campaigns we've already told you about. */
        val notified: Set<String> = emptySet(),
        val error: String = "",
    ) {
        val open: List<Recall> get() = recalls.filter { it.campaign !in resolved }
    }

    val store = com.blacklab.app.core.JsonStore("Recalls", version = 1, empty = { State() }, decode = { fromJson(it) }, encode = { toJson(it) })
    val state: StateFlow<State> = store.state
    fun file(context: Context) = File(context.filesDir, "garage/nhtsa.json")

    fun load(context: Context): State = store.load(file(context))

    private fun update(context: Context, change: (State) -> State) { store.update(file(context), change = change) }

    fun setResolved(context: Context, campaign: String, done: Boolean) = update(context) { it.copy(resolved = if (done) it.resolved + campaign else it.resolved - campaign) }

    /** NHTSA's own page for an open-recall check on this exact VIN (their site; the API doesn't offer it). */
    fun vinCheckUrl(vin: String) = "https://www.nhtsa.gov/recalls?vin=" + URLEncoder.encode(vin, "UTF-8")

    // ---------------------------------------------------------------- network

    private fun get(url: String): JSONObject = com.blacklab.app.core.Net.retry {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12_000; c.readTimeout = 20_000
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "BlackLab-Android")
        try {
            if (c.responseCode !in 200..299) throw com.blacklab.app.core.Net.HttpStatus(c.responseCode, "NHTSA")
            JSONObject(c.inputStream.bufferedReader().readText())
        } finally { c.disconnect() }
    }

    /** Decodes, fills blank profile fields from it, and refreshes the recall list. Returns a status line. */
    suspend fun refresh(context: Context, notify: Boolean = true): String = withContext(Dispatchers.IO) {
        val g = Garage.load(context).vehicle
        runCatching {
            val decoded = if (g.vin.length == 17) {
                load(context).decoded?.takeIf { it.vin == g.vin } ?: parseDecode(g.vin, get("https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVinValues/${g.vin}?format=json"))
            } else null
            decoded?.let { applyToProfile(context, it) }
            val year = decoded?.year ?: g.year
            val make = decoded?.make?.ifBlank { null } ?: g.make
            val model = decoded?.model?.ifBlank { null } ?: g.model
            val recalls = parseRecalls(get("https://api.nhtsa.gov/recalls/recallsByVehicle?make=${enc(make)}&model=${enc(model)}&modelYear=$year"))
            val before = load(context)
            val fresh = recalls.filter { it.campaign !in before.notified && it.campaign !in before.resolved }
            update(context) { it.copy(decoded = decoded ?: it.decoded, recalls = recalls, checkedAt = System.currentTimeMillis(), error = "", notified = it.notified + recalls.map { r -> r.campaign }) }
            // The first check only lists what's there; after that, anything new is announced
            if (notify && before.checkedAt > 0) fresh.forEach { r ->
                VehicleLink.alert(context, "recall_${r.campaign}", "New recall: ${r.component.lowercase().replaceFirstChar { it.uppercase() }}",
                    "NHTSA campaign ${r.campaign} for the $year ${make.lowercase().replaceFirstChar { it.uppercase() }} $model. ${r.summary.take(240)}")
            }
            if (recalls.isEmpty()) "No recalls listed for the $year $model" else "${recalls.size} recall${if (recalls.size > 1) "s" else ""} listed for the $year $model"
        }.getOrElse { e -> update(context) { it.copy(error = e.message ?: "Couldn't reach NHTSA") }; "Couldn't reach NHTSA: ${e.message}" }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Fills trim, engine and drivetrain in the profile from the VIN (only where they differ from the decode and you haven't chosen otherwise). */
    private fun applyToProfile(context: Context, d: Decoded) = Garage.update(context) { s ->
        val v = s.vehicle
        val nv = v.copy(
            year = d.year ?: v.year,
            trim = v.trim.ifBlank { Garage.TRIMS.firstOrNull { it.equals(d.trim, true) } ?: d.trim },
            engine = d.engine.takeIf { it.isNotBlank() && v.engine == Garage.ENGINES.first() } ?: v.engine,
            drive = d.drive.ifBlank { v.drive },
        )
        s.copy(vehicle = nv, items = if (nv.drive == "FWD") s.items.filterNot { it.id == "awd" } else s.items)
    }

    // ---------------------------------------------------------------- parsing (pure; unit-tested)

    fun parseDecode(vin: String, o: JSONObject): Decoded {
        val r = o.optJSONArray("Results")?.optJSONObject(0) ?: JSONObject()
        fun v(k: String) = r.optString(k).trim().takeIf { it.isNotEmpty() && it != "Not Applicable" && it != "null" }.orEmpty()
        val liters = v("DisplacementL").toDoubleOrNull()
        val cyl = v("EngineCylinders")
        val turbo = v("Turbo").equals("Yes", true) || v("EngineConfiguration").contains("turbo", true)
        val hybrid = v("ElectrificationLevel").contains("hybrid", true)
        val engine = when {
            liters == null -> ""
            hybrid && v("ElectrificationLevel").contains("plug", true) -> Garage.ENGINES[3]
            hybrid -> Garage.ENGINES[2]
            liters in 2.4..2.6 && turbo -> Garage.ENGINES[1]
            liters in 2.4..2.6 -> Garage.ENGINES[0]
            else -> "%.1fL".format(liters) + (if (cyl.isNotBlank()) " $cyl-cylinder" else "") + if (turbo) " Turbo" else ""
        }
        val dt = v("DriveType")
        val drive = when {
            dt.contains("4x2", true) || dt.contains("FWD", true) || dt.contains("Front", true) -> "FWD"
            dt.contains("AWD", true) || dt.contains("4WD", true) || dt.contains("4x4", true) || dt.contains("All", true) -> "AWD"
            else -> ""
        }
        val hp = v("EngineHP").toDoubleOrNull()?.let { "%.0f hp".format(it) }.orEmpty()
        val trans = listOf(v("TransmissionSpeeds").takeIf { it.isNotBlank() }?.let { "$it-speed" }, v("TransmissionStyle").lowercase().ifBlank { null }).filterNotNull().joinToString(" ")
        val plant = listOf(v("PlantCity").lowercase().split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }, v("PlantState").lowercase().replaceFirstChar { it.uppercase() })
            .filter { it.isNotBlank() }.joinToString(", ")
        val specs = listOfNotNull(
            v("Trim").takeIf { it.isNotBlank() }?.let { "Trim" to it },
            listOf(liters?.let { "%.1fL".format(it) }, cyl.takeIf { it.isNotBlank() }?.let { "$it-cyl" }, v("EngineModel").takeIf { it.isNotBlank() }, hp.ifBlank { null }).filterNotNull()
                .joinToString(" · ").takeIf { it.isNotBlank() }?.let { "Engine" to it },
            trans.takeIf { it.isNotBlank() }?.let { "Transmission" to it },
            (if (drive == "FWD") "Front-wheel drive" else if (drive == "AWD") "All-wheel drive" else dt).takeIf { it.isNotBlank() }?.let { "Drive" to it },
            v("FuelTypePrimary").takeIf { it.isNotBlank() }?.let { "Fuel" to it },
            v("Seats").takeIf { it.isNotBlank() }?.let { "Seats" to (it + (v("SeatRows").takeIf { r2 -> r2.isNotBlank() }?.let { r2 -> " in $r2 rows" } ?: "")) },
            v("TPMS").takeIf { it.isNotBlank() }?.let { "Tire-pressure sensors" to it },
            plant.takeIf { it.isNotBlank() }?.let { "Built in" to it },
            v("Note").takeIf { it.contains("Curb", true) }?.let { "Curb weight" to it.substringAfter(":").trim() },
        )
        return Decoded(vin, v("ModelYear").toIntOrNull(), v("Make").lowercase().replaceFirstChar { it.uppercase() }, v("Model"), v("Trim"), engine, drive, specs)
    }

    fun parseRecalls(o: JSONObject): List<Recall> {
        val a = o.optJSONArray("results") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { j ->
            Recall(j.optString("NHTSACampaignNumber"), j.optString("ReportReceivedDate"), j.optString("Component"), j.optString("Summary").trim(),
                j.optString("Consequence").trim(), j.optString("Remedy").trim(), j.optBoolean("parkIt"), j.optBoolean("parkOutSide"), j.optBoolean("overTheAirUpdate"))
        }.filter { it.campaign.isNotBlank() }.sortedByDescending { it.campaign }
    }

    // ---------------------------------------------------------------- JSON

    private fun toJson(s: State) = JSONObject().apply {
        s.decoded?.let { d ->
            put("decoded", JSONObject().put("vin", d.vin).put("year", d.year ?: JSONObject.NULL).put("make", d.make).put("model", d.model).put("trim", d.trim)
                .put("engine", d.engine).put("drive", d.drive).put("specs", JSONArray().apply { d.specs.forEach { (k, v) -> put(JSONArray().put(k).put(v)) } }))
        }
        put("recalls", JSONArray().apply {
            s.recalls.forEach { r -> put(JSONObject().put("NHTSACampaignNumber", r.campaign).put("ReportReceivedDate", r.date).put("Component", r.component).put("Summary", r.summary)
                .put("Consequence", r.consequence).put("Remedy", r.remedy).put("parkIt", r.parkIt).put("parkOutSide", r.parkOutside).put("overTheAirUpdate", r.overTheAir)) }
        })
        put("checkedAt", s.checkedAt); put("resolved", JSONArray(s.resolved.toList())); put("notified", JSONArray(s.notified.toList())); put("error", s.error)
    }

    private fun fromJson(o: JSONObject): State {
        val d = o.optJSONObject("decoded")?.let { j ->
            val specs = j.optJSONArray("specs")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONArray(it) }.map { it.optString(0) to it.optString(1) } }.orEmpty()
            Decoded(j.optString("vin"), if (j.isNull("year")) null else j.optInt("year"), j.optString("make"), j.optString("model"), j.optString("trim"), j.optString("engine"), j.optString("drive"), specs)
        }
        fun set(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }.orEmpty()
        return State(d, parseRecalls(JSONObject().put("results", o.optJSONArray("recalls") ?: JSONArray())), o.optLong("checkedAt"), set("resolved"), set("notified"), o.optString("error"))
    }

    // ---------------------------------------------------------------- scheduling

    /** Weekly check on Wi-Fi or mobile data. */
    fun schedule(context: Context) {
        val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("nhtsa_weekly", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RecallWorker>(7, TimeUnit.DAYS).setConstraints(net).setInitialDelay(1, TimeUnit.HOURS).build())
    }

    /** Right away (a new VIN arrived), once there's a connection. */
    fun refreshSoon(context: Context) {
        val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(context).enqueueUniqueWork("nhtsa_now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<RecallWorker>().setConstraints(net).build())
    }
}

class RecallWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val msg = Nhtsa.refresh(applicationContext)
        return if (msg.startsWith("Couldn't")) Result.retry() else Result.success()
    }
}

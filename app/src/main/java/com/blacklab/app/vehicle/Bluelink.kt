package com.blacklab.app.vehicle

import com.blacklab.app.assistant.Secrets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.TimeZone

/**
 * Hyundai Bluelink (US) — what the car last reported to Hyundai's servers: odometer, fuel and range,
 * doors and locks, tire-pressure and fluid warnings, 12V battery and where it's parked.
 *
 * Hyundai doesn't publish this API. BlackLab speaks the same protocol as Hyundai's own web portal (as
 * documented by the open-source hyundai_kia_connect_api / bluelinky projects), so Hyundai can change it
 * without notice. Only cached status is read — BlackLab never wakes the car's modem — and your
 * Bluelink sign-in is kept in the Keystore-encrypted [Secrets] store and only ever sent to Hyundai.
 */
object Bluelink {
    const val USER = "bluelink_user"
    const val PASS = "bluelink_pass"
    const val PIN = "bluelink_pin"
    private const val TOKEN = "bluelink_token"

    private const val HOST = "api.telematics.hyundaiusa.com"
    private const val LOGIN = "https://$HOST/v2/ac/oauth/token"
    private const val API = "https://$HOST/ac/v2/"
    // Identifiers of Hyundai's own web client (public; the same for every user), not credentials
    private const val CLIENT_ID = "m66129Bb-em93-SPAHYN-bZ91-am4540zp19920"
    private const val CLIENT_SECRET = "v558o935-6nne-423i-baa8"

    class BluelinkException(message: String) : Exception(message)

    data class Car(val regId: String, val vin: String, val name: String, val model: String, val year: String, val generation: String, val odometer: Double?)
    data class Result(val car: Car, val metrics: List<Telemetry.Metric>, val parked: Telemetry.Parked?, val statusAt: Long)

    fun configured(secrets: Secrets) = secrets.has(USER) && secrets.has(PASS)

    fun signOut(secrets: Secrets) { listOf(USER, PASS, PIN, TOKEN).forEach { secrets.put(it, null) } }

    private fun headers(): MutableMap<String, String> = mutableMapOf(
        "content-type" to "application/json;charset=UTF-8",
        "accept" to "application/json, text/plain, */*",
        "accept-language" to "en-US,en;q=0.9",
        "user-agent" to "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36",
        "origin" to "https://$HOST",
        "referer" to "https://$HOST/login",
        "from" to "SPA", "to" to "ISS", "language" to "0",
        "offset" to (TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 3_600_000).toString(),
        "gen" to "2",
        "client_id" to CLIENT_ID, "clientSecret" to CLIENT_SECRET,
    )

    private fun call(url: String, headers: Map<String, String>, body: JSONObject? = null): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000; c.readTimeout = 25_000
            c.requestMethod = if (body != null) "POST" else "GET"
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toString().toByteArray()) } }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (code !in 200..299) {
                val msg = json.optString("errorMessage").ifBlank { json.optJSONObject("error")?.optString("errorDesc").orEmpty() }.ifBlank { json.optString("message") }
                throw BluelinkException(when (code) {
                    401, 403 -> "Bluelink rejected the sign-in" + (msg.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
                    429 -> "Bluelink is rate-limiting requests — try again later"
                    else -> "Bluelink error $code" + (msg.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
                })
            }
            return json
        } finally { c.disconnect() }
    }

    private fun token(secrets: Secrets, forceLogin: Boolean = false): String {
        if (!forceLogin) secrets.get(TOKEN)?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { t ->
            if (t.optLong("expires") > System.currentTimeMillis() + 60_000) return t.optString("access")
        }
        val user = secrets.get(USER) ?: throw BluelinkException("Sign in to Bluelink first")
        val pass = secrets.get(PASS) ?: throw BluelinkException("Sign in to Bluelink first")
        val r = call(LOGIN, headers(), JSONObject().put("username", user).put("password", pass))
        val access = r.optString("access_token").ifBlank { throw BluelinkException("Bluelink didn't accept the username or password") }
        val expires = System.currentTimeMillis() + (r.optString("expires_in").toDoubleOrNull() ?: 1800.0).toLong() * 1000
        secrets.put(TOKEN, JSONObject().put("access", access).put("expires", expires).toString())
        return access
    }

    private fun authed(secrets: Secrets, access: String) = headers().apply {
        put("username", secrets.get(USER).orEmpty()); put("accessToken", access); put("blueLinkServicePin", secrets.get(PIN).orEmpty())
    }

    fun parseCars(enrollment: JSONObject): List<Car> {
        val a = enrollment.optJSONArray("enrolledVehicleDetails") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val d = a.optJSONObject(i)?.optJSONObject("vehicleDetails") ?: return@mapNotNull null
            Car(d.optString("regid"), d.optString("vin"), d.optString("nickName"), d.optString("modelCode"), d.optString("modelYear"),
                d.optString("vehicleGeneration").ifBlank { "2" }, d.optString("odometer").replace(",", "").toDoubleOrNull())
        }
    }

    /** Signs in and lists the cars on the account (used once at setup). */
    suspend fun cars(secrets: Secrets): List<Car> = withContext(Dispatchers.IO) {
        val access = token(secrets, forceLogin = true)
        parseCars(call(API + "enrollment/details/" + secrets.get(USER).orEmpty(), authed(secrets, access)))
    }

    /** The car's last-reported status (cached on Hyundai's side; doesn't wake the car). */
    suspend fun fetch(secrets: Secrets, vin: String): Result = withContext(Dispatchers.IO) {
        var access = token(secrets)
        val enrollment = runCatching { call(API + "enrollment/details/" + secrets.get(USER).orEmpty(), authed(secrets, access)) }
            .recoverCatching { e -> if (e is BluelinkException && e.message?.contains("sign-in") == true) { access = token(secrets, true); call(API + "enrollment/details/" + secrets.get(USER).orEmpty(), authed(secrets, access)) } else throw e }
            .getOrThrow()
        val cars = parseCars(enrollment)
        val car = cars.firstOrNull { it.vin.equals(vin, true) } ?: cars.firstOrNull() ?: throw BluelinkException("No vehicles on this Bluelink account")
        val h = authed(secrets, access).apply { put("registrationId", car.regId); put("gen", car.generation); put("vin", car.vin); put("APPCLOUD-VIN", car.vin); put("refresh", "false") }
        val status = call(API + "rcs/rvs/vehicleStatus", h).optJSONObject("vehicleStatus")
        val location = runCatching { call(API + "rcs/rfc/findMyCar", h) }.getOrNull()
        parse(car, status, location)
    }

    fun parseTime(s: String?, fallback: Long): Long {
        if (s.isNullOrBlank()) return fallback
        runCatching { return Instant.parse(s).toEpochMilli() }
        runCatching { return LocalDateTime.parse(s.take(14), DateTimeFormatter.ofPattern("yyyyMMddHHmmss")).toInstant(ZoneOffset.UTC).toEpochMilli() }
        return fallback
    }

    /** Pure: Bluelink JSON → BlackLab metrics (unit-tested with recorded responses). */
    fun parse(car: Car, status: JSONObject?, location: JSONObject?, now: Long = System.currentTimeMillis()): Result {
        val src = Telemetry.Source.BLUELINK
        val at = parseTime(status?.optString("dateTime")?.ifBlank { null } ?: status?.optString("lastStatusDate"), now)
        val out = ArrayList<Telemetry.Metric>()
        fun b(k: String, v: Boolean?) = Telemetry.bool(k, v, src, at)?.let(out::add)
        fun n(k: String, v: Double?) = Telemetry.num(k, v, src, at)?.let(out::add)
        fun t(k: String, v: String?) = Telemetry.text(k, v, src, at)?.let(out::add)
        fun JSONObject.boolOrNull(k: String): Boolean? = if (!has(k) || isNull(k)) null else when (val v = opt(k)) { is Boolean -> v; is Number -> v.toInt() != 0; is String -> v == "1" || v.equals("true", true); else -> null }
        fun JSONObject.dblOrNull(k: String): Double? = if (!has(k) || isNull(k)) null else when (val v = opt(k)) { is Number -> v.toDouble(); is String -> v.toDoubleOrNull(); else -> null }

        car.odometer?.takeIf { it > 0 }?.let { n("odometer", it) }
        t("vin", car.vin)
        t("model", listOf(car.year, car.name.ifBlank { car.model }).filter { it.isNotBlank() }.joinToString(" "))
        if (status != null) {
            n("fuel_pct", status.dblOrNull("fuelLevel"))
            b("low_fuel", status.boolOrNull("lowFuelLight"))
            status.optJSONObject("dte")?.let { d ->
                val v = d.dblOrNull("value")
                // unit 1 = km, 3 = miles
                n("range", if (d.optInt("unit", 3) == 1 && v != null) Telemetry.kmToMi(v) else v)
            }
            b("engine_on", status.boolOrNull("engine"))
            b("locked", status.boolOrNull("doorLock"))
            b("trunk_open", status.boolOrNull("trunkOpen"))
            b("hood_open", status.boolOrNull("hoodOpen"))
            b("climate_on", status.boolOrNull("airCtrlOn"))
            status.optJSONObject("airTemp")?.optString("value")?.takeIf { it.isNotBlank() && it != "0" }?.let { v ->
                t("climate_set", if (v.toDoubleOrNull() != null) "$v°" else v.uppercase())
            }
            b("washer_low", status.boolOrNull("washerFluidStatus"))
            b("brake_fluid", status.boolOrNull("breakOilStatus"))
            b("smartkey_low", status.boolOrNull("smartKeyBatteryWarning"))
            status.optJSONObject("battery")?.dblOrNull("batSoc")?.let { n("battery_soc", it) }
            val names = mapOf("frontLeft" to "driver", "frontRight" to "passenger", "backLeft" to "rear left", "backRight" to "rear right")
            status.optJSONObject("doorOpen")?.let { d -> t("doors_open", names.filter { (k, _) -> d.boolOrNull(k) == true }.values.joinToString(", ").ifBlank { "None" }) }
            status.optJSONObject("windowOpen")?.let { d -> t("windows_open", names.filter { (k, _) -> d.boolOrNull(k) == true }.values.joinToString(", ").ifBlank { "None" }) }
            status.optJSONObject("tirePressureLamp")?.let { tp ->
                val tires = mapOf("tirePressureWarningLampFrontLeft" to "front left", "tirePressureWarningLampFrontRight" to "front right",
                    "tirePressureWarningLampRearLeft" to "rear left", "tirePressureWarningLampRearRight" to "rear right")
                val low = tires.filter { (k, _) -> tp.boolOrNull(k) == true }.values
                val all = tp.boolOrNull("tirePressureWarningLampAll") == true
                t("tire_warning", when { low.isNotEmpty() -> "Low: " + low.joinToString(", "); all -> "Check all tires"; else -> "OK" })
            }
        }
        t("bluelink_at", java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).let { "%d/%d %d:%02d".format(it.monthValue, it.dayOfMonth, it.hour, it.minute) })
        val parked = location?.optJSONObject("coord")?.let { c ->
            val lat = c.optDouble("lat"); val lon = c.optDouble("lon")
            if (lat.isFinite() && lon.isFinite() && (lat != 0.0 || lon != 0.0)) Telemetry.Parked(lat, lon, "", parseTime(location.optString("time"), at), src) else null
        }
        return Result(car, out, parked, at)
    }
}

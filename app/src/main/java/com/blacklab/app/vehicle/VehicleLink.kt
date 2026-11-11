package com.blacklab.app.vehicle

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.blacklab.app.R
import com.blacklab.app.app
import com.blacklab.app.reminders.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * The link between BlackLab and the car. When the phone connects to the Santa Fe's Bluetooth (or
 * Android Auto starts), a [DriveService] session begins: it logs the trip with the phone's GPS, reads
 * the OBD-II adapter if you have one, and pulls Bluelink's last-reported status. Android Auto adds
 * whatever the head unit shares. Every value lands in one snapshot with its source and time.
 */
object VehicleLink {

    /** What's happening right now (not saved). */
    data class Live(
        val active: Boolean = false,
        val since: Long = 0,
        val via: String = "",
        val tripMiles: Double = 0.0,
        val mph: Double? = null,
        val obd: String = "",
        val bluelink: String = "",
        val androidAuto: Boolean = false,
        val ending: Boolean = false,
        val sim: Boolean = false,
    )

    val store = com.blacklab.app.core.JsonStore("Drive log", version = 1, empty = { Telemetry.State() }, decode = Telemetry::fromJson, encode = Telemetry::toJson)
    val state: StateFlow<Telemetry.State> = store.state
    private val _live = MutableStateFlow(Live())
    val live: StateFlow<Live> = _live.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null

    const val CH_DRIVE = "drive"
    const val CH_VEHICLE = "vehicle"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            android.app.NotificationChannel(CH_DRIVE, "Driving", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while BlackLab logs a drive and reads the car"; setShowBadge(false)
            },
            android.app.NotificationChannel(CH_VEHICLE, "Vehicle alerts & trips", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Check-engine codes, warnings from the car and trip summaries"
            },
        ))
    }

    fun file(context: Context) = File(context.filesDir, "garage/telemetry.json")

    fun load(context: Context): Telemetry.State = store.load(file(context))

    private fun writeNow(context: Context) { store.writeNow(file(context)) }

    /** Live readings arrive every second; the file is written at most every 20 s (and when a drive ends). */
    private fun save(context: Context, immediate: Boolean) {
        if (immediate) { saveJob?.cancel(); writeNow(context); return }
        if (saveJob?.isActive == true) return
        saveJob = scope.launch { delay(20_000); writeNow(context) }
    }

    fun update(context: Context, immediate: Boolean = true, change: (Telemetry.State) -> Telemetry.State) {
        store.update(file(context), persist = false, change = change)
        save(context, immediate)
        com.blacklab.app.widgets.CarWidget.refreshSoon(context)
    }

    // ---------------------------------------------------------------- simulator

    /** True while a practice drive runs: nothing it reports touches the Garage, and the car's real values come back afterwards. */
    @Volatile var simulating = false
        private set
    private var simBackup: Telemetry.State? = null

    fun beginSim(context: Context) = synchronized(this) {
        if (simulating) return
        simBackup = load(context)
        simulating = true
    }

    fun endSim(context: Context) {
        val b = synchronized(this) { simBackup.also { simBackup = null; simulating = false } } ?: return
        update(context) { it.copy(snapshot = b.snapshot, dtcs = b.dtcs, parked = b.parked, lastSession = b.lastSession, pendingFill = b.pendingFill) }
    }

    // ---------------------------------------------------------------- fill-ups

    fun clearPendingFill(context: Context, loggedAt: Long = System.currentTimeMillis()) {
        val p = load(context).pendingFill ?: return
        if (loggedAt >= p.time - 6 * 3_600_000L) update(context) { it.copy(pendingFill = null) }
    }

    private fun checkFill(context: Context, before: Map<String, Telemetry.Metric>, metrics: List<Telemetry.Metric>) {
        val s = load(context)
        if (simulating || !s.link.detectFills) return
        val now = metrics.firstOrNull { it.key == "fuel_pct" } ?: return
        val prev = before["fuel_pct"] ?: return
        if (now.time < prev.time) return
        val g = Garage.load(context).vehicle
        val gal = Telemetry.detectFill(prev.num, now.num, g.tankGallons) ?: return
        val station = s.parked?.takeIf { now.time - it.time < 3 * 3_600_000L }?.place?.substringBefore(",").orEmpty()
        update(context) { it.copy(pendingFill = Telemetry.PendingFill(now.time, prev.num ?: 0.0, now.num ?: 0.0, gal, g.odometerNow.takeIf { o -> o > 0 }, station)) }
        alert(context, "car_fill", "Filled up? About %.1f gal".format(gal),
            "The fuel gauge went from %.0f%% to %.0f%%%s. Tap to log it with the price, so MPG and fuel costs stay right.".format(prev.num ?: 0.0, now.num ?: 0.0, if (station.isNotBlank()) " at $station" else ""))
    }

    fun flush(context: Context) = save(context, true)

    fun setLink(context: Context, change: (Telemetry.Link) -> Telemetry.Link) = update(context) { it.copy(link = change(it.link)) }

    internal fun setLive(change: (Live) -> Live) { _live.value = change(_live.value) }

    /** Odometer readings the session should know about (so GPS miles aren't double-counted). */
    internal var onOdometer: ((Int) -> Unit)? = null

    /**
     * Records readings from any source. Side effects: the odometer updates the Garage, the VIN fills
     * the profile, and a newly lit warning raises a notification.
     */
    fun put(context: Context, metrics: List<Telemetry.Metric>) {
        if (metrics.isEmpty()) return
        val before = load(context).snapshot
        update(context, immediate = false) { it.copy(snapshot = Telemetry.merge(it.snapshot, metrics)) }
        checkFill(context, before, metrics)
        if (simulating) {
            metrics.filter { Telemetry.isAlert(it) && it.key !in setOf("fuel_pct", "coolant", "battery_v", "dtc_count") }.forEach { m ->
                if (before[m.key]?.let(Telemetry::isAlert) != true) alert(context, "car_${m.key}", "Simulated · ${Telemetry.def(m.key)?.label ?: m.key}: ${Telemetry.format(m)}", "From the drive simulator.")
            }
            return
        }
        metrics.firstOrNull { it.key == "odometer" }?.num?.let { miles ->
            val m = Math.round(miles).toInt()
            val g = Garage.load(context)
            if (m > g.vehicle.odometer) { Garage.setOdometer(context, m, metrics.first { it.key == "odometer" }.time); onOdometer?.invoke(m) }
        }
        metrics.firstOrNull { it.key == "vin" }?.text?.let { vin ->
            val v = Garage.load(context).vehicle
            if (v.vin.isBlank() && vin.length == 17) { Garage.setVehicle(context, v.copy(vin = vin)); Nhtsa.refreshSoon(context) }
        }
        // Warnings that just turned on (not the ones we already told you about)
        metrics.filter { Telemetry.isAlert(it) && it.key !in setOf("fuel_pct", "coolant", "battery_v", "dtc_count") }.forEach { m ->
            val prev = before[m.key]
            if (prev == null || !Telemetry.isAlert(prev)) alert(context, "car_${m.key}", "${Telemetry.def(m.key)?.label ?: m.key}: ${Telemetry.format(m)}", "Reported by ${m.source.label}.")
        }
    }

    fun putCodes(context: Context, stored: List<String>, pending: List<String>, permanent: List<String>) {
        var added: List<Telemetry.Dtc> = emptyList()
        update(context) { s ->
            val (all, new) = Telemetry.mergeDtcs(s.dtcs, stored, pending, permanent, System.currentTimeMillis())
            added = new
            s.copy(dtcs = all, snapshot = Telemetry.merge(s.snapshot, listOfNotNull(Telemetry.num("dtc_count", stored.size.toDouble(), Telemetry.Source.OBD))))
        }
        if (added.isNotEmpty()) alert(context, "car_dtc", (if (simulating) "Simulated · " else "") + if (added.size == 1) "Trouble code ${added[0].code}" else "${added.size} new trouble codes",
            added.joinToString("\n") { "${it.code} — ${Dtcs.describe(it.code)}" + if (it.kind != "stored") " (${it.kind})" else "" })
    }

    @SuppressLint("MissingPermission")
    fun alert(context: Context, tag: String, title: String, text: String) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val n = NotificationCompat.Builder(context, CH_VEHICLE)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).setColor(accent(context))
            .setContentIntent(Notifications.openAppIntent(context, "garage", tag.hashCode())).build()
        runCatching { nm.notify(tag, 1, n) }
    }

    /** Your current account color, so car notifications match the app. */
    fun accent(context: Context): Int = runCatching {
        val s = context.app.settings.current
        com.blacklab.app.ui.theme.ThemeChoice.from(s).palette(s.activeProfile).accent(true).let { c ->
            android.graphics.Color.argb(255, (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())
        }
    }.getOrDefault(0xFF2BD9A8.toInt())

    // ---------------------------------------------------------------- Bluetooth

    fun btGranted(context: Context) = ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    data class BtDevice(val address: String, val name: String, val isCar: Boolean, val looksObd: Boolean)

    private val OBD_NAMES = Regex("obd|elm|v-?link|vgate|icar|veepeak|obdlink|konnwei|carista|bluedriver|kiwi|scan", RegexOption.IGNORE_CASE)
    private val CAR_NAMES = Regex("santa ?fe|hyundai|my car|car ?multimedia|car ?audio|uvo|bluelink|ccnc|gen5|navigation", RegexOption.IGNORE_CASE)

    @SuppressLint("MissingPermission")
    fun looksLikeCar(d: BluetoothDevice): Boolean = runCatching {
        @SuppressLint("MissingPermission") val name = d.name.orEmpty()
        val cls = d.bluetoothClass
        (cls != null && (cls.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO || cls.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE && CAR_NAMES.containsMatchIn(name))) ||
            CAR_NAMES.containsMatchIn(name)
    }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    fun bonded(context: Context): List<BtDevice> {
        if (!btGranted(context)) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return runCatching { adapter.bondedDevices.orEmpty() }.getOrDefault(emptySet()).map { d ->
            val name = runCatching { d.alias ?: d.name }.getOrNull().orEmpty().ifBlank { d.address }
            BtDevice(d.address, name, looksLikeCar(d), OBD_NAMES.containsMatchIn(name))
        }.sortedWith(compareByDescending<BtDevice> { it.isCar }.thenByDescending { it.looksObd }.thenBy { it.name.lowercase() })
    }

    // ---------------------------------------------------------------- sessions

    /** The phone connected to something over Bluetooth: start a drive if it's the car. */
    @SuppressLint("MissingPermission")
    fun onBluetoothConnected(context: Context, device: BluetoothDevice) {
        val s = load(context)
        val link = s.link
        if (!link.autoTrips) return
        if (device.address.equals(link.obdAddress, true)) return
        val name = runCatching { device.alias ?: device.name }.getOrNull().orEmpty()
        when {
            link.carAddress.isNotBlank() && device.address.equals(link.carAddress, true) -> start(context, "Car Bluetooth", name.ifBlank { link.carName })
            link.carAddress.isBlank() && looksLikeCar(device) -> {
                // First time we see the car: remember it so trips log from now on
                setLink(context) { it.copy(carAddress = device.address, carName = name) }
                alert(context, "car_found", "Found your car: ${name.ifBlank { device.address }}", "BlackLab will log trips and read the car every time the phone connects to it. Change this in Garage › Car connection.")
                start(context, "Car Bluetooth", name)
            }
        }
    }

    fun onBluetoothDisconnected(context: Context, address: String) {
        val link = load(context).link
        if (link.carAddress.isNotBlank() && address.equals(link.carAddress, true)) stop(context, "Car Bluetooth")
    }

    /** A four-minute practice drive from the simulator (the app must be open; nothing it reports touches the Garage). */
    fun simulate(context: Context, withCode: Boolean) {
        if (_live.value.active) return
        val i = Intent(context, DriveService::class.java).setAction(DriveService.ACTION_START).putExtra("via", "Simulator").putExtra("sim", true).putExtra("simCode", withCode)
        ContextCompat.startForegroundService(context, i)
    }

    @SuppressLint("MissingPermission")
    fun start(context: Context, via: String, carName: String = "") {
        val i = Intent(context, DriveService::class.java).setAction(DriveService.ACTION_START).putExtra("via", via).putExtra("car", carName)
        runCatching { ContextCompat.startForegroundService(context, i) }.onFailure {
            // Android blocked a background start (e.g. no companion pairing yet): offer a tap to start
            val n = NotificationCompat.Builder(context, CH_VEHICLE).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Connected to ${carName.ifBlank { "your car" }}")
                .setContentText("Tap to log this drive. Pair the car in Garage › Car connection so this happens automatically.")
                .setAutoCancel(true)
                .setContentIntent(android.app.PendingIntent.getForegroundService(context, 7701, i, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT))
                .build()
            runCatching { NotificationManagerCompat.from(context).notify("car_start", 1, n) }
        }
    }

    fun stop(context: Context, via: String, now: Boolean = false) {
        if (!_live.value.active) return
        runCatching { context.startService(Intent(context, DriveService::class.java).setAction(if (now) DriveService.ACTION_END_NOW else DriveService.ACTION_STOP).putExtra("via", via)) }
    }

    /** Android Auto connected or went away (the car screen's session lifecycle). */
    fun onAndroidAuto(context: Context, connected: Boolean) {
        setLive { it.copy(androidAuto = connected) }
        if (connected && load(context).link.autoTrips) start(context, "Android Auto", Garage.load(context).vehicle.name)
        if (!connected) stop(context, "Android Auto")
    }

    // ---------------------------------------------------------------- Bluelink

    suspend fun refreshBluelink(context: Context): String {
        val s = load(context)
        val secrets = context.app.secrets
        if (!s.link.bluelink || !Bluelink.configured(secrets)) return "Bluelink isn't set up"
        setLive { it.copy(bluelink = "Checking Bluelink…") }
        return runCatching {
            val r = Bluelink.fetch(secrets, s.link.bluelinkVin.ifBlank { Garage.load(context).vehicle.vin })
            put(context, r.metrics)
            r.parked?.let { p ->
                val place = runCatching { com.blacklab.app.core.Geo.address(context, p.lat, p.lon) }.getOrNull().orEmpty()
                update(context) { st -> if (st.parked == null || p.time >= st.parked.time) st.copy(parked = p.copy(place = place)) else st }
            }
            if (s.link.bluelinkVin.isBlank()) setLink(context) { it.copy(bluelinkVin = r.car.vin) }
            "Bluelink: status from ${Telemetry.ago(r.statusAt)}"
        }.getOrElse { e -> "Bluelink: ${e.message ?: "couldn't connect"}" }.also { msg -> setLive { it.copy(bluelink = msg) }; flush(context) }
    }

    // ---------------------------------------------------------------- trips

    fun setTrip(context: Context, id: String, change: (Telemetry.Trip) -> Telemetry.Trip) = update(context) { s -> s.copy(trips = s.trips.map { if (it.id == id) change(it) else it }) }
    fun deleteTrip(context: Context, id: String) = update(context) { s -> s.copy(trips = s.trips.filterNot { it.id == id }) }
    fun addRule(context: Context, rule: Telemetry.Rule) = setLink(context) { it.copy(rules = it.rules.filterNot { r -> r.id == rule.id } + rule) }
    fun deleteRule(context: Context, id: String) = setLink(context) { it.copy(rules = it.rules.filterNot { r -> r.id == id }) }

    /** Tags earlier trips that still have the default tag and no note. Returns how many changed. */
    fun applyRules(context: Context): Int {
        var n = 0
        update(context) { s ->
            s.copy(trips = s.trips.map { t ->
                if (t.end == null || t.simulated || t.rule.isNotBlank() || t.note.isNotBlank() || t.purpose != s.link.defaultPurpose) t
                else Telemetry.matchRule(t, s.link.rules)?.takeIf { it.purpose != t.purpose }?.let { r -> n++; t.copy(purpose = r.purpose, rule = r.name) } ?: t
            })
        }
        return n
    }

    fun clearCodes(context: Context) = update(context) { s -> s.copy(dtcs = s.dtcs.map { it.copy(active = false) }) }

    /** Writes the trip log as CSV into the cache and returns it for sharing. */
    fun exportCsv(context: Context): File {
        val f = File(context.cacheDir, "exports/BlackLab-mileage-log.csv")
        f.parentFile?.mkdirs()
        f.writeText(Telemetry.tripsCsv(load(context).trips))
        return f
    }

    /** One spoken line for the assistant / car screen. */
    fun summary(context: Context): String = Telemetry.summary(load(context))
}

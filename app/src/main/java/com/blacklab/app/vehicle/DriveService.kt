package com.blacklab.app.vehicle

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.blacklab.app.R
import com.blacklab.app.core.Geo
import com.blacklab.app.reminders.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * One drive, from the moment the phone connects to the car until it disconnects (with a two-minute
 * grace period so a quick stop or a Bluetooth hiccup doesn't split the trip). Logs distance, time,
 * speed and driving events with the phone's GPS, runs the OBD-II reader, pulls Bluelink at the start,
 * and when the drive ends saves the trip, where you parked, and the miles toward maintenance.
 */
class DriveService : Service() {
    companion object {
        const val ACTION_START = "com.blacklab.app.drive.START"
        const val ACTION_STOP = "com.blacklab.app.drive.STOP"
        const val ACTION_END_NOW = "com.blacklab.app.drive.END_NOW"
        private const val NOTIF_ID = 7700
        private const val GRACE_MS = 120_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: Session? = null
    private var endJob: Job? = null
    private var started = System.currentTimeMillis()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val via = intent?.getStringExtra("via").orEmpty().ifBlank { "Car" }
        when (intent?.action) {
            ACTION_START -> {
                if (!foreground()) { stopSelf(); return START_NOT_STICKY }
                endJob?.cancel(); endJob = null
                val s = session ?: Session(this, intent.getStringExtra("car").orEmpty(), intent.getBooleanExtra("sim", false), intent.getBooleanExtra("simCode", false))
                    .also { session = it; started = it.startedAt; it.begin() }
                s.vias += via
                VehicleLink.setLive { it.copy(ending = false, via = s.vias.joinToString(" + ")) }
            }
            ACTION_STOP -> {
                val s = session ?: run { stopSelf(); return START_NOT_STICKY }
                s.vias -= via
                if (s.vias.isEmpty() && endJob == null) {
                    s.stopAt = System.currentTimeMillis()
                    VehicleLink.setLive { it.copy(ending = true) }
                    endJob = scope.launch { delay(GRACE_MS); finish() }
                }
            }
            ACTION_END_NOW -> { if (session == null) stopSelf() else { session?.stopAt = session?.stopAt ?: System.currentTimeMillis(); scope.launch { finish() } } }
            else -> if (session == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun foreground(): Boolean {
        val n = notification("Connected to the car", "Logging this drive")
        val loc = if (has(Manifest.permission.ACCESS_FINE_LOCATION)) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        val bt = if (has(Manifest.permission.BLUETOOTH_CONNECT)) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        for (type in listOf(loc or bt, bt, loc).filter { it != 0 }.distinct()) {
            if (runCatching { startForeground(NOTIF_ID, n, type) }.isSuccess) return true
        }
        return false
    }

    /**
     * The drive notification. On Android 16+ it asks to be a Live Update: it stays at the top of the
     * shade and the lock screen, with the trip miles as a chip in the status bar and a running clock.
     */
    private fun notification(title: String, text: String, chip: String? = null): android.app.Notification {
        val end = android.app.PendingIntent.getService(this, NOTIF_ID, Intent(this, DriveService::class.java).setAction(ACTION_END_NOW),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        val b = android.app.Notification.Builder(this, VehicleLink.CH_DRIVE)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text))
            .setOngoing(true).setOnlyAlertOnce(true).setColor(VehicleLink.accent(this)).setCategory(android.app.Notification.CATEGORY_NAVIGATION)
            .setWhen(started).setShowWhen(true).setUsesChronometer(true)
            .setContentIntent(Notifications.openAppIntent(this, "garage", NOTIF_ID))
            .addAction(android.app.Notification.Action.Builder(null as android.graphics.drawable.Icon?, "End trip", end).build())
        if (android.os.Build.VERSION.SDK_INT >= 36) {
            // Notification.EXTRA_REQUEST_PROMOTED_ONGOING (the Builder setter arrives in a later SDK; the extra is what it sets)
            b.addExtras(android.os.Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
            chip?.let { b.setShortCriticalText(it) }
        }
        return b.build()
    }

    @SuppressLint("MissingPermission")
    internal fun updateNotification(title: String, text: String, chip: String? = null) { runCatching { NotificationManagerCompat.from(this).notify(NOTIF_ID, notification(title, text, chip)) } }

    private suspend fun finish() {
        val s = session ?: return
        session = null
        endJob = null
        runCatching { s.end() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        session?.let { s -> runCatching { s.saveOpen() }; s.release(); if (s.sim) VehicleLink.endSim(applicationContext) }
        session = null
        VehicleLink.setLive { VehicleLink.Live(androidAuto = it.androidAuto) }
        scope.cancel()
        super.onDestroy()
    }

    /** Everything about the drive in progress. */
    private class Session(val svc: DriveService, carName: String, val sim: Boolean = false, val simCode: Boolean = false) : LocationListener {
        val ctx: Context = svc.applicationContext
        val vias = LinkedHashSet<String>()
        var stopAt: Long? = null
        private val job = SupervisorJob()
        private val scope = CoroutineScope(job + Dispatchers.Main.immediate)
        private var trip = Telemetry.Trip(UUID.randomUUID().toString(), System.currentTimeMillis(), car = if (sim) "Simulator" else carName, simulated = sim)
        val startedAt: Long get() = trip.start
        private var last: Location? = null
        private var lastSpeed: Float? = null
        private var lastEventAt = 0L
        private var gpsMeters = 0.0
        private var milesSinceOdo = 0.0
        private var speedMiles = 0.0
        private var fuelGal = 0.0
        private var fuelSeen = false
        private var obd: ObdReader? = null
        private val link = VehicleLink.load(ctx).link
        private val sources: MutableSet<Telemetry.Source> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        fun begin() {
            closeStaleTrips()
            Garage.load(ctx)
            if (sim) VehicleLink.beginSim(ctx)
            val snap = VehicleLink.state.value
            val start = trip.start
            trip = trip.copy(fuelPctStart = snap["fuel_pct"]?.takeIf { start - it.time < 6 * 3_600_000L }?.num, purpose = link.defaultPurpose)
            VehicleLink.setLive { it.copy(active = true, since = start, tripMiles = 0.0, mph = null, sim = sim,
                obd = if (sim) "Starting the simulator…" else if (link.obdAddress.isBlank()) "" else "Waiting for the OBD adapter…", bluelink = "") }
            if (!sim) VehicleLink.put(ctx, listOfNotNull(Telemetry.text("car_bt", trip.car.ifBlank { link.carName }, Telemetry.Source.CAR_BT)))
            VehicleLink.onOdometer = { miles ->
                trip = trip.copy(odoStart = trip.odoStart ?: miles, odoEnd = miles)
                milesSinceOdo = 0.0
            }
            VehicleLink.update(ctx) { it.copy(lastSession = start) }
            if (sim) {
                // The pretend car does the driving; the phone's GPS sitting on a desk would only add noise
                scope.launch(Dispatchers.IO) { runObd() }
                scope.launch {
                    delay(((SimCar.DURATION_S + 6) * 1000).toLong())
                    runCatching { svc.startService(Intent(svc, DriveService::class.java).setAction(ACTION_END_NOW)) }
                }
            } else {
                startGps()
                if (link.obdAddress.isNotBlank()) scope.launch(Dispatchers.IO) { runObd() }
                if (link.bluelink) scope.launch(Dispatchers.IO) { VehicleLink.refreshBluelink(ctx) }
            }
            scope.launch { tick() }
        }

        /** A drive the app never finished (the phone restarted mid-trip): close it where it left off. */
        private fun closeStaleTrips() = VehicleLink.update(ctx) { s ->
            s.copy(trips = s.trips.map { t -> if (t.end == null) t.copy(end = t.start + (t.movingSec + t.idleSec) * 1000) else t })
        }

        @SuppressLint("MissingPermission")
        private fun startGps() {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
            val lm = ctx.getSystemService(LocationManager::class.java) ?: return
            val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER).firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) } ?: return
            runCatching { lm.requestLocationUpdates(provider, 2000L, 0f, this, Looper.getMainLooper()) }
            sources += Telemetry.Source.PHONE
        }

        override fun onLocationChanged(l: Location) {
            if (l.hasAccuracy() && l.accuracy > 35f) return
            val prev = last
            if (prev == null) {
                last = l
                if (trip.startLat == null) {
                    trip = trip.copy(startLat = l.latitude, startLon = l.longitude)
                    if (link.recordPlaces) scope.launch { Geo.address(ctx, l.latitude, l.longitude)?.let { a -> trip = trip.copy(startPlace = a) } }
                }
                return
            }
            val dt = (l.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1e9
            if (dt <= 0) return
            val d = prev.distanceTo(l).toDouble()
            val v = if (l.hasSpeed()) l.speed.toDouble() else d / dt
            if (d >= 3 && v > 0.8 && d / dt < 70) { gpsMeters += d; milesSinceOdo += Telemetry.metersToMi(d) }
            val mph = Telemetry.mpsToMph(v)
            trip = if (v > 1.0) trip.copy(movingSec = trip.movingSec + dt.toLong().coerceAtMost(30), maxMph = maxOf(trip.maxMph, mph))
            else trip.copy(idleSec = trip.idleSec + dt.toLong().coerceAtMost(30))
            lastSpeed?.let { ps ->
                val accel = (v - ps) / dt
                val now = System.currentTimeMillis()
                if (now - lastEventAt > 5000 && dt < 4) {
                    if (accel <= -3.9) { trip = trip.copy(hardBrakes = trip.hardBrakes + 1); lastEventAt = now }
                    else if (accel >= 3.5) { trip = trip.copy(hardAccels = trip.hardAccels + 1); lastEventAt = now }
                }
            }
            lastSpeed = v.toFloat()
            last = l
            VehicleLink.setLive { it.copy(mph = mph) }
        }

        /** Every 2 s: integrate the car's own speed and fuel flow, refresh the live line, and save every minute. */
        private suspend fun tick() {
            var n = 0
            var prev = System.currentTimeMillis()
            while (scope.isActive) {
                delay(2000)
                val now = System.currentTimeMillis()
                val dt = (now - prev) / 3_600_000.0
                prev = now
                val snap = VehicleLink.state.value
                snap["speed"]?.takeIf { now - it.time < 5000 && it.source != Telemetry.Source.PHONE }?.num?.let { mph ->
                    speedMiles += mph * dt
                    if (gpsMeters == 0.0) milesSinceOdo += mph * dt
                    sources += snap["speed"]!!.source
                    if (last == null) VehicleLink.setLive { it.copy(mph = mph) }
                }
                snap["fuel_rate"]?.takeIf { now - it.time < 5000 }?.num?.let { g -> fuelGal += g * dt; fuelSeen = true }
                trip = trip.copy(gpsMiles = Telemetry.metersToMi(gpsMeters), speedMiles = speedMiles, fuelGal = if (fuelSeen) fuelGal else null, sources = sources.toSet())
                VehicleLink.setLive { it.copy(tripMiles = trip.miles) }
                if (n % 5 == 0) {
                    val live = VehicleLink.live.value
                    val line = listOfNotNull("%.1f mi".format(trip.miles), Telemetry.duration(trip.durationSec),
                        snap["inst_mpg"]?.takeIf { now - it.time < 10_000 }?.let { "${Telemetry.format(it)}" },
                        live.obd.takeIf { it.isNotBlank() && !it.startsWith("OBD-II connected") }).joinToString(" · ")
                    svc.updateNotification(if (live.ending) "Drive ending…" else (if (sim) "Simulated drive" else "Driving · ${trip.car.ifBlank { "your car" }}"), line,
                        "%.1f mi".format(trip.miles))
                }
                if (++n % 30 == 0) saveOpen()
            }
        }

        private suspend fun runObd() {
            if (sim) {
                val r = ObdReader(ctx, link, onStatus = { s -> VehicleLink.setLive { it.copy(obd = s) } }, onMetrics = { m -> VehicleLink.put(ctx, m) },
                    onCodes = { a, b, c -> VehicleLink.putCodes(ctx, a, b, c) }, simPort = SimObdPort(simCode))
                obd = r
                try { if (r.connect()) { sources += Telemetry.Source.OBD; r.readOnce(); r.poll() } } finally { r.close(); obd = null }
                return
            }
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                VehicleLink.setLive { it.copy(obd = "Allow Nearby devices to use the OBD adapter") }; return
            }
            var first = true
            while (scope.isActive) {
                val r = ObdReader(ctx, link, onStatus = { s -> VehicleLink.setLive { it.copy(obd = s) } },
                    onMetrics = { m -> VehicleLink.put(ctx, m) },
                    onCodes = { a, b, c -> VehicleLink.putCodes(ctx, a, b, c) })
                obd = r
                try {
                    if (r.connect()) {
                        sources += Telemetry.Source.OBD
                        if (first) { r.readOnce(); first = false }
                        r.poll()
                    }
                } catch (_: Exception) {
                    VehicleLink.setLive { it.copy(obd = "OBD adapter disconnected") }
                } finally { r.close(); obd = null }
                delay(30_000)
            }
        }

        /** Saves the drive in progress so a crash or restart doesn't lose it. */
        fun saveOpen() {
            val t = trip
            VehicleLink.update(ctx) { s -> s.copy(trips = listOf(t) + s.trips.filterNot { it.id == t.id }) }
        }

        fun release() {
            VehicleLink.onOdometer = null
            runCatching { ctx.getSystemService(LocationManager::class.java)?.removeUpdates(this) }
            obd?.close()
            job.cancel()
        }

        suspend fun end() {
            val endAt = stopAt ?: System.currentTimeMillis()
            val snap = VehicleLink.state.value
            val fix = last
            release()
            trip = trip.copy(end = endAt, gpsMiles = Telemetry.metersToMi(gpsMeters), speedMiles = speedMiles, fuelGal = if (fuelSeen) fuelGal else null, sources = sources.toSet(),
                fuelPctEnd = snap["fuel_pct"]?.takeIf { endAt - it.time < 15 * 60_000L && it.time >= trip.start }?.num, endLat = fix?.latitude, endLon = fix?.longitude)
            val place = if (fix != null && link.recordPlaces) withTimeoutOrNull(8000) { Geo.address(ctx, fix.latitude, fix.longitude) }.orEmpty() else ""
            trip = trip.copy(endPlace = place)
            if (sim) trip = trip.copy(note = "Simulated drive (practice; not counted in totals)", movingSec = (SimCar.DURATION_S * 0.7).toLong(), idleSec = (SimCar.DURATION_S * 0.3).toLong())
            else if (trip.purpose == link.defaultPurpose) Telemetry.matchRule(trip, link.rules)?.let { r -> trip = trip.copy(purpose = r.purpose, rule = r.name) }
            val keep = trip.miles >= 0.2 || trip.movingSec >= 90
            val t = trip
            VehicleLink.update(ctx) { s ->
                val rest = s.trips.filterNot { it.id == t.id }
                s.copy(trips = (if (keep) listOf(t) + rest else rest).take(3000),
                    parked = if (sim) s.parked else fix?.let { Telemetry.Parked(it.latitude, it.longitude, place, endAt, Telemetry.Source.PHONE) } ?: s.parked)
            }
            if (sim) VehicleLink.endSim(ctx)
            if (!sim && milesSinceOdo > 0.5) Garage.addDrivenMiles(ctx, milesSinceOdo)
            VehicleLink.flush(ctx)
            if (keep && (link.tripSummary || sim)) summarize(t)
            if (link.bluelink && !sim) {
                // The car uploads its status a few minutes after it's switched off: read it then
                WorkManager.getInstance(ctx).enqueueUniqueWork("bluelink_after_drive", ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<BluelinkWorker>().setInitialDelay(5, TimeUnit.MINUTES).build())
            }
            VehicleLink.setLive { VehicleLink.Live(androidAuto = it.androidAuto) }
        }

        private fun summarize(t: Telemetry.Trip) {
            val g = Garage.load(ctx)
            val mpg = t.mpg(g.vehicle.tankGallons)
            val next = Garage.due(g).firstOrNull { it.status == Garage.Status.OVERDUE || it.status == Garage.Status.DUE_SOON }
            val title = (if (t.simulated) "Simulated drive: " else "Drive logged: ") + "%.1f mi in %s".format(t.miles, Telemetry.duration(t.durationSec))
            val lines = listOfNotNull(
                listOf(t.startPlace, t.endPlace).filter { it.isNotBlank() }.takeIf { it.size == 2 }?.joinToString(" → "),
                listOfNotNull(mpg?.let { "%.1f mpg".format(it) }, t.avgMph?.let { "avg %.0f mph".format(it) }, "top %.0f mph".format(t.maxMph).takeIf { t.maxMph > 5 },
                    (t.hardBrakes + t.hardAccels).takeIf { it > 0 }?.let { "$it hard brake/accel" }).joinToString(" · ").ifBlank { null },
                t.rule.takeIf { it.isNotBlank() }?.let { "Tagged ${t.purpose.label} (rule: $it)" },
                g.vehicle.odometerNow.takeIf { it > 0 }?.let { "Odometer ${if (g.vehicle.estMiles > 0) "≈ " else ""}${Garage.fmtMiles(it)}" },
                next?.let { "${it.item.name} ${it.describe()}" },
            )
            VehicleLink.alert(ctx, "trip_summary", title, lines.joinToString("\n").ifBlank { "Saved to your trip log." })
        }
    }
}

/** Reads Bluelink a few minutes after a drive, once the car has reported where and how it was left. */
class BluelinkWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val msg = VehicleLink.refreshBluelink(applicationContext)
        val s = VehicleLink.state.value
        // Left unlocked or open? Say so.
        val open = listOfNotNull(
            s["locked"]?.takeIf { it.bool == false && abs(System.currentTimeMillis() - it.time) < 30 * 60_000L }?.let { "doors unlocked" },
            s["windows_open"]?.takeIf { it.text != null && it.text != "None" }?.let { "windows open (${it.text})" },
            s["trunk_open"]?.takeIf { it.bool == true }?.let { "liftgate open" },
        )
        if (open.isNotEmpty()) VehicleLink.alert(applicationContext, "car_left_open", "Your car: " + open.joinToString(", "), "From Bluelink's latest report. ${s.parked?.place?.takeIf { it.isNotBlank() }?.let { "Parked at $it." } ?: ""}")
        return if (msg.contains("rate-limit")) Result.retry() else Result.success()
    }
}

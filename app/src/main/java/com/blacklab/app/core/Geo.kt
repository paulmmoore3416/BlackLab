package com.blacklab.app.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

/** A resolved place: coordinates plus an optional human-readable address. */
data class GeoTag(
    val lat: Double,
    val lon: Double,
    val altitude: Double? = null,
    val accuracyM: Float? = null,
    val address: String? = null,
    val time: Long = System.currentTimeMillis(),
) {
    val coords: String get() = String.format(Locale.US, "%.6f, %.6f", lat, lon)
    val label: String get() = address?.takeIf { it.isNotBlank() } ?: coords
    val mapUrl: String get() = "geo:$lat,$lon?q=$lat,$lon"

    fun toLocation(): Location = Location(LocationManager.GPS_PROVIDER).apply {
        latitude = lat; longitude = lon; time = this@GeoTag.time
        this@GeoTag.altitude?.let { altitude = it }
        accuracyM?.let { accuracy = it }
    }

    companion object {
        fun of(l: Location, address: String? = null) = GeoTag(
            l.latitude, l.longitude, l.altitude.takeIf { l.hasAltitude() }, l.accuracy.takeIf { l.hasAccuracy() }, address, l.time,
        )
    }
}

/**
 * Location without Google Play services: the platform fused provider (falls back to GPS/network),
 * plus platform reverse geocoding. Everything stays on the device except the optional geocoder lookup.
 */
object Geo {
    private val executor = Executors.newSingleThreadExecutor()

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    val permissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun providers(lm: LocationManager): List<String> =
        listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.hasProvider(it) && lm.isProviderEnabled(it) }.getOrDefault(false) }

    @SuppressLint("MissingPermission")
    fun lastKnown(ctx: Context): Location? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        return providers(lm).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    }

    /** A fresh fix (or the last known one if no fix arrives within [timeoutMs]). */
    @SuppressLint("MissingPermission")
    suspend fun current(ctx: Context, timeoutMs: Long = 8000): Location? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val provider = providers(lm).firstOrNull() ?: return lastKnown(ctx)
        val fresh = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Location?> { cont ->
                val cancel = CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                runCatching { lm.getCurrentLocation(provider, cancel, executor) { loc -> if (cont.isActive) cont.resume(loc) } }
                    .onFailure { if (cont.isActive) cont.resume(null) }
            }
        }
        return fresh ?: lastKnown(ctx)
    }

    /** Reverse-geocode to a single-line street address. Returns null offline or when unavailable. */
    suspend fun address(ctx: Context, lat: Double, lon: Double): String? {
        if (!Geocoder.isPresent()) return null
        val result = withTimeoutOrNull(6000) {
            suspendCancellableCoroutine<List<Address>?> { cont ->
                runCatching {
                    Geocoder(ctx, Locale.getDefault()).getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) { if (cont.isActive) cont.resume(addresses) }
                        override fun onError(errorMessage: String?) { if (cont.isActive) cont.resume(null) }
                    })
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }
        val a = result?.firstOrNull() ?: return null
        return (0..a.maxAddressLineIndex).mapNotNull { a.getAddressLine(it) }.joinToString(", ").ifBlank {
            listOfNotNull(a.thoroughfare, a.locality, a.adminArea).joinToString(", ")
        }.ifBlank { null }
    }

    suspend fun tag(ctx: Context, withAddress: Boolean = true, timeoutMs: Long = 8000): GeoTag? {
        val l = current(ctx, timeoutMs) ?: return null
        return GeoTag.of(l, if (withAddress) address(ctx, l.latitude, l.longitude) else null)
    }

    fun compass(deg: Float): String {
        val dirs = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        return dirs[(((deg % 360) + 360) % 360 / 22.5f).roundToInt() % 16]
    }

    fun dms(v: Double, pos: String, neg: String): String {
        val a = abs(v)
        val d = a.toInt(); val mFull = (a - d) * 60; val m = mFull.toInt(); val s = (mFull - m) * 60
        return String.format(Locale.US, "%d°%02d'%04.1f\"%s", d, m, s, if (v >= 0) pos else neg)
    }
}

/**
 * Keeps a live location fix while a screen (camera) is open so each capture is tagged instantly,
 * and resolves the street address in the background.
 */
class LocationTracker(private val ctx: Context) {
    @Volatile var latest: GeoTag? = null
        private set
    private var listener: LocationListener? = null
    private var lastGeocoded: Location? = null

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope) {
        if (!Geo.hasPermission(ctx) || listener != null) return
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        Geo.lastKnown(ctx)?.let { latest = GeoTag.of(it) }
        val l = LocationListener { loc ->
            val prev = latest
            latest = GeoTag.of(loc, prev?.address?.takeIf { lastGeocoded?.distanceTo(loc)?.let { d -> d < 60 } == true })
            if (lastGeocoded == null || lastGeocoded!!.distanceTo(loc) >= 60) {
                lastGeocoded = loc
                scope.launch(Dispatchers.IO) {
                    val a = Geo.address(ctx, loc.latitude, loc.longitude)
                    latest = latest?.copy(address = a)
                }
            }
        }
        listener = l
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { runCatching { lm.hasProvider(it) && lm.isProviderEnabled(it) }.getOrDefault(false) } ?: return
        runCatching { lm.requestLocationUpdates(provider, 3000L, 2f, l, Looper.getMainLooper()) }
        if (latest?.address == null) latest?.let { t ->
            scope.launch(Dispatchers.IO) { latest = latest?.copy(address = Geo.address(ctx, t.lat, t.lon)) }
        }
    }

    fun stop() {
        val l = listener ?: return
        runCatching { ctx.getSystemService(LocationManager::class.java)?.removeUpdates(l) }
        listener = null
    }
}

/**
 * Orientation from the rotation-vector sensor: where the back camera points (true-north heading),
 * its pitch, and the device roll (for the level indicator). Angles in degrees.
 */
class OrientationTracker(ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(SensorManager::class.java)
    private val r = FloatArray(9)
    @Volatile var headingDeg: Float? = null
        private set
    @Volatile var pitchDeg: Float = 0f
        private set
    @Volatile var rollDeg: Float = 0f
        private set
    var declination: Float = 0f

    fun start() {
        val s = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() { sm?.unregisterListener(this) }

    fun setLocation(tag: GeoTag?) {
        tag ?: return
        declination = GeomagneticField(tag.lat.toFloat(), tag.lon.toFloat(), (tag.altitude ?: 0.0).toFloat(), tag.time).declination
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(r, event.values)
        // Back camera looks along the device's -Z axis; its world direction is -(third column of R).
        val east = -r[2]; val north = -r[5]; val up = -r[8]
        headingDeg = ((Math.toDegrees(atan2(east, north).toDouble()).toFloat() + declination) % 360 + 360) % 360
        pitchDeg = Math.toDegrees(kotlin.math.asin(up.coerceIn(-1f, 1f).toDouble())).toFloat()
        rollDeg = Math.toDegrees(kotlin.math.asin(r[6].coerceIn(-1f, 1f).toDouble())).toFloat()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

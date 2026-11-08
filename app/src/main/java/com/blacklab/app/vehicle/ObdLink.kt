package com.blacklab.app.vehicle

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** A byte pipe to an ELM327-compatible adapter. [send] returns everything up to the `>` prompt. */
interface ObdPort {
    suspend fun send(cmd: String, timeoutMs: Long = 2500): String
    fun close()
}

/** Classic Bluetooth adapters (most ELM327 clones, OBDLink MX/LX, Veepeak Mini): serial port profile. */
@SuppressLint("MissingPermission")
class ClassicObdPort private constructor(private val socket: BluetoothSocket) : ObdPort {
    companion object {
        private val SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        suspend fun open(device: BluetoothDevice): ClassicObdPort = withContext(Dispatchers.IO) {
            val s = runCatching { device.createRfcommSocketToServiceRecord(SPP).also { it.connect() } }
                .recoverCatching { device.createInsecureRfcommSocketToServiceRecord(SPP).also { it.connect() } }
                .getOrThrow()
            ClassicObdPort(s)
        }
    }

    override suspend fun send(cmd: String, timeoutMs: Long): String = withContext(Dispatchers.IO) {
        val input = socket.inputStream
        while (input.available() > 0) input.read()
        socket.outputStream.write((cmd + "\r").toByteArray(Charsets.US_ASCII)); socket.outputStream.flush()
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                val c = input.read()
                if (c < 0) break
                if (c.toChar() == '>') return@withContext sb.toString()
                sb.append(c.toChar())
            } else delay(10)
        }
        sb.toString()
    }

    override fun close() { runCatching { socket.close() } }
}

/**
 * Bluetooth LE adapters (Veepeak OBDCheck BLE, Vgate iCar Pro BLE, OBDLink CX, most "BLE 4.0" clones).
 * They expose a UART-like service: one characteristic to write commands, one that notifies replies.
 */
@SuppressLint("MissingPermission")
class BleObdPort private constructor(private val gatt: BluetoothGatt, private val buffer: StringBuilder) : ObdPort {
    private lateinit var tx: BluetoothGattCharacteristic

    companion object {
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        /** Known adapter services first; anything else with a write + notify pair is tried after. */
        private val PREFERRED = listOf("0000fff0", "0000ffe0", "e7810a71-73ae-499d-8c15-faa9aef0c3f2", "000018f0", "6e400001-b5a3-f393-e0a9-e50e24dcca9e")

        suspend fun open(context: Context, device: BluetoothDevice): BleObdPort {
            val buffer = StringBuilder()
            val ready = CompletableDeferred<BluetoothGatt?>()
            val written = arrayOf<CompletableDeferred<Unit>?>(null)
            val cb = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
                    else if (newState == BluetoothProfile.STATE_DISCONNECTED) { if (!ready.isCompleted) ready.complete(null); synchronized(buffer) { buffer.append("\u0000DISCONNECTED>") } }
                }
                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { ready.complete(if (status == BluetoothGatt.GATT_SUCCESS) g else null) }
                override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                    synchronized(buffer) { buffer.append(String(value, Charsets.US_ASCII)) }
                }
                override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { written[0]?.complete(Unit) }
                override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { written[0]?.complete(Unit) }
            }
            val gatt = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
            val g = withTimeoutOrNull(15_000) { ready.await() } ?: run { gatt.close(); error("The OBD adapter didn't answer over Bluetooth LE") }
            val services = g.services.sortedBy { s -> PREFERRED.indexOfFirst { s.uuid.toString().startsWith(it) }.let { if (it < 0) 99 else it } }
            var rx: BluetoothGattCharacteristic? = null
            var tx: BluetoothGattCharacteristic? = null
            for (s in services) {
                val n = s.characteristics.firstOrNull { it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 }
                val w = s.characteristics.firstOrNull { it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 }
                if (n != null && w != null) { rx = n; tx = w; break }
            }
            if (rx == null || tx == null) { g.close(); error("This Bluetooth LE device doesn't look like an OBD adapter") }
            g.setCharacteristicNotification(rx, true)
            rx.getDescriptor(CCCD)?.let { d ->
                written[0] = CompletableDeferred()
                val v = if (rx.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                g.writeDescriptor(d, v)
                withTimeoutOrNull(3000) { written[0]!!.await() }
            }
            return BleObdPort(g, buffer).also { it.tx = tx; it.written = written }
        }
    }

    private var written: Array<CompletableDeferred<Unit>?> = arrayOf(null)

    override suspend fun send(cmd: String, timeoutMs: Long): String {
        synchronized(buffer) { buffer.setLength(0) }
        val bytes = (cmd + "\r").toByteArray(Charsets.US_ASCII)
        val type = if (tx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        // Most adapters take 20-byte chunks; OBD commands are shorter than that
        bytes.toList().chunked(20).forEach { chunk ->
            written[0] = CompletableDeferred()
            gatt.writeCharacteristic(tx, chunk.toByteArray(), type)
            if (type == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) withTimeoutOrNull(1000) { written[0]!!.await() }
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && coroutineContext.isActive) {
            synchronized(buffer) {
                val i = buffer.indexOf(">")
                if (i >= 0) return buffer.substring(0, i).also { buffer.delete(0, i + 1) }
            }
            delay(15)
        }
        return synchronized(buffer) { buffer.toString() }
    }

    override fun close() { runCatching { gatt.disconnect() }; runCatching { gatt.close() } }
}

/**
 * Talks to the OBD adapter for one drive: sets it up, reads the VIN, codes and odometer once,
 * then polls live values until cancelled. Every reading goes to [onMetrics].
 */
@SuppressLint("MissingPermission")
class ObdReader(private val context: Context, private val link: Telemetry.Link, private val onStatus: (String) -> Unit, private val onMetrics: (List<Telemetry.Metric>) -> Unit,
                private val onCodes: (stored: List<String>, pending: List<String>, permanent: List<String>) -> Unit,
                /** Talk to this instead of a Bluetooth adapter (the drive simulator). */
                private val simPort: ObdPort? = null) {

    private var port: ObdPort? = null
    private val supported = HashSet<Int>()

    suspend fun connect(): Boolean {
        if (simPort != null) port = simPort else {
            val adapter: BluetoothAdapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
            if (link.obdAddress.isBlank() || !BluetoothAdapter.checkBluetoothAddress(link.obdAddress)) return false
            val device = adapter.getRemoteDevice(link.obdAddress)
            onStatus("Connecting to ${link.obdName.ifBlank { "OBD adapter" }}…")
            port = runCatching { if (link.obdBle) BleObdPort.open(context, device) else ClassicObdPort.open(device) }
                .onFailure { onStatus("OBD adapter not reachable — is it plugged in?") }.getOrNull() ?: return false
        }
        val p = port!!
        p.send("ATZ", 4000); delay(500)
        Obd.INIT.drop(1).forEach { p.send(it, 1500) }
        val first = p.send("0100", 12_000)
        if (Obd.isNoData(first)) { onStatus("Adapter connected, but the car isn't answering — turn the engine on"); return false }
        supported += Obd.supported(first, 0x00)
        for (base in listOf(0x20, 0x40, 0x60, 0x80, 0xA0)) {
            if ((base) !in supported) break
            supported += Obd.supported(p.send("01%02X".format(base)), base)
        }
        onStatus(if (simPort != null) "Simulator connected" else "OBD-II connected")
        return true
    }

    private suspend fun read(pid: Int): Double? {
        if (supported.isNotEmpty() && pid !in supported) return null
        val raw = port?.send("01%02X".format(pid)) ?: return null
        return Obd.response(raw, 0x01, pid)?.let { Obd.decode(pid, it) }
    }

    private fun m(key: String, v: Double?) = Telemetry.num(key, v, Telemetry.Source.OBD)

    suspend fun readOnce() {
        val p = port ?: return
        val out = ArrayList<Telemetry.Metric>()
        Obd.vin(p.send("0902", 5000))?.let { out += Telemetry.Metric("vin", null, it, Telemetry.Source.OBD, System.currentTimeMillis()) }
        Obd.ascii(p.send("090A", 4000), 0x09, 0x0A)?.let { Telemetry.text("ecu", it, Telemetry.Source.OBD)?.let(out::add) }
        if (0x01 in supported || supported.isEmpty()) Obd.response(p.send("0101"), 0x01, 0x01)?.let { Obd.monitor(it) }?.let { (mil, n) ->
            Telemetry.bool("mil", mil, Telemetry.Source.OBD)?.let(out::add); m("dtc_count", n.toDouble())?.let(out::add)
        }
        for (pid in Obd.ONCE.filter { it != 0x01 }) {
            if (supported.isNotEmpty() && pid !in supported) continue
            val raw = p.send("01%02X".format(pid))
            val d = Obd.response(raw, 0x01, pid) ?: continue
            if (pid == 0x51) Obd.fuelType(d[0])?.let { Telemetry.text("fuel_type", it, Telemetry.Source.OBD)?.let(out::add) }
            else Obd.KEYS[pid]?.let { k -> m(k, Obd.decode(pid, d))?.let(out::add) }
        }
        Obd.voltage(p.send("ATRV"))?.let { m("battery_v", it)?.let(out::add) }
        onMetrics(out)
        val stored = Obd.dtcs(p.send("03", 5000), 0x03)
        val pending = Obd.dtcs(p.send("07", 5000), 0x07)
        val permanent = Obd.dtcs(p.send("0A", 5000), 0x0A)
        onCodes(stored, pending, permanent)
    }

    /** Polls until the coroutine is cancelled or the adapter goes away. */
    suspend fun poll() {
        var cycle = 0
        var failures = 0
        while (coroutineContext.isActive && port != null) {
            val out = ArrayList<Telemetry.Metric>()
            val pids = if (cycle % 30 == 0) Obd.FAST + Obd.SLOW else Obd.FAST
            var any = false
            for (pid in pids) {
                if (pid == 0x5E && 0x10 in supported) continue // MAF is enough for fuel flow
                val v = read(pid) ?: continue
                any = true
                Obd.KEYS[pid]?.let { k -> m(k, v)?.let(out::add) }
            }
            if (cycle % 30 == 0) port?.send("ATRV")?.let(Obd::voltage)?.let { m("battery_v", it)?.let(out::add) }
            val mph = out.firstOrNull { it.key == "speed" }?.num
            val gph = out.firstOrNull { it.key == "maf" }?.num?.let(Telemetry::galPerHourFromMaf) ?: out.firstOrNull { it.key == "fuel_rate" }?.num
            gph?.let { g -> m("fuel_rate", g)?.let(out::add) }
            if (mph != null) Telemetry.instantMpg(mph, gph)?.let { m("inst_mpg", it)?.let(out::add) }
            out += listOfNotNull(Telemetry.bool("engine_on", (out.firstOrNull { it.key == "rpm" }?.num ?: 0.0) > 300, Telemetry.Source.OBD))
            if (any) { failures = 0; onMetrics(out) } else if (++failures >= 5) { onStatus("OBD adapter stopped answering"); break }
            cycle++
            delay(1000)
        }
    }

    fun close() { port?.close(); port = null }
}

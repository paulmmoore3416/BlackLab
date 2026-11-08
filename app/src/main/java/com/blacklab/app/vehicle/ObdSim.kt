package com.blacklab.app.vehicle

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A pretend car for testing at your desk: a four-minute drive (idle, town, a stop light, a faster
 * road, parking) answered the way a real ELM327 adapter answers. The drive runs through the real
 * [ObdReader] and [Obd] parsing, so the trip log, live values, MPG, alerts and notifications all
 * get exercised. It never reports a VIN or an odometer, so it can't touch your Garage.
 */
object SimCar {
    const val ADDRESS = "SIM"
    const val DURATION_S = 240.0

    /** Speed in mph at [t] seconds into the drive. */
    fun mph(t: Double): Double = when {
        t < 15 -> 0.0
        t < 35 -> (t - 15) / 20 * 32
        t < 95 -> 32 + 6 * sin((t - 35) / 9)
        t < 105 -> max(0.0, 32 - (t - 95) / 10 * 32)
        t < 125 -> 0.0
        t < 145 -> (t - 125) / 20 * 52
        t < 205 -> 52 + 4 * sin((t - 145) / 11)
        t < 225 -> max(0.0, 52 - (t - 205) / 20 * 52)
        else -> 0.0
    }

    data class Snap(val mph: Double, val rpm: Double, val maf: Double, val throttle: Double, val load: Double, val coolantC: Double, val fuelPct: Double, val runtimeS: Double)

    fun at(t: Double, startFuel: Double = 61.0): Snap {
        val v = mph(t)
        val accel = mph(t + 1) - v
        val rpm = if (t >= DURATION_S) 0.0 else 720 + v * 32 + max(0.0, accel) * 260
        val maf = if (t >= DURATION_S) 0.0 else 2.6 + v * 0.22 + max(0.0, accel) * 3.5
        val throttle = min(100.0, 14 + max(0.0, accel) * 18 + v * 0.25)
        val load = min(100.0, 18 + max(0.0, accel) * 22 + v * 0.4)
        val coolant = min(92.0, 62 + t * 0.2)
        val fuel = startFuel - t / DURATION_S * 0.4
        return Snap(v, rpm, maf, throttle, load, coolant, fuel, t)
    }
}

/** The adapter side of the simulator: ELM327 command in, ELM327-style reply out (echo off, spaces off, headers off). */
class SimObdPort(private val withCode: Boolean, private val clock: () -> Long = System::currentTimeMillis) : ObdPort {
    private val started = clock()
    private fun t() = (clock() - started) / 1000.0

    /** PIDs this pretend car answers (no A6 odometer, on purpose). */
    private val pids = setOf(0x01, 0x04, 0x05, 0x0B, 0x0C, 0x0D, 0x0F, 0x10, 0x11, 0x1F, 0x21, 0x2F, 0x31, 0x33, 0x42, 0x46, 0x5C)

    private fun bitmap(base: Int): String {
        var bits = 0L
        for (i in 1..32) {
            val pid = base + i
            if (pid in pids || (i == 32 && pids.any { it > pid })) bits = bits or (1L shl (32 - i))
        }
        return "%08X".format(bits)
    }

    private fun hex(vararg b: Int) = b.joinToString("") { "%02X".format(it.coerceIn(0, 255)) }

    override suspend fun send(cmd: String, timeoutMs: Long): String = reply(cmd.trim().uppercase().replace(" ", "")) + "\r\r>"

    fun reply(c: String): String {
        if (c.startsWith("AT")) return when (c) { "ATZ" -> "ELM327 v1.5 (BlackLab simulator)"; "ATRV" -> "14.%dV".format(1 + (t().toInt() % 3)); else -> "OK" }
        if (t() > SimCar.DURATION_S + 30) return "NO DATA"
        val s = SimCar.at(t())
        return when (c) {
            "03" -> if (withCode) "430104 20" else "4300"
            "07", "0A" -> "${(c.toInt(16) + 0x40).toString(16).uppercase()}00"
            "0902", "090A" -> "NO DATA"
            else -> {
                if (!c.startsWith("01") || c.length != 4) return "?"
                val pid = c.substring(2).toInt(16)
                val head = "41%02X".format(pid)
                when (pid) {
                    0x00, 0x20, 0x40, 0x60, 0x80, 0xA0 -> if (pid == 0x00 || pids.any { it > pid }) head + bitmap(pid) else "NO DATA"
                    !in pids -> "NO DATA"
                    0x01 -> head + hex(if (withCode) 0x81 else 0x00, 0x07, 0xE5, 0x00)
                    0x04 -> head + hex((s.load * 2.55).roundToInt())
                    0x05 -> head + hex((s.coolantC + 40).roundToInt())
                    0x0B -> head + hex((30 + s.load * 0.6).roundToInt())
                    0x0C -> (s.rpm * 4).roundToInt().let { head + hex(it shr 8, it and 0xFF) }
                    0x0D -> head + hex((s.mph / 0.621371).roundToInt())
                    0x0F -> head + hex(24 + 40)
                    0x10 -> (s.maf * 100).roundToInt().let { head + hex(it shr 8, it and 0xFF) }
                    0x11 -> head + hex((s.throttle * 2.55).roundToInt())
                    0x1F -> s.runtimeS.roundToInt().let { head + hex(it shr 8, it and 0xFF) }
                    0x21 -> head + hex(0, if (withCode) 12 else 0)
                    0x2F -> head + hex((s.fuelPct * 2.55).roundToInt())
                    0x31 -> head + hex(0x04, 0xD2)
                    0x33 -> head + hex(99)
                    0x42 -> 14100.let { head + hex(it shr 8, it and 0xFF) }
                    0x46 -> head + hex(18 + 40)
                    0x5C -> head + hex((s.coolantC - 6 + 40).roundToInt())
                    else -> "NO DATA"
                }
            }
        }
    }

    override fun close() {}
}

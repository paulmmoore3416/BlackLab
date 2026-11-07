package com.blacklab.app.vehicle

/**
 * ELM327 / OBD-II protocol: the commands BlackLab sends to a Bluetooth OBD adapter and the pure
 * parsing of what comes back (unit-tested). The adapter is set up with echo, spaces and headers off,
 * so a reply looks like `410C1AF8`, and long replies (VIN, many codes) arrive as ISO-TP frames
 * `0:…`, `1:…` after a byte-count line.
 */
object Obd {

    val INIT = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0")

    /** Fast PIDs polled every cycle while driving. */
    val FAST = listOf(0x0D, 0x0C, 0x10, 0x5E)
    /** Slower-changing PIDs, read every ~30 s. */
    val SLOW = listOf(0x05, 0x5C, 0x0F, 0x04, 0x11, 0x0B, 0x2F, 0x42, 0x46, 0x33, 0x1F, 0x5B)
    /** Read once per drive. */
    val ONCE = listOf(0x01, 0x21, 0x31, 0x51, 0xA6)

    /** Splits a raw adapter reply into data lines (no prompt, echo, status chatter or ISO-TP framing). */
    fun lines(raw: String): List<String> = raw.replace(">", "").split('\r', '\n')
        .map { it.trim().replace(" ", "").uppercase() }
        .filter { it.isNotEmpty() && !it.startsWith("SEARCHING") && !it.startsWith("BUSINIT") && it != "OK" && !it.startsWith("AT") }

    fun isNoData(raw: String): Boolean = lines(raw).let { l -> l.isEmpty() || l.all { it.contains("NODATA") || it.contains("ERROR") || it.contains("UNABLE") || it == "?" || it.contains("STOPPED") || it.contains("CANERROR") } }

    private fun hex(s: String): IntArray? {
        val c = s.filter { it.isLetterOrDigit() }
        if (c.length < 2 || c.length % 2 != 0 || !c.all { it in '0'..'9' || it in 'A'..'F' }) return null
        return IntArray(c.length / 2) { c.substring(it * 2, it * 2 + 2).toInt(16) }
    }

    /**
     * Messages in the reply, one byte array per ECU answer. ISO-TP multi-frame replies
     * (`014` / `0:…` / `1:…`) are stitched back together.
     */
    fun messages(raw: String): List<IntArray> {
        val ls = lines(raw).filterNot { it.contains("NODATA") || it.contains("ERROR") }
        val out = ArrayList<IntArray>()
        var multi: ArrayList<Int>? = null
        var expect = 0
        fun flush() {
            multi?.let { m -> out += (if (expect in 1 until m.size) m.subList(0, expect) else m).toIntArray() }
            multi = null; expect = 0
        }
        for (l in ls) {
            val frame = Regex("^([0-9A-F]):(.*)$").find(l)
            when {
                frame != null -> {
                    if (frame.groupValues[1] == "0" && multi?.isNotEmpty() == true) flush()
                    if (multi == null) multi = ArrayList()
                    hex(frame.groupValues[2])?.let { multi!!.addAll(it.toList()) }
                }
                l.length == 3 && l.all { it in '0'..'9' || it in 'A'..'F' } -> { flush(); expect = l.toInt(16) }
                else -> { flush(); hex(l)?.let { out += it } }
            }
        }
        flush()
        return out
    }

    /** Data bytes for a mode-01 style request (`01 0C` → reply `41 0C A B`), from the first ECU that answered. */
    fun response(raw: String, mode: Int, pid: Int): IntArray? =
        messages(raw).firstOrNull { it.size >= 2 && it[0] == mode + 0x40 && it[1] == pid }?.let { it.copyOfRange(2, it.size) }

    /** PIDs supported, from the bitmap replies to 0100 / 0120 / 0140 … (union over all ECUs). */
    fun supported(raw: String, base: Int): Set<Int> {
        val out = HashSet<Int>()
        messages(raw).filter { it.size >= 6 && it[0] == 0x41 && it[1] == base }.forEach { m ->
            for (i in 0 until 32) {
                val byte = m[2 + i / 8]
                if (byte and (0x80 shr (i % 8)) != 0) out += base + i + 1
            }
        }
        return out
    }

    /** Decodes a mode-01 PID into BlackLab's US units (°F, mph, mi). */
    fun decode(pid: Int, d: IntArray): Double? {
        fun a() = d.getOrNull(0)?.toDouble()
        fun ab() = if (d.size >= 2) (d[0] * 256 + d[1]).toDouble() else null
        return when (pid) {
            0x04, 0x11, 0x2F, 0x5B -> a()?.let { it * 100.0 / 255.0 }
            0x05, 0x0F, 0x46, 0x5C -> a()?.let { Telemetry.cToF(it - 40) }
            0x0B, 0x33 -> a()
            0x0C -> ab()?.let { it / 4.0 }
            0x0D -> a()?.let { Telemetry.kmToMi(it) }
            0x10 -> ab()?.let { it / 100.0 }
            0x1F -> ab()?.let { it / 60.0 }
            0x21, 0x31 -> ab()?.let { Telemetry.kmToMi(it) }
            0x42 -> ab()?.let { it / 1000.0 }
            0x51 -> a()
            0x5E -> ab()?.let { it / 20.0 * 0.264172 } // L/h → gal/h
            0xA6 -> if (d.size >= 4) Telemetry.kmToMi(((d[0].toLong() shl 24) or (d[1].toLong() shl 16) or (d[2].toLong() shl 8) or d[3].toLong()) / 10.0) else null
            else -> null
        }
    }

    /** Where each PID lands in the snapshot. */
    val KEYS = mapOf(0x04 to "load", 0x05 to "coolant", 0x0B to "map", 0x0C to "rpm", 0x0D to "speed", 0x0F to "intake_temp", 0x10 to "maf", 0x11 to "throttle",
        0x1F to "runtime", 0x21 to "dist_mil", 0x2F to "fuel_pct", 0x31 to "dist_clear", 0x33 to "baro", 0x42 to "module_v", 0x46 to "ambient", 0x5B to "hev_soc",
        0x5C to "oil_temp", 0x5E to "fuel_rate", 0xA6 to "odometer")

    /** Monitor status (PID 01): check-engine light and how many codes are stored. */
    fun monitor(d: IntArray): Pair<Boolean, Int>? = d.getOrNull(0)?.let { (it and 0x80 != 0) to (it and 0x7F) }

    fun fuelType(code: Int): String? = when (code) {
        1 -> "Gasoline"; 2 -> "Methanol"; 3 -> "Ethanol"; 4 -> "Diesel"; 5 -> "LPG"; 6 -> "CNG"; 8 -> "Electric"
        9, 10, 11, 12, 13 -> "Bi-fuel"; 17 -> "Hybrid (gasoline)"; 18 -> "Hybrid (ethanol)"; 19 -> "Hybrid (diesel)"; 20 -> "Hybrid (electric)"; 21 -> "Hybrid (mixed)"; 22 -> "Hybrid (regenerative)"
        else -> null
    }

    fun dtc(a: Int, b: Int): String = "PCBU"[(a shr 6) and 3] + ((a shr 4) and 3).toString() + "%X".format(a and 0xF) + "%X".format((b shr 4) and 0xF) + "%X".format(b and 0xF)

    /** Trouble codes from a mode 03 (stored), 07 (pending) or 0A (permanent) reply. */
    fun dtcs(raw: String, mode: Int): List<String> {
        val out = LinkedHashSet<String>()
        messages(raw).filter { it.isNotEmpty() && it[0] == mode + 0x40 }.forEach { m ->
            // CAN replies carry a count byte after the mode (odd payload length); older protocols don't
            val start = if ((m.size - 1) % 2 == 1) 2 else 1
            var i = start
            while (i + 1 < m.size) {
                if (m[i] != 0 || m[i + 1] != 0) out += dtc(m[i], m[i + 1])
                i += 2
            }
        }
        return out.toList()
    }

    /** The 17-character VIN from a 0902 reply. */
    fun vin(raw: String): String? {
        val msgs = messages(raw).filter { it.size > 3 && it[0] == 0x49 && it[1] == 0x02 }
        if (msgs.isEmpty()) return null
        val bytes = if (msgs.size == 1) msgs[0].drop(3) else msgs.sortedBy { it[2] }.flatMap { it.drop(3) }
        val s = bytes.filter { it in 0x30..0x5A }.map { it.toChar() }.joinToString("").filter { it.isLetterOrDigit() }
        return s.takeLast(17).takeIf { it.length == 17 && it.none { c -> c == 'I' || c == 'O' || c == 'Q' } }
    }

    /** ECU name (0904 calibration / 090A ECU name), ASCII. */
    fun ascii(raw: String, mode: Int, pid: Int): String? {
        val m = messages(raw).firstOrNull { it.size > 3 && it[0] == mode + 0x40 && it[1] == pid } ?: return null
        return m.drop(3).filter { it in 0x20..0x7E }.map { it.toChar() }.joinToString("").trim().ifBlank { null }
    }

    /** `ATRV` → "12.6V". */
    fun voltage(raw: String): Double? = Regex("(\\d{1,2}(?:\\.\\d)?)\\s*V").find(raw.uppercase())?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it in 5.0..18.0 }
}

/** Plain-language meanings for trouble codes: common ones by code, the rest by system. */
object Dtcs {
    private val known = mapOf(
        "P0011" to "Camshaft timing over-advanced (bank 1)", "P0016" to "Crankshaft/camshaft position correlation",
        "P0030" to "O2 sensor heater circuit (bank 1 sensor 1)", "P0087" to "Fuel rail pressure too low", "P0101" to "Mass air flow sensor range/performance",
        "P0106" to "Manifold pressure sensor range/performance", "P0113" to "Intake air temperature sensor high", "P0117" to "Coolant temp sensor low",
        "P0118" to "Coolant temp sensor high", "P0121" to "Throttle position sensor range/performance", "P0128" to "Engine running cold (thermostat)",
        "P0130" to "O2 sensor circuit (bank 1 sensor 1)", "P0133" to "O2 sensor slow response (bank 1 sensor 1)", "P0135" to "O2 sensor heater (bank 1 sensor 1)",
        "P0141" to "O2 sensor heater (bank 1 sensor 2)", "P0171" to "System too lean (bank 1)", "P0172" to "System too rich (bank 1)",
        "P0234" to "Turbo overboost", "P0299" to "Turbo underboost", "P0300" to "Random/multiple cylinder misfire",
        "P0301" to "Cylinder 1 misfire", "P0302" to "Cylinder 2 misfire", "P0303" to "Cylinder 3 misfire", "P0304" to "Cylinder 4 misfire",
        "P0325" to "Knock sensor circuit", "P0335" to "Crankshaft position sensor", "P0340" to "Camshaft position sensor",
        "P0401" to "EGR flow insufficient", "P0420" to "Catalytic converter efficiency below threshold (bank 1)",
        "P0440" to "EVAP system malfunction", "P0441" to "EVAP purge flow incorrect", "P0442" to "EVAP small leak (check the gas cap)",
        "P0455" to "EVAP large leak (check the gas cap)", "P0456" to "EVAP very small leak (check the gas cap)", "P0457" to "EVAP leak — gas cap loose or missing",
        "P0500" to "Vehicle speed sensor", "P0505" to "Idle control system", "P0506" to "Idle speed lower than expected", "P0507" to "Idle speed higher than expected",
        "P0562" to "System voltage low", "P0563" to "System voltage high", "P0571" to "Brake switch circuit",
        "P0606" to "Engine computer processor fault", "P0700" to "Transmission control system (see transmission codes)",
        "P0715" to "Transmission input speed sensor", "P0741" to "Torque converter clutch stuck off", "P0750" to "Shift solenoid A",
        "P1326" to "Hyundai knock sensor detection system (engine bearing check — see dealer)",
        "P2096" to "Post-catalyst fuel trim too lean", "P2097" to "Post-catalyst fuel trim too rich", "P2187" to "System too lean at idle",
        "P2195" to "O2 sensor signal stuck lean", "P2610" to "ECM internal engine-off timer",
        "U0001" to "High-speed CAN bus", "U0100" to "Lost communication with the engine computer", "U0101" to "Lost communication with the transmission computer",
        "U0121" to "Lost communication with ABS", "U0140" to "Lost communication with body control module", "U0155" to "Lost communication with instrument cluster",
        "C0035" to "Left front wheel speed sensor", "C0040" to "Right front wheel speed sensor", "C0045" to "Left rear wheel speed sensor", "C0050" to "Right rear wheel speed sensor",
        "C1611" to "ABS/ESC communication", "B1602" to "Body CAN communication",
    )

    fun describe(code: String): String {
        known[code]?.let { return it }
        if (code.length != 5) return "Unknown code"
        val maker = code[1] == '1' || (code[0] != 'P' && code[1] == '2') || (code[0] == 'P' && code[1] == '3' && code[2] in '0'..'3')
        val system = when (code[0]) {
            'P' -> when (code[2]) {
                '0', '1', '2' -> "fuel & air metering"; '3' -> "ignition / misfire"; '4' -> "emissions controls"; '5' -> "speed, idle & inputs"
                '6' -> "computer & outputs"; '7', '8', '9' -> "transmission"; 'A', 'B', 'C' -> "hybrid system"; else -> "powertrain"
            }
            'C' -> "chassis (brakes, steering, suspension)"; 'B' -> "body (airbags, climate, lighting)"; 'U' -> "network communication"; else -> "vehicle"
        }
        return (if (maker) "Hyundai-specific " else "") + system + " code"
    }

    /** How urgent it is, for the badge colour. */
    fun severe(code: String): Boolean = code.startsWith("P03") || code in setOf("P0217", "P0218", "P0524", "P0562", "P0700", "P1326", "P0087", "P0234") || code.startsWith("C")
}

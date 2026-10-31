package com.blacklab.app.assistant

import android.content.Context
import com.blacklab.app.AppContainer
import com.blacklab.app.core.Time
import com.blacklab.app.data.ContentStore
import com.blacklab.app.data.DocumentEntity
import com.blacklab.app.data.TaskEntity
import com.blacklab.app.data.TaskOps
import com.blacklab.app.ui.components.Ocr
import com.blacklab.app.ui.editor.EditorController
import org.json.JSONObject
import java.io.File

/**
 * Snap-to-Work-Order: photograph an equipment nameplate / data plate and BlackLab reads it
 * on-device (ML Kit OCR), pulls out manufacturer, model, serial and electrical / refrigerant
 * specs, then files a pre-filled work order document and a task. No network needed.
 */
object Nameplate {

    data class Asset(val fields: LinkedHashMap<String, String>, val raw: String) {
        val label: String get() = listOfNotNull(fields["Manufacturer"], fields["Model"]).joinToString(" ").ifBlank { "Equipment" }
    }

    private val brands = listOf(
        "Carrier", "Trane", "Lennox", "York", "Daikin", "Rheem", "Ruud", "Goodman", "Amana", "Bryant", "Payne", "Heil", "Tempstar", "Lochinvar", "A.O. Smith",
        "AO Smith", "Bradford White", "State", "Rinnai", "Navien", "Baldor", "WEG", "Siemens", "Square D", "Eaton", "Cutler-Hammer", "General Electric", "ABB",
        "Honeywell", "Johnson Controls", "Mitsubishi", "LG", "Fujitsu", "Bell & Gossett", "Grundfos", "Taco", "Armstrong", "Liebert", "Vertiv", "Generac", "Kohler",
        "Cummins", "Caterpillar", "Otis", "Schindler", "ThyssenKrupp", "TK Elevator", "Kone", "Pentair", "Weil-McLain", "Burnham", "Cleaver-Brooks", "AAON",
        "McQuay", "Greenheck", "Loren Cook", "Marley", "Baltimore Aircoil", "Evapco", "Modine", "Reznor", "Marathon", "Leeson", "Toshiba", "Hubbell", "Emerson",
        "Copeland", "Bitzer", "Danfoss", "Hussmann", "Hoshizaki", "Manitowoc", "Scotsman", "Lincoln", "Miller", "Ingersoll Rand", "Quincy", "Atlas Copco", "Sullair",
    )

    private val patterns: List<Pair<String, Regex>> = listOf(
        "Model" to Regex("(?:MODEL\\s*(?:NO|NUMBER|#)\\.?|CAT(?:ALOG)?\\.?\\s*(?:NO|#)\\.?|MODEL|MOD\\.?|M/N)\\s*[:#.]?\\s*([A-Z0-9][A-Z0-9\\-/.]{2,})", RegexOption.IGNORE_CASE),
        "Serial" to Regex("(?:SERIAL\\s*(?:NO|NUMBER|#)\\.?|SERIAL|SER\\.?\\s*(?:NO|#)\\.?|SER\\.?|S/N)\\s*[:#.]?\\s*([A-Z0-9][A-Z0-9\\-]{3,})", RegexOption.IGNORE_CASE),
        "Voltage" to Regex("\\b(\\d{3}(?:\\s*/\\s*\\d{3})?(?:\\s*-\\s*\\d{3})?)\\s*(?:V|VOLTS?|VAC)\\b|\\bVOLT(?:S|AGE)?\\s*[:.]?\\s*(\\d{3}(?:\\s*/\\s*\\d{3})?)", RegexOption.IGNORE_CASE),
        "Phase" to Regex("\\b([13])\\s*(?:PH|PHASE)\\b|\\bPH(?:ASE)?\\s*[:.]?\\s*([13])\\b", RegexOption.IGNORE_CASE),
        "Frequency" to Regex("\\b(50|60)\\s*HZ\\b", RegexOption.IGNORE_CASE),
        "Horsepower" to Regex("\\b(\\d+(?:\\.\\d+)?)\\s*HP\\b", RegexOption.IGNORE_CASE),
        "FLA" to Regex("\\bF\\.?L\\.?A\\.?\\s*[:.]?\\s*(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE),
        "RLA" to Regex("\\bR\\.?L\\.?A\\.?\\s*[:.]?\\s*(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE),
        "MCA" to Regex("\\bMCA\\s*[:.]?\\s*(\\d+(?:\\.\\d+)?)|MIN(?:IMUM)?\\.?\\s*CIRCUIT\\s*AMP\\w*\\s*[:.]?\\s*(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE),
        "Max fuse / MOCP" to Regex("\\b(?:MOCP|MOP|MAX\\.?\\s*(?:FUSE|BREAKER|OVERCURRENT)\\w*)\\s*[:.]?\\s*(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE),
        "Refrigerant" to Regex("\\b(R-?(?:22|410A|32|454B|134A|407C|404A|513A|1234YF|123|290|744))\\b", RegexOption.IGNORE_CASE),
        "Charge" to Regex("\\b(\\d+(?:\\.\\d+)?\\s*(?:LBS?|OZ|KG))\\b", RegexOption.IGNORE_CASE),
        "Capacity" to Regex("\\b(\\d[\\d,]*(?:\\.\\d+)?\\s*(?:BTU/?H(?:R)?|MBH|TONS?|KW|GPM|CFM|GAL(?:LONS?)?))\\b", RegexOption.IGNORE_CASE),
        "RPM" to Regex("\\b(\\d{3,4})\\s*RPM\\b", RegexOption.IGNORE_CASE),
        "Mfg date" to Regex("(?:MFG\\.?\\s*DATE|DATE\\s*OF\\s*MFG|MANUFACTURED|MFD)\\s*[:.]?\\s*([0-9]{1,4}[/\\-.][0-9]{1,2}(?:[/\\-.][0-9]{2,4})?|[A-Z]{3,9}\\s*\\d{4})", RegexOption.IGNORE_CASE),
    )

    fun parse(text: String): Asset {
        val f = LinkedHashMap<String, String>()
        val upper = text.uppercase()
        brands.firstOrNull { upper.contains(it.uppercase()) }?.let { f["Manufacturer"] = it }
        patterns.forEach { (name, re) ->
            re.find(text)?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.let { f[name] = it.trim().trimEnd('.', ',') }
        }
        return Asset(f, text)
    }

    /** Asks Gemini Nano to fill any fields the patterns missed. */
    private suspend fun refine(asset: Asset): Asset {
        if (!Nano.ready || asset.raw.isBlank()) return asset
        val out = Nano.generate(
            """Extract equipment nameplate data from this OCR text. Reply with one JSON object only, using these keys when present:
manufacturer, model, serial, equipment_type, voltage, phase, amps, horsepower, refrigerant, capacity, manufacture_date.

OCR text:
${asset.raw.take(2000)}""", temperature = 0.1f, maxTokens = 220,
        ) ?: return asset
        val json = runCatching { JSONObject(out.substring(out.indexOf('{'), out.lastIndexOf('}') + 1)) }.getOrNull() ?: return asset
        val map = mapOf("manufacturer" to "Manufacturer", "model" to "Model", "serial" to "Serial", "equipment_type" to "Equipment type", "voltage" to "Voltage",
            "phase" to "Phase", "amps" to "FLA", "horsepower" to "Horsepower", "refrigerant" to "Refrigerant", "capacity" to "Capacity", "manufacture_date" to "Mfg date")
        val merged = LinkedHashMap(asset.fields)
        map.forEach { (k, label) -> json.optString(k).trim().takeIf { it.isNotBlank() && it != "null" && label !in merged }?.let { merged[label] = it } }
        return Asset(merged, asset.raw)
    }

    data class Outcome(val asset: Asset, val docId: Long, val taskId: Long)

    suspend fun process(context: Context, app: AppContainer, photo: File, note: String = ""): Outcome {
        val text = Ocr.recognize(context, photo)
        var asset = parse(text)
        if (app.settings.current.asstUseNano) asset = refine(asset)
        val s = app.settings.current
        val e = ContentStore::escape
        val woNumber = "WO-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd-HHmm"))
        val img = EditorController.imageDataUri(photo, 1200)
        val html = buildString {
            append("<h1 class=\"title\">Work Order $woNumber</h1>")
            append("<table><tbody>")
            listOf("WO #" to woNumber, "Date requested" to Time.dateTime(Time.now()), "Requested by" to s.userName, "Asset" to asset.label,
                "Priority" to "☐ Emergency  ☐ Urgent  ☐ Routine  ☐ Scheduled").forEach { (k, v) ->
                append("<tr><th style=\"width:32%\">${e(k)}</th><td>${if (v.isBlank()) "<br>" else e(v)}</td></tr>")
            }
            append("</tbody></table>")
            append("<h2>Equipment nameplate</h2><table><tbody>")
            if (asset.fields.isEmpty()) append("<tr><td>No nameplate fields recognized — see raw text below.</td></tr>")
            asset.fields.forEach { (k, v) -> append("<tr><th style=\"width:32%\">${e(k)}</th><td>${e(v)}</td></tr>") }
            append("</tbody></table>")
            append("<h2>Description of work</h2><p>${if (note.isBlank()) "<br>" else e(note)}</p>")
            append("<h2>Assignment</h2><table><tbody><tr><th style=\"width:32%\">Assigned to</th><td><br></td></tr><tr><th>Target completion</th><td><br></td></tr></tbody></table>")
            append("<h2>Completion</h2><ul class=\"checklist\"><li>Work completed and area cleaned</li><li>Requester notified</li><li>Asset history updated</li></ul>")
            img?.let { append("<h2>Nameplate photo</h2><p><img src=\"$it\"></p>") }
            append("<h2>Recognized text</h2><p style=\"font-family:monospace;font-size:10pt\">${e(text).replace("\n", "<br>")}</p>")
        }
        val title = "$woNumber · ${asset.label}".take(80)
        val rel = app.content.writeDocument(s.activeProfile, title, "Work Orders", "", html)
        val plain = ContentStore.plainText(html)
        val docId = app.db.documents().insert(DocumentEntity(profile = s.activeProfile.name, title = title, folder = "Work Orders", filePath = rel,
            preview = ContentStore.preview(plain), wordCount = ContentStore.wordCount(plain), tags = "work order"))
        val listId = app.db.tasks().lists(s.activeProfile.name).firstOrNull { it.name.equals(s.asstWorkOrderList, true) }?.id
        val taskId = TaskOps.save(app, TaskEntity(
            profile = s.activeProfile.name, listId = listId, title = "$woNumber: ${note.ifBlank { "Service " + asset.label }}".take(140),
            notes = asset.fields.entries.joinToString("\n") { "${it.key}: ${it.value}" }, tags = "workorder", attachmentPath = app.storage.relative(photo),
            dueDay = Time.today().toEpochDay(), priority = 2,
        ))
        return Outcome(asset, docId, taskId)
    }
}

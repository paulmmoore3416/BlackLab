package com.blacklab.app.dictation

import com.blacklab.app.core.GeoTag
import com.blacklab.app.core.Time
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One recognised utterance. [ms] is the position in the audio; [para] starts a new paragraph. */
data class Segment(val ms: Long, val text: String, val para: Boolean = false)

data class DictationMeta(
    val title: String,
    val startedAt: Long,
    val endedAt: Long = startedAt,
    val durationMs: Long = 0,
    val geo: GeoTag? = null,
    val account: String = "",
    val segments: List<Segment> = emptyList(),
    val markers: List<Pair<Long, String>> = emptyList(),
    /** Set when the user edits the transcript by hand (replaces the timed segments for display/export). */
    val editedText: String? = null,
    val audio: String = "",
) {
    val wordCount: Int get() = Regex("[\\p{L}\\p{N}'’]+").findAll(editedText ?: segments.joinToString(" ") { it.text }).count()
}

/** Voice commands recognised during dictation. Matching ignores case and punctuation. */
object VoiceCommands {
    enum class Cmd { PAUSE, RESUME, STOP, NEW_PARAGRAPH, NEW_LINE, MARK }

    private val phrases: List<Pair<Cmd, List<String>>> = listOf(
        Cmd.PAUSE to listOf("pause recording", "pause the recording", "pause dictation", "pause transcription"),
        Cmd.RESUME to listOf("resume recording", "start recording", "continue recording", "restart recording", "resume dictation", "start dictation", "continue dictation", "unpause recording"),
        Cmd.STOP to listOf("stop recording", "stop the recording", "end recording", "end the recording", "finish recording", "stop dictation", "end dictation"),
        Cmd.MARK to listOf("mark that", "add marker", "add a marker", "bookmark that", "flag that"),
    )

    fun normalize(s: String): String = s.lowercase(Locale.US).replace(Regex("[^\\p{L}\\p{N}' ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** A control command at the end of [text] → the command and the text spoken before it. */
    fun trailing(text: String): Pair<Cmd, String>? {
        val norm = normalize(text)
        for ((cmd, list) in phrases) for (p in list) {
            if (norm == p || norm.endsWith(" $p")) {
                // cut the phrase off the original (keep the user's punctuation in what precedes it)
                val words = p.split(' ').size
                val tokens = text.trim().split(Regex("\\s+"))
                val before = tokens.dropLast(words).joinToString(" ").trimEnd(',', ';', ':', '-', ' ')
                return cmd to before
            }
        }
        return null
    }

    /** Applies inline formatting commands ("new paragraph", "new line") inside a final utterance. */
    fun inline(text: String): Pair<String, Boolean> {
        var t = text
        var para = false
        val np = Regex("(?i)[,.]?\\s*\\bnew paragraph\\b[,.]?\\s*")
        if (np.containsMatchIn(t)) {
            if (np.find(t)?.range?.first == 0) para = true
            t = t.replace(np, "\n\n")
        }
        t = t.replace(Regex("(?i)[,.]?\\s*\\b(new line|next line)\\b[,.]?\\s*"), "\n")
        return t.trim('\n', ' ').let { if (it.isEmpty()) "" else it } to para
    }

    val help = listOf("“pause recording”", "“resume recording”", "“stop recording”", "“new paragraph”", "“new line”", "“mark that”")
}

object DictationStore {
    private val folderFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.US)

    /** A free, dated folder name such as Dictation_2026-10-28_14-32-05 (not created). */
    fun folderName(start: Long): String = "Dictation_" + Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).format(folderFmt)

    fun uniqueFolder(root: File, start: Long): File {
        val name = folderName(start)
        var f = File(root, name); var i = 2
        while (f.exists()) { f = File(root, "$name ($i)"); i++ }
        return f
    }

    fun newFolder(root: File, start: Long): File = uniqueFolder(root, start).also { it.mkdirs() }

    fun metaFile(folder: File) = File(folder, "meta.json")
    fun transcriptFile(folder: File) = File(folder, folder.name + ".txt")
    fun audioFile(folder: File, meta: DictationMeta?) = File(folder, meta?.audio?.ifBlank { null } ?: (folder.name + ".m4a"))

    fun list(root: File): List<Pair<File, DictationMeta>> =
        root.listFiles()?.filter { it.isDirectory }?.mapNotNull { d -> read(d)?.let { d to it } }?.sortedByDescending { it.second.startedAt } ?: emptyList()

    fun read(folder: File): DictationMeta? = runCatching {
        val o = JSONObject(metaFile(folder).readText())
        DictationMeta(
            title = o.optString("title"),
            startedAt = o.optLong("startedAt"),
            endedAt = o.optLong("endedAt"),
            durationMs = o.optLong("durationMs"),
            geo = o.optJSONObject("location")?.let { g ->
                GeoTag(g.getDouble("lat"), g.getDouble("lon"), if (g.has("altitude")) g.getDouble("altitude") else null,
                    if (g.has("accuracyM")) g.getDouble("accuracyM").toFloat() else null, g.optString("address").ifBlank { null }, g.optLong("time"))
            },
            account = o.optString("account"),
            segments = o.optJSONArray("segments")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { Segment(it.getLong("ms"), it.getString("text"), it.optBoolean("para")) } } } ?: emptyList(),
            markers = o.optJSONArray("markers")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { it.getLong("ms") to it.getString("label") } } } ?: emptyList(),
            editedText = if (o.has("editedText")) o.getString("editedText") else null,
            audio = o.optString("audio"),
        )
    }.getOrNull()

    fun write(folder: File, m: DictationMeta, timestamps: Boolean = true) {
        val o = JSONObject().apply {
            put("title", m.title); put("startedAt", m.startedAt); put("endedAt", m.endedAt); put("durationMs", m.durationMs)
            put("account", m.account); put("audio", m.audio); put("app", "BlackLab"); put("wordCount", m.wordCount)
            m.geo?.let { g ->
                put("location", JSONObject().apply {
                    put("lat", g.lat); put("lon", g.lon); g.altitude?.let { put("altitude", it) }; g.accuracyM?.let { put("accuracyM", it.toDouble()) }
                    g.address?.let { put("address", it) }; put("time", g.time)
                })
            }
            put("segments", JSONArray().apply { m.segments.forEach { s -> put(JSONObject().put("ms", s.ms).put("text", s.text).put("para", s.para)) } })
            put("markers", JSONArray().apply { m.markers.forEach { (ms, l) -> put(JSONObject().put("ms", ms).put("label", l)) } })
            m.editedText?.let { put("editedText", it) }
        }
        metaFile(folder).writeText(o.toString(2))
        transcriptFile(folder).writeText(document(m, timestamps))
    }

    /** Paragraphs of the transcript, each with the audio position it starts at. */
    fun paragraphs(m: DictationMeta): List<Pair<Long, String>> {
        m.editedText?.let { t -> return t.split(Regex("\n{2,}")).filter { it.isNotBlank() }.map { -1L to it.trim() } }
        val out = mutableListOf<Pair<Long, StringBuilder>>()
        m.segments.forEach { s ->
            if (out.isEmpty() || s.para) out += s.ms to StringBuilder(s.text)
            else out.last().second.append(if (s.text.startsWith("\n")) "" else " ").append(s.text)
        }
        return out.map { it.first to it.second.toString().trim() }.filter { it.second.isNotBlank() }
    }

    fun plainText(m: DictationMeta, timestamps: Boolean): String =
        paragraphs(m).joinToString("\n\n") { (ms, t) -> if (timestamps && ms >= 0) "[${Time.duration(ms)}] $t" else t }

    fun document(m: DictationMeta, timestamps: Boolean): String = buildString {
        val z = ZoneId.systemDefault()
        val day = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.US)
        val tm = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
        val s = Instant.ofEpochMilli(m.startedAt).atZone(z); val e = Instant.ofEpochMilli(m.endedAt).atZone(z)
        appendLine(m.title)
        appendLine("Recorded: ${s.format(day)} · ${s.format(tm)} – ${e.format(tm)} (${Time.duration(m.durationMs)})")
        m.geo?.let { g ->
            appendLine("Location: ${g.address ?: "—"}")
            appendLine("GPS: ${g.coords}" + (g.accuracyM?.let { " (±${it.toInt()} m)" } ?: "") + " · https://maps.google.com/?q=${g.lat},${g.lon}")
        }
        if (m.account.isNotBlank()) appendLine("Account: ${m.account}")
        appendLine("Words: ${m.wordCount}")
        if (m.markers.isNotEmpty()) appendLine("Markers: " + m.markers.joinToString(" · ") { "${Time.duration(it.first)} ${it.second}" })
        appendLine("────────────────────────────────────────")
        appendLine()
        append(plainText(m, timestamps))
        appendLine()
    }
}

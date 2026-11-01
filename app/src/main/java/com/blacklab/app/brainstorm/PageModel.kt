package com.blacklab.app.brainstorm

import com.blacklab.app.data.ContentStore
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Safelist
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ====================================================================== tags

/** OneNote-style paragraph tags. The ids match `data-tag` values written by assets/notebook/page.html. */
data class TagDef(val id: String, val label: String, val symbol: String, val color: Long)

object Tags {
    val all = listOf(
        TagDef("todo", "To Do", "☐", 0xFF4A6EE0),
        TagDef("important", "Important", "★", 0xFFF2B400),
        TagDef("question", "Question", "❓", 0xFF8E44AD),
        TagDef("idea", "Idea", "💡", 0xFFFFB300),
        TagDef("critical", "Critical", "❗", 0xFFD93025),
        TagDef("remember", "Remember for later", "📌", 0xFFE6C700),
        TagDef("definition", "Definition", "📗", 0xFF43A047),
        TagDef("follow", "Follow up", "🚩", 0xFFE53935),
        TagDef("discuss", "Discuss with team", "💬", 0xFF1E88E5),
        TagDef("schedule", "Schedule meeting", "📅", 0xFF00897B),
        TagDef("contact", "Contact", "👤", 0xFF6D4C41),
        TagDef("address", "Address", "🏠", 0xFF5E35B1),
        TagDef("phone", "Phone number", "📞", 0xFF3949AB),
        TagDef("website", "Website to visit", "🌐", 0xFF039BE5),
        TagDef("password", "Password", "🔑", 0xFF757575),
        TagDef("book", "Book to read", "📖", 0xFF8D6E63),
    )
    private val byId = all.associateBy { it.id }
    fun of(id: String): TagDef = byId[id] ?: TagDef(id, id.replaceFirstChar { it.uppercase() }, "•", 0xFF888888)

    data class Line(val tag: String, val done: Boolean, val text: String)

    fun parseIndex(index: String): List<Line> = index.lines().mapNotNull { l ->
        val p = l.split('|', limit = 3)
        if (p.size < 3 || p[0].isBlank()) null else Line(p[0], p[1] == "1", p[2])
    }
}

// ====================================================================== text extraction

data class PageExtract(val title: String, val text: String, val preview: String, val words: Int, val tagIndex: String)

object PageText {
    fun model(json: String): JSONObject = runCatching { JSONObject(json) }.getOrDefault(JSONObject())

    fun items(m: JSONObject): List<JSONObject> {
        val a = m.optJSONArray("items") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.sortedWith(compareBy({ it.optDouble("y", 0.0) }, { it.optDouble("x", 0.0) }))
    }

    fun extract(json: String): PageExtract {
        val m = model(json)
        val title = m.optString("title").trim()
        val parts = ArrayList<String>()
        val tags = ArrayList<String>()
        items(m).forEach { it ->
            when (it.optString("t")) {
                "text" -> {
                    val html = it.optString("html")
                    parts += ContentStore.plainText(html)
                    val doc: Document = Jsoup.parseBodyFragment(html)
                    doc.select("[data-tag]").forEach { el ->
                        val text = el.text().replace('|', '/').replace('\n', ' ').trim()
                        if (text.isNotEmpty()) tags += "${el.attr("data-tag")}|${if (el.attr("data-done") == "1") "1" else "0"}|$text"
                    }
                }
                "audio" -> { parts += it.optString("transcript"); parts += it.optString("name") }
                "image" -> parts += it.optString("ocr")
                "file" -> parts += it.optString("name")
            }
        }
        val text = parts.filter { it.isNotBlank() }.joinToString("\n\n")
        return PageExtract(title, text, ContentStore.preview(text), ContentStore.wordCount(text), tags.joinToString("\n"))
    }

    /** All asset names a page references (images, recordings, files). */
    fun assetNames(json: String): Set<String> = items(model(json)).mapNotNull { it.optString("src").takeIf { s -> s.startsWith("asset:") }?.removePrefix("asset:") ?: it.optString("path").takeIf { p -> p.startsWith("asset:") }?.removePrefix("asset:") }.toSet()
}

// ====================================================================== crypto for password-protected sections

/**
 * Section passwords: PBKDF2-HMAC-SHA256 (210k rounds) derives an AES-256 key; files are AES-GCM with a
 * random 96-bit IV and a "BLE1" magic header. The password itself is never stored — only a salt and a
 * known value encrypted with the key, used to check a typed password.
 */
object PageCrypto {
    private val MAGIC = byteArrayOf('B'.code.toByte(), 'L'.code.toByte(), 'E'.code.toByte(), '1'.code.toByte())
    private const val ROUNDS = 210_000
    private const val CHECK = "blacklab-section-v1"
    private val rnd = SecureRandom()

    fun newSalt(): String = ByteArray(16).also(rnd::nextBytes).let { Base64.getEncoder().encodeToString(it) }

    fun deriveKey(password: CharArray, salt: String, rounds: Int = ROUNDS): SecretKey {
        val spec = PBEKeySpec(password, Base64.getDecoder().decode(salt), rounds, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    fun isEncrypted(b: ByteArray) = b.size > 4 && b[0] == MAGIC[0] && b[1] == MAGIC[1] && b[2] == MAGIC[2] && b[3] == MAGIC[3]

    fun encrypt(key: SecretKey, plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also(rnd::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return MAGIC + iv + c.doFinal(plain)
    }

    fun decrypt(key: SecretKey, data: ByteArray): ByteArray {
        require(isEncrypted(data)) { "Not encrypted" }
        val iv = data.copyOfRange(4, 16)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return c.doFinal(data, 16, data.size - 16)
    }

    fun check(key: SecretKey): String = Base64.getEncoder().encodeToString(encrypt(key, CHECK.toByteArray()))

    fun verify(key: SecretKey, check: String): Boolean =
        runCatching { String(decrypt(key, Base64.getDecoder().decode(check))) == CHECK }.getOrDefault(false)
}

// ====================================================================== templates

data class PageTemplate(val id: String, val label: String, val hint: String)

object PageTemplates {
    val all = listOf(
        PageTemplate("blank", "Blank page", "An empty canvas"),
        PageTemplate("lined", "Lined paper", "College-ruled lines"),
        PageTemplate("grid", "Grid paper", "Squares for sketches & diagrams"),
        PageTemplate("dots", "Dot grid", "Bullet-journal dots"),
        PageTemplate("todo", "To-do list", "Tickable To Do tags"),
        PageTemplate("meeting", "Meeting notes", "Attendees, agenda, decisions, actions"),
        PageTemplate("lecture", "Lecture notes (Cornell)", "Cues, notes and summary"),
        PageTemplate("project", "Project overview", "Goal, scope, milestones, risks"),
        PageTemplate("mindmap", "Mind map", "A central idea with branches"),
        PageTemplate("swot", "SWOT analysis", "Strengths, weaknesses, opportunities, threats"),
        PageTemplate("decision", "Decision: pros & cons", "Weigh options side by side"),
        PageTemplate("daily", "Daily plan", "Top three, schedule, notes"),
        PageTemplate("weekly", "Weekly planner", "Monday to Sunday"),
        PageTemplate("retro", "Retrospective", "Went well, to improve, actions"),
    )

    private var seq = 0
    private fun id() = "t" + (System.nanoTime() % 1_000_000_000).toString(36) + (seq++).toString(36)

    fun text(x: Int, y: Int, w: Int, html: String): JSONObject = JSONObject().put("id", id()).put("t", "text").put("x", x).put("y", y).put("w", w).put("html", html)
    fun line(x1: Double, y1: Double, x2: Double, y2: Double, color: String = "#7c5cff", w: Double = 2.0, shape: String = "line"): JSONObject =
        JSONObject().put("id", id()).put("c", color).put("w", w).put("shape", shape).put("pts", JSONArray(listOf(x1, y1, x2, y2)))

    private fun e(s: String) = ContentStore.escape(s)
    private fun todos(vararg t: String) = t.joinToString("") { "<p data-tag=\"todo\" data-done=\"0\">${e(it)}</p>" }
    private fun bullets(vararg t: String) = "<ul>" + t.joinToString("") { "<li>${e(it)}</li>" } + "</ul>"
    private fun card(bg: String, html: String) = "<div style=\"background:$bg;border-radius:12px;padding:8px 10px\">$html</div>"

    fun build(template: String, title: String, now: Long = System.currentTimeMillis()): String {
        val items = JSONArray()
        val ink = JSONArray()
        var bg = JSONObject().put("color", "#ffffff").put("rule", "none").put("dark", false)
        val date = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US).format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()))
        when (template) {
            "lined" -> { bg.put("rule", "college"); items.put(text(20, 90, 360, "<p><br></p>")) }
            "grid" -> { bg.put("rule", "grid"); items.put(text(20, 90, 360, "<p><br></p>")) }
            "dots" -> { bg.put("rule", "dots"); items.put(text(20, 90, 360, "<p><br></p>")) }
            "todo" -> items.put(text(20, 90, 360, "<h2>To do</h2>" + todos("", "", "", "", "")))
            "meeting" -> {
                items.put(text(20, 90, 360, "<p><b>Date:</b> ${e(date)}</p><p><b>Attendees:</b> </p><p><b>Location:</b> </p>"))
                items.put(text(20, 190, 360, "<h2>Agenda</h2><ol><li><br></li><li><br></li><li><br></li></ol>"))
                items.put(text(20, 340, 360, "<h2>Notes</h2><p><br></p>"))
                items.put(text(20, 460, 360, "<h2>Decisions</h2><p data-tag=\"important\"><br></p>"))
                items.put(text(20, 560, 360, "<h2>Action items</h2>" + todos("Owner — task — due date", "", "")))
            }
            "lecture" -> {
                bg.put("rule", "college")
                items.put(text(12, 90, 110, "<p><b>Cues &amp; questions</b></p><p data-tag=\"question\"><br></p>"))
                items.put(text(132, 90, 260, "<p><b>Notes</b> · ${e(date)}</p><ul><li><br></li></ul>"))
                items.put(text(12, 620, 380, "<p><b>Summary</b></p><p><br></p>"))
                ink.put(line(126.0, 84.0, 126.0, 600.0, "#d93025", 1.5))
                ink.put(line(8.0, 606.0, 396.0, 606.0, "#d93025", 1.5))
            }
            "project" -> {
                items.put(text(20, 90, 360, "<h2>Goal</h2><p><br></p><h2>Scope</h2>" + bullets("In: ", "Out: ")))
                items.put(text(20, 300, 360, "<h2>Milestones</h2><table><tbody><tr><th>Milestone</th><th>Owner</th><th>Due</th></tr><tr><td><br></td><td><br></td><td><br></td></tr><tr><td><br></td><td><br></td><td><br></td></tr></tbody></table>"))
                items.put(text(20, 470, 360, "<h2>Risks</h2><p data-tag=\"critical\"><br></p><h2>Next steps</h2>" + todos("", "")))
            }
            "mindmap" -> return mindMap(title.ifBlank { "Central idea" }, listOf("Idea", "Idea", "Idea", "Idea", "Idea", "Idea"), now, title)
            "swot" -> {
                items.put(text(12, 90, 186, card("#e8f5e9", "<h3>Strengths</h3><ul><li><br></li></ul>")))
                items.put(text(206, 90, 186, card("#ffebee", "<h3>Weaknesses</h3><ul><li><br></li></ul>")))
                items.put(text(12, 290, 186, card("#e3f2fd", "<h3>Opportunities</h3><ul><li><br></li></ul>")))
                items.put(text(206, 290, 186, card("#fff8e1", "<h3>Threats</h3><ul><li><br></li></ul>")))
            }
            "decision" -> items.put(text(20, 90, 360, "<p><b>Question:</b> </p><table><tbody><tr><th>Pros 👍</th><th>Cons 👎</th></tr><tr><td><br></td><td><br></td></tr><tr><td><br></td><td><br></td></tr><tr><td><br></td><td><br></td></tr></tbody></table><p><b>Decision:</b> </p>"))
            "daily" -> {
                items.put(text(20, 90, 360, "<h2>Top three</h2><p data-tag=\"important\"><br></p><p data-tag=\"important\"><br></p><p data-tag=\"important\"><br></p>"))
                items.put(text(20, 250, 360, "<h2>Schedule</h2><table><tbody>" + listOf("8:00", "10:00", "12:00", "2:00", "4:00").joinToString("") { "<tr><td>$it</td><td><br></td></tr>" } + "</tbody></table>"))
                items.put(text(20, 470, 360, "<h2>Notes</h2><p><br></p><h2>Grateful for</h2><p><br></p>"))
            }
            "weekly" -> items.put(text(20, 90, 360, "<table><tbody>" + listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday").joinToString("") { "<tr><th>$it</th><td><br></td></tr>" } + "</tbody></table><h3>This week's focus</h3><p data-tag=\"important\"><br></p>"))
            "retro" -> {
                items.put(text(20, 90, 360, card("#e8f5e9", "<h3>Went well</h3><ul><li><br></li></ul>")))
                items.put(text(20, 230, 360, card("#fff3e0", "<h3>To improve</h3><ul><li><br></li></ul>")))
                items.put(text(20, 370, 360, card("#e3f2fd", "<h3>Actions</h3>" + todos(""))))
            }
            else -> items.put(text(20, 90, 360, "<p><br></p>"))
        }
        return JSONObject().put("v", 1).put("title", title).put("created", now).put("bg", bg).put("items", items).put("ink", ink).toString()
    }

    /** A radial mind map: the topic in the middle, [ideas] around it, joined by lines. */
    fun mindMap(topic: String, ideas: List<String>, now: Long = System.currentTimeMillis(), title: String = topic): String {
        val items = JSONArray()
        val ink = JSONArray()
        val cx = 200.0; val cy = 470.0
        val n = ideas.size.coerceIn(1, 12)
        val rx = 150.0; val ry = if (n > 6) 290.0 else 240.0
        // with more than six branches the side boxes sit level with the centre, so everything narrows
        val wide = n <= 6
        val cw = if (wide) 180 else 130; val bw = if (wide) 140 else 118
        items.put(text((cx - cw / 2).toInt(), (cy - 30).toInt(), cw, "<div style=\"background:#7c5cff;color:#fff;border-radius:16px;padding:10px 12px;text-align:center\"><b>${e(topic)}</b></div>"))
        val palette = listOf("#ede7ff", "#e3f2fd", "#e8f5e9", "#fff8e1", "#fce4ec", "#e0f7fa")
        ideas.take(n).forEachIndexed { i, idea ->
            val a = -PI / 2 + i * 2 * PI / n
            val bx = cx + rx * cos(a); val by = cy + ry * sin(a)
            ink.put(line(cx, cy, bx, by, "#b39ddb", 2.0))
            items.put(text((bx - bw / 2).toInt().coerceAtLeast(4), (by - 24).toInt(), bw, card(palette[i % palette.size], "<p style=\"text-align:center\">${e(idea)}</p>")))
        }
        val bg = JSONObject().put("color", "#ffffff").put("rule", "dots").put("dark", false)
        return JSONObject().put("v", 1).put("title", title).put("created", now).put("bg", bg).put("items", items).put("ink", ink).toString()
    }

    /** Offline idea starters when Gemini Nano isn't available: 5W1H + SCAMPER prompts about the topic. */
    fun prompts(topic: String): List<String> = listOf(
        "Who is it for?", "What problem does it solve?", "Why now?", "How could it work?",
        "Substitute: what could replace part of it?", "Combine: what could it merge with?",
        "Adapt: what works elsewhere?", "Eliminate: what can we remove?",
    ).let { if (topic.isBlank()) it else it }
}

// ====================================================================== rendering (exports, print, Live Share)

object PageHtml {
    private val safelist: Safelist = Safelist.relaxed()
        .addTags("span", "div", "font", "mark", "s", "strike", "hr")
        .addAttributes(":all", "style", "class", "data-tag", "data-done")
        .addAttributes("font", "color", "size", "face")
        .addProtocols("a", "href", "bl-page", "http", "https", "mailto", "tel")

    fun sanitize(html: String): String = Jsoup.clean(html, "", safelist, Document.OutputSettings().prettyPrint(false))

    const val TAG_CSS = """
      .bx p { margin: 0 0 4px 0; } .bx h1 { font-size: 18pt; margin: 6px 0 4px; color: #2f3ea8; } .bx h2 { font-size: 14.5pt; margin: 6px 0 4px; color: #3b4fc4; }
      .bx h3 { font-size: 12.5pt; margin: 4px 0 2px; color: #4a5ad0; } .bx table { border-collapse: collapse; } .bx td, .bx th { border: 1px solid #9aa0a6; padding: 4px 6px; vertical-align: top; }
      .bx th { background: rgba(124,92,255,.12); } .bx ul, .bx ol { padding-left: 24px; margin: 2px 0 4px; }
      .bx ul.checklist { list-style: none; padding-left: 4px; } .bx ul.checklist li:before { content: '☐ '; } .bx ul.checklist li.checked:before { content: '☑ '; } .bx ul.checklist li.checked { color: #888; text-decoration: line-through; }
      .bx [data-tag]:before { margin-right: 6px; } .bx [data-tag="todo"]:before { content: '☐'; color: #4a6ee0; } .bx [data-tag="todo"][data-done="1"]:before { content: '☑'; }
      .bx [data-tag="important"]:before { content: '★'; color: #f2b400; } .bx [data-tag="question"]:before { content: '❓'; } .bx [data-tag="idea"]:before { content: '💡'; }
      .bx [data-tag="critical"]:before { content: '❗'; } .bx [data-tag="remember"] { background: rgba(255,235,59,.5); } .bx [data-tag="remember"]:before { content: '📌'; }
      .bx [data-tag="definition"] { background: rgba(102,187,106,.3); } .bx [data-tag="definition"]:before { content: '📗'; } .bx [data-tag="contact"]:before { content: '👤'; }
      .bx [data-tag="address"]:before { content: '🏠'; } .bx [data-tag="phone"]:before { content: '📞'; } .bx [data-tag="website"]:before { content: '🌐'; } .bx [data-tag="password"]:before { content: '🔑'; }
      .bx [data-tag="discuss"]:before { content: '💬'; } .bx [data-tag="follow"]:before { content: '🚩'; } .bx [data-tag="book"]:before { content: '📖'; } .bx [data-tag="schedule"]:before { content: '📅'; }
    """

    private fun num(o: JSONObject, k: String) = o.optDouble(k, 0.0).let { if (it.isNaN()) 0.0 else it }

    // ---------------------------------------------------------------- ink → SVG
    fun inkPath(s: JSONObject): String {
        val p = s.optJSONArray("pts") ?: return ""
        val v = DoubleArray(p.length()) { p.optDouble(it, 0.0) }
        val f = { d: Double -> String.format(Locale.US, "%.1f", d) }
        when (s.optString("shape")) {
            "line" -> return "M${f(v[0])} ${f(v[1])}L${f(v[2])} ${f(v[3])}"
            "arrow" -> {
                val a = atan2(v[3] - v[1], v[2] - v[0]); val l = max(10.0, s.optDouble("w", 2.0) * 4)
                return "M${f(v[0])} ${f(v[1])}L${f(v[2])} ${f(v[3])}M${f(v[2] - l * cos(a - 0.45))} ${f(v[3] - l * sin(a - 0.45))}L${f(v[2])} ${f(v[3])}L${f(v[2] - l * cos(a + 0.45))} ${f(v[3] - l * sin(a + 0.45))}"
            }
            "rect" -> return "M${f(v[0])} ${f(v[1])}H${f(v[2])}V${f(v[3])}H${f(v[0])}Z"
            "ellipse" -> {
                val cx = (v[0] + v[2]) / 2; val cy = (v[1] + v[3]) / 2; val rx = max(abs(v[2] - v[0]) / 2, 0.5); val ry = max(abs(v[3] - v[1]) / 2, 0.5)
                return "M${f(cx - rx)} ${f(cy)}A${f(rx)} ${f(ry)} 0 1 0 ${f(cx + rx)} ${f(cy)}A${f(rx)} ${f(ry)} 0 1 0 ${f(cx - rx)} ${f(cy)}Z"
            }
        }
        if (v.size < 6) return if (v.size >= 3) "M${f(v[0])} ${f(v[1])}l0.01 0" else ""
        val sb = StringBuilder("M${f(v[0])} ${f(v[1])}")
        var i = 3
        while (i < v.size - 3) {
            sb.append("Q${f(v[i])} ${f(v[i + 1])} ${f((v[i] + v[i + 3]) / 2)} ${f((v[i + 1] + v[i + 4]) / 2)}")
            i += 3
        }
        sb.append("L${f(v[v.size - 3])} ${f(v[v.size - 2])}")
        return sb.toString()
    }

    fun strokeWidth(s: JSONObject): Double {
        var w = s.optDouble("w", 2.0)
        val p = s.optJSONArray("pts")
        if (s.optString("shape").isEmpty() && !s.optBoolean("hl") && p != null && p.length() >= 3) {
            var sum = 0.0; var n = 0; var i = 2
            while (i < p.length()) { sum += p.optDouble(i, 0.5); n++; i += 3 }
            w *= ((if (n > 0) sum / n else 0.5) * 2).coerceIn(0.6, 1.6)
        }
        return w
    }

    data class Bounds(val x1: Double, val y1: Double, val x2: Double, val y2: Double)

    fun inkBounds(m: JSONObject): Bounds? {
        val ink = m.optJSONArray("ink") ?: return null
        var x1 = Double.MAX_VALUE; var y1 = Double.MAX_VALUE; var x2 = -Double.MAX_VALUE; var y2 = -Double.MAX_VALUE
        for (k in 0 until ink.length()) {
            val s = ink.optJSONObject(k) ?: continue
            val p = s.optJSONArray("pts") ?: continue
            val step = if (s.optString("shape").isEmpty()) 3 else 2
            var i = 0
            while (i + 1 < p.length()) { x1 = min(x1, p.optDouble(i)); y1 = min(y1, p.optDouble(i + 1)); x2 = max(x2, p.optDouble(i)); y2 = max(y2, p.optDouble(i + 1)); i += step }
        }
        return if (x1 == Double.MAX_VALUE) null else Bounds(x1 - 6, y1 - 6, x2 + 6, y2 + 6)
    }

    fun inkSvg(m: JSONObject, width: Double, height: Double, offsetX: Double = 0.0, offsetY: Double = 0.0): String {
        val ink = m.optJSONArray("ink") ?: return ""
        if (ink.length() == 0) return ""
        val sb = StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${width.toInt()}\" height=\"${height.toInt()}\" viewBox=\"$offsetX $offsetY ${width.toInt()} ${height.toInt()}\" class=\"ink\">")
        for (k in 0 until ink.length()) {
            val s = ink.optJSONObject(k) ?: continue
            val color = s.optString("c", "#000000").takeIf { it.matches(Regex("#[0-9a-fA-F]{3,8}")) } ?: "#000000"
            val hl = s.optBoolean("hl")
            sb.append("<path d=\"${inkPath(s)}\" fill=\"none\" stroke=\"$color\" stroke-width=\"${String.format(Locale.US, "%.2f", strokeWidth(s))}\" stroke-linecap=\"${if (hl) "butt" else "round"}\" stroke-linejoin=\"round\"${if (hl) " stroke-opacity=\"0.38\"" else ""}/>")
        }
        return sb.append("</svg>").toString()
    }

    // ---------------------------------------------------------------- layout estimate (no DOM on this side)
    fun estimateHeight(it: JSONObject): Double = when (it.optString("t")) {
        "image" -> num(it, "h").takeIf { h -> h > 0 } ?: 200.0
        "text" -> {
            val w = num(it, "w").takeIf { w -> w > 40 } ?: 360.0
            val doc = Jsoup.parseBodyFragment(it.optString("html"))
            val blocks = doc.select("p,li,h1,h2,h3,tr,div,blockquote").size.coerceAtLeast(1)
            val chars = doc.text().length
            24.0 + blocks * 20.0 + chars * 7.2 / w * 20.0
        }
        else -> 70.0 + (if (it.optString("transcript").isNotBlank()) 90.0 else 0.0)
    }

    private fun stamp(created: Long): String = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy   h:mm a", Locale.US).format(Instant.ofEpochMilli(created).atZone(ZoneId.systemDefault()))

    /** The page with its real layout (absolute boxes + ink), for printing, Live Share and read-only views. */
    fun static(json: String, asset: (String) -> String, standalone: Boolean = true): String {
        val m = PageText.model(json)
        val items = PageText.items(m)
        var w = 400.0; var h = 300.0
        items.forEach { w = max(w, num(it, "x") + (num(it, "w").takeIf { x -> x > 0 } ?: 220.0) + 24); h = max(h, num(it, "y") + estimateHeight(it) + 40) }
        inkBounds(m)?.let { w = max(w, it.x2 + 24); h = max(h, it.y2 + 40) }
        val bg = m.optJSONObject("bg") ?: JSONObject()
        val paper = bg.optString("color", "#ffffff").takeIf { it.matches(Regex("#[0-9a-fA-F]{3,8}")) } ?: "#ffffff"
        val dark = bg.optBoolean("dark")
        val sb = StringBuilder()
        sb.append("<div class=\"paper${if (dark) " dark" else ""}\" style=\"width:${w.toInt()}px;height:${h.toInt()}px;background:$paper\">")
        sb.append("<div class=\"head\"><div class=\"title\">${ContentStore.escape(m.optString("title"))}</div><div class=\"stamp\">${stamp(m.optLong("created", System.currentTimeMillis()))}</div></div>")
        sb.append(inkSvg(m, w, h))
        items.forEach { it ->
            val x = num(it, "x").toInt(); val y = num(it, "y").toInt(); val iw = num(it, "w").toInt()
            val pos = "left:${x}px;top:${y}px;" + (if (iw > 0) "width:${iw}px;" else "")
            when (it.optString("t")) {
                "text" -> sb.append("<div class=\"item bx\" style=\"$pos\">${sanitize(it.optString("html"))}</div>")
                "image" -> sb.append("<img class=\"item\" style=\"$pos" + (if (num(it, "h") > 0) "height:${num(it, "h").toInt()}px;" else "") + "\" src=\"${ContentStore.escape(asset(it.optString("src")))}\" alt=\"\">")
                else -> {
                    val icon = if (it.optString("t") == "audio") "🎙" else "📎"
                    sb.append("<div class=\"item att\" style=\"$pos\"><b>$icon ${ContentStore.escape(it.optString("name", "Attachment"))}</b>")
                    if (it.optString("transcript").isNotBlank()) sb.append("<div class=\"tr\">${ContentStore.escape(it.optString("transcript"))}</div>")
                    sb.append("</div>")
                }
            }
        }
        sb.append("</div>")
        if (!standalone) return sb.toString()
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>${ContentStore.escape(m.optString("title").ifBlank { "Page" })}</title><style>${STATIC_CSS}</style></head><body>$sb</body></html>"
    }

    const val STATIC_CSS = """
      body { margin: 0; background: #e9ebef; font-family: Calibri, 'Segoe UI', Roboto, Arial, sans-serif; color: #1b1b1f; }
      .paper { position: relative; margin: 0 auto; box-shadow: 0 1px 8px rgba(0,0,0,.18); }
      .paper.dark { color: #e8e8ec; }
      .head { position: absolute; left: 24px; top: 18px; right: 24px; }
      .title { font-size: 22pt; font-weight: 300; border-bottom: 1px solid rgba(128,128,128,.45); padding-bottom: 4px; }
      .stamp { font-size: 9pt; color: #8a8f98; margin-top: 4px; }
      .ink { position: absolute; left: 0; top: 0; }
      .item { position: absolute; box-sizing: border-box; }
      .bx { padding: 4px 6px; font-size: 11pt; line-height: 1.4; overflow-wrap: anywhere; }
      .att { background: rgba(124,92,255,.08); border: 1px solid rgba(124,92,255,.35); border-radius: 10px; padding: 8px 10px; min-width: 180px; }
      .att .tr { font-size: 9.5pt; color: #555; margin-top: 6px; white-space: pre-wrap; }
      @media print { body { background: #fff; } .paper { box-shadow: none; margin: 0; } }
    """ + TAG_CSS

    /** The page as flowing HTML (top-to-bottom), for Word / PDF / Markdown / text export. */
    fun linear(json: String, asset: (String) -> String, inkImage: String? = null): String {
        val m = PageText.model(json)
        val sb = StringBuilder()
        sb.append("<h1 class=\"title\">${ContentStore.escape(m.optString("title").ifBlank { "Untitled page" })}</h1>")
        sb.append("<p><em>${stamp(m.optLong("created", System.currentTimeMillis()))}</em></p>")
        PageText.items(m).forEach { it ->
            when (it.optString("t")) {
                "text" -> sb.append(tagsAsText(sanitize(it.optString("html"))))
                "image" -> sb.append("<p><img src=\"${ContentStore.escape(asset(it.optString("src")))}\"></p>")
                "audio" -> {
                    sb.append("<p>🎙 <b>${ContentStore.escape(it.optString("name", "Recording"))}</b></p>")
                    if (it.optString("transcript").isNotBlank()) sb.append("<blockquote>${ContentStore.escape(it.optString("transcript"))}</blockquote>")
                }
                "file" -> sb.append("<p>📎 ${ContentStore.escape(it.optString("name", "Attachment"))}</p>")
            }
        }
        // handwriting and shapes keep their layout as one picture after the text
        if (inkImage != null) sb.append("<h3>Drawings</h3><p><img src=\"$inkImage\"></p>")
        return sb.toString()
    }

    /** Exporters don't know CSS tags, so tags become leading symbols. */
    fun tagsAsText(html: String): String {
        val doc = Jsoup.parseBodyFragment(html)
        doc.select("[data-tag]").forEach { el ->
            val t = el.attr("data-tag")
            val sym = if (t == "todo") (if (el.attr("data-done") == "1") "☑" else "☐") else Tags.of(t).symbol
            el.prependText("$sym ")
            el.removeAttr("data-tag"); el.removeAttr("data-done")
        }
        doc.select("ul.checklist > li").forEach { li -> li.prependText(if (li.hasClass("checked")) "☑ " else "☐ ") }
        return doc.body().html()
    }
}

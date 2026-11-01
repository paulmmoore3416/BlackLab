package com.blacklab.app.brainstorm

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import com.blacklab.app.AppContainer
import com.blacklab.app.app
import com.blacklab.app.core.Profile
import com.blacklab.app.core.Recurrence
import com.blacklab.app.core.Time
import com.blacklab.app.data.ContentStore
import com.blacklab.app.data.NoteEntity
import com.blacklab.app.data.NoteLabelEntity
import com.blacklab.app.reminders.Notifications
import com.blacklab.app.reminders.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Keep-style note colours: (name, light, dark). Index 0 = default surface. */
data class NoteColor(val name: String, val light: Long, val dark: Long)

object NoteStyle {
    val colors = listOf(
        NoteColor("Default", 0x00000000, 0x00000000),
        NoteColor("Coral", 0xFFFAAFA8, 0xFF77172E),
        NoteColor("Peach", 0xFFF39F76, 0xFF692B17),
        NoteColor("Sand", 0xFFFFF8B8, 0xFF7C4A03),
        NoteColor("Mint", 0xFFE2F6D3, 0xFF264D3B),
        NoteColor("Sage", 0xFFB4DDD3, 0xFF0C625D),
        NoteColor("Fog", 0xFFD4E4ED, 0xFF256377),
        NoteColor("Storm", 0xFFAECCDC, 0xFF284255),
        NoteColor("Dusk", 0xFFD3BFDB, 0xFF472E5B),
        NoteColor("Blossom", 0xFFF6E2DD, 0xFF6C394F),
        NoteColor("Clay", 0xFFE9E3D4, 0xFF4B443A),
        NoteColor("Chalk", 0xFFEFEFF1, 0xFF232427),
    )
    /** Background themes: (id, label, emoji watermark). */
    val backgrounds = listOf(
        Triple("", "None", ""), Triple("groceries", "Groceries", "🛒"), Triple("food", "Food", "🍲"), Triple("music", "Music", "🎵"),
        Triple("recipes", "Recipes", "🍳"), Triple("notes", "Notes", "📝"), Triple("places", "Places", "📍"), Triple("travel", "Travel", "✈️"),
        Triple("video", "Video", "🎬"), Triple("celebration", "Celebration", "🎉"), Triple("work", "Work", "🛠️"), Triple("ideas", "Ideas", "💡"),
    )
    fun bgEmoji(id: String) = backgrounds.firstOrNull { it.first == id }?.third.orEmpty()
}

data class NoteItem(val text: String, val done: Boolean = false, val indent: Int = 0)
data class NoteAttachment(val kind: String, val path: String, val transcript: String = "", val ocr: String = "", val durationMs: Long = 0)

object NoteOps {
    const val TRASH_DAYS = 7L

    fun items(n: NoteEntity): List<NoteItem> = parseItems(n.items)
    fun parseItems(json: String): List<NoteItem> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).map { a.getJSONObject(it) }.map { NoteItem(it.optString("t"), it.optBoolean("d"), it.optInt("i")) }
    }.getOrDefault(emptyList())
    fun itemsJson(items: List<NoteItem>): String = JSONArray().apply { items.forEach { put(JSONObject().put("t", it.text).put("d", it.done).put("i", it.indent)) } }.toString()

    fun attachments(n: NoteEntity): List<NoteAttachment> = parseAttachments(n.attachments)
    fun parseAttachments(json: String): List<NoteAttachment> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).map { a.getJSONObject(it) }.map { NoteAttachment(it.optString("k"), it.optString("p"), it.optString("tr"), it.optString("ocr"), it.optLong("d")) }
    }.getOrDefault(emptyList())
    fun attachmentsJson(list: List<NoteAttachment>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("k", it.kind).put("p", it.path).put("tr", it.transcript).put("ocr", it.ocr).put("d", it.durationMs)) }
    }.toString()

    fun labels(n: NoteEntity): List<String> = splitLabels(n.labels)
    fun splitLabels(s: String) = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    fun joinLabels(l: Collection<String>) = if (l.isEmpty()) "" else l.distinct().joinToString(",", prefix = ",", postfix = ",")

    /** One line describing the note, for reminders, search and previews. */
    fun summary(n: NoteEntity): String = if (n.checklist) {
        val it = items(n); val open = it.filter { !it.done }
        (open.take(3).joinToString(" · ") { x -> x.text }) + if (it.isNotEmpty()) "  (${it.count { x -> x.done }}/${it.size} done)" else ""
    } else n.body.replace(Regex("\\s+"), " ").take(160)

    fun plainText(n: NoteEntity): String = buildString {
        if (n.title.isNotBlank()) append(n.title).append('\n')
        if (n.checklist) items(n).forEach { append(if (it.done) "☑ " else "☐ ").append("  ".repeat(it.indent)).append(it.text).append('\n') } else append(n.body)
        attachments(n).forEach { a -> if (a.transcript.isNotBlank()) append("\n").append(a.transcript); if (a.ocr.isNotBlank()) append("\n").append(a.ocr) }
    }.trim()

    fun matches(n: NoteEntity, q: String): Boolean = q.isBlank() || plainText(n).contains(q, true) || n.labels.contains(q, true)

    suspend fun save(app: AppContainer, n: NoteEntity): Long {
        val dao = app.db.notes()
        val old = if (n.id != 0L) dao.get(n.id) else null
        val id = if (n.id == 0L) dao.insert(n.copy(sortOrder = (dao.maxOrder(n.profile) ?: 0) + 1)) else { dao.update(n.copy(updatedAt = Time.now())); n.id }
        if (n.reminderAt != old?.reminderAt) app.requestReminderSync()
        val placeChanged = n.placeLat != old?.placeLat || n.placeLon != old?.placeLon || n.placeRadius != old?.placeRadius ||
            (n.trashedAt != null) != (old?.trashedAt != null) || n.archived != (old?.archived ?: false)
        if (placeChanged && (n.placeLat != null || old?.placeLat != null)) PlaceReminders.sync(app.context)
        return id
    }

    suspend fun trash(app: AppContainer, n: NoteEntity) { save(app, n.copy(trashedAt = Time.now(), pinned = false)); app.db.brainstorm().deleteSharesFor("NOTE", n.id) }
    suspend fun restore(app: AppContainer, n: NoteEntity) = save(app, n.copy(trashedAt = null))

    suspend fun deleteForever(app: AppContainer, n: NoteEntity) {
        File(Brainstorm.root(app, Profile.from(n.profile)), "notes/${n.id}").deleteRecursively()
        app.db.brainstorm().deleteNoteComments(n.id)
        app.db.brainstorm().deleteSharesFor("NOTE", n.id)
        app.db.notes().delete(n)
        app.requestReminderSync()
        PlaceReminders.sync(app.context)
    }

    suspend fun purgeTrash(app: AppContainer) = app.db.notes().trashedBefore(Time.now() - TRASH_DAYS * 86_400_000L).forEach { deleteForever(app, it) }

    suspend fun copy(app: AppContainer, n: NoteEntity): Long {
        val id = save(app, n.copy(id = 0, title = n.title, pinned = false, reminderAt = null, createdAt = Time.now(), updatedAt = Time.now()))
        val src = File(Brainstorm.root(app, Profile.from(n.profile)), "notes/${n.id}")
        if (src.isDirectory) {
            val dst = Brainstorm.noteDir(app, Profile.from(n.profile), id)
            src.copyRecursively(dst, overwrite = true)
            val moved = attachments(n).map { a -> a.copy(path = a.path.replace("/notes/${n.id}/", "/notes/$id/")) }
            app.db.notes().get(id)?.let { app.db.notes().update(it.copy(attachments = attachmentsJson(moved))) }
        }
        return id
    }

    /** Advances repeating reminders after they fire; one-off reminders are cleared. */
    suspend fun afterReminders(app: AppContainer, noteIds: List<Long>) {
        noteIds.distinct().forEach { id ->
            val n = app.db.notes().get(id) ?: return@forEach
            val at = n.reminderAt ?: return@forEach
            val rule = Recurrence.parse(n.repeatRule)
            val next = rule?.let {
                val day = Time.toDate(at)
                it.nextAfter(day, maxOf(day, Time.today()))?.let { d -> at + (d.toEpochDay() - day.toEpochDay()) * 86_400_000L }
            }
            app.db.notes().update(n.copy(reminderAt = next, repeatRule = if (next == null) null else n.repeatRule))
        }
        app.requestReminderSync()
    }

    suspend fun renameLabel(app: AppContainer, profile: Profile, label: NoteLabelEntity, newName: String) {
        val dao = app.db.notes()
        dao.updateLabel(label.copy(name = newName))
        dao.allFor(profile.name).filter { label.name in labels(it) }.forEach { n -> dao.update(n.copy(labels = joinLabels(labels(n).map { if (it == label.name) newName else it }))) }
    }

    suspend fun deleteLabel(app: AppContainer, profile: Profile, label: NoteLabelEntity) {
        val dao = app.db.notes()
        dao.deleteLabel(label)
        dao.allFor(profile.name).filter { label.name in labels(it) }.forEach { n -> dao.update(n.copy(labels = joinLabels(labels(n) - label.name))) }
    }

    /** Sends a note to a notebook section as a new page (pictures become page pictures). */
    suspend fun toPage(app: AppContainer, n: NoteEntity, sectionId: Long): Long {
        val profile = Profile.from(n.profile)
        val e = ContentStore::escape
        val html = if (n.checklist) "<ul class=\"checklist\">" + items(n).joinToString("") { "<li${if (it.done) " class=\"checked\"" else ""}${if (it.indent > 0) " style=\"margin-left:${it.indent * 24}px\"" else ""}>${e(it.text)}</li>" } + "</ul>"
        else ContentStore.textToHtml(n.body)
        val labelsLine = labels(n).takeIf { it.isNotEmpty() }?.let { "<p style=\"color:#888\">🏷 ${e(it.joinToString(", "))}</p>" } ?: ""
        val items = JSONArray().put(PageTemplates.text(20, 90, 370, html + labelsLine))
        val json = JSONObject().put("v", 1).put("title", n.title.ifBlank { summary(n).take(60) }).put("created", n.createdAt)
            .put("bg", JSONObject().put("color", "#ffffff").put("rule", "none")).put("items", items).put("ink", JSONArray()).toString()
        val pageId = Brainstorm.createPage(app, profile, sectionId, "blank", n.title, json = json)
        val page = app.db.brainstorm().page(pageId) ?: return pageId
        val m = PageText.model(Brainstorm.read(app, page))
        var y = 90 + PageHtml.estimateHeight(items.getJSONObject(0)).toInt() + 20
        attachments(n).forEach { a ->
            val f = app.storage.resolve(a.path)
            if (!f.exists()) return@forEach
            val ref = Brainstorm.addAsset(app, page, f.extension) { out -> f.copyTo(out, overwrite = true) }
            val item = when (a.kind) {
                "audio" -> JSONObject().put("t", "audio").put("name", "Voice note").put("path", ref).put("dur", a.durationMs).put("transcript", a.transcript).put("w", 300)
                else -> {
                    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(f.absolutePath, o)
                    val w = minOf(360, o.outWidth.coerceAtLeast(1)); val h = (w.toDouble() * o.outHeight / o.outWidth.coerceAtLeast(1)).toInt()
                    JSONObject().put("t", "image").put("src", ref).put("w", w).put("h", h).put("ocr", a.ocr)
                }
            }
            item.put("id", "n" + System.nanoTime().toString(36)).put("x", 20).put("y", y)
            y += (if (a.kind == "audio") 90 else item.optInt("h", 200)) + 20
            m.getJSONArray("items").put(item)
        }
        Brainstorm.save(app, pageId, m.toString())
        return pageId
    }
}

/**
 * Location reminders ("remind me when I get to the hardware store"): platform proximity alerts,
 * re-registered at boot and whenever notes change. Needs precise + background location.
 */
object PlaceReminders {
    const val ACTION = "com.blacklab.app.action.NOTE_PLACE"
    const val EXTRA_NOTE = "note_id"
    private const val PREFS = "blacklab_places"

    fun hasPermission(c: Context) = c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun hasBackground(c: Context) = c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun pi(c: Context, noteId: Long, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(c, (7_000_000 + noteId).toInt(), Intent(c, PlaceReceiver::class.java).setAction(ACTION).putExtra(EXTRA_NOTE, noteId), flags)

    @SuppressLint("MissingPermission")
    suspend fun sync(c: Context) {
        val app = c.app
        val lm = c.getSystemService(LocationManager::class.java) ?: return
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = prefs.getStringSet("ids", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()
        val notes = app.db.notes().withPlaces()
        val wanted = notes.map { it.id }.toSet()
        (old - wanted).forEach { id -> pi(c, id, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_MUTABLE)?.let { runCatching { lm.removeProximityAlert(it) }; it.cancel() } }
        if (hasPermission(c)) notes.forEach { n ->
            val p = pi(c, n.id, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE) ?: return@forEach
            runCatching { lm.removeProximityAlert(p) }
            runCatching { lm.addProximityAlert(n.placeLat!!, n.placeLon!!, n.placeRadius.coerceIn(80, 2000).toFloat(), -1, p) }
        }
        prefs.edit().putStringSet("ids", wanted.map { it.toString() }.toSet()).apply()
    }
}

class PlaceReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        scope.launch {
            try {
                when (intent.action) {
                    PlaceReminders.ACTION -> {
                        val id = intent.getLongExtra(PlaceReminders.EXTRA_NOTE, 0)
                        val entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, true)
                        val n = context.app.db.notes().get(id) ?: return@launch
                        if (n.trashedAt != null || n.archived || n.placeLat == null) return@launch
                        if (entering == n.placeOnLeave) return@launch
                        Notifications.show(context, ReminderScheduler.Due("place:${n.id}:${Time.now() / 60_000}", ReminderScheduler.Kind.NOTE, n.id, Time.now(),
                            n.title.ifBlank { "Note" } + if (n.placeName.isNotBlank()) (if (entering) " · at " else " · leaving ") + n.placeName else "",
                            NoteOps.summary(n).ifBlank { "Location reminder" }, "note/${n.id}", context.app.settings.profileName(Profile.from(n.profile))))
                    }
                }
            } finally { pending.finish() }
        }
    }
}

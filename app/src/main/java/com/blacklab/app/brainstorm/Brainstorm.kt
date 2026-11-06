package com.blacklab.app.brainstorm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.util.Base64
import com.blacklab.app.AppContainer
import com.blacklab.app.assistant.MeetingActions
import com.blacklab.app.assistant.Nano
import com.blacklab.app.core.Files
import com.blacklab.app.core.Folder
import com.blacklab.app.core.Profile
import com.blacklab.app.core.Time
import com.blacklab.app.data.ContentStore
import com.blacklab.app.data.NotebookEntity
import com.blacklab.app.data.PageEntity
import com.blacklab.app.data.SectionEntity
import com.blacklab.app.data.TaskEntity
import com.blacklab.app.data.TaskOps
import com.blacklab.app.docs.DocExporter
import com.blacklab.app.docs.ExportFormat
import com.blacklab.app.docs.PageSetup
import com.blacklab.app.ui.editor.EditorController
import com.blacklab.app.ui.tasks.QuickParse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.SecretKey
import kotlin.math.max

/** Unlocked section keys live only in memory and expire after 20 idle minutes. */
object SectionKeys {
    private const val IDLE_MS = 20 * 60_000L
    private val keys = ConcurrentHashMap<Long, Pair<SecretKey, Long>>()
    fun put(sectionId: Long, key: SecretKey) { keys[sectionId] = key to System.currentTimeMillis() }
    fun get(sectionId: Long): SecretKey? {
        val (k, t) = keys[sectionId] ?: return null
        if (System.currentTimeMillis() - t > IDLE_MS) { keys.remove(sectionId); return null }
        keys[sectionId] = k to System.currentTimeMillis()
        return k
    }
    fun lock(sectionId: Long) { keys.remove(sectionId) }
    fun lockAll() = keys.clear()
    val anyUnlocked get() = keys.isNotEmpty()
}

class LockedException : Exception("This section is locked")

/**
 * Files behind Brainstorm. Every page lives in its own folder so moving it between sections or
 * notebooks is only a database change:
 *
 *   <Profile>/Brainstorm/pages/<pageId>/page.json      the canvas (text boxes, pictures, ink…)
 *   <Profile>/Brainstorm/pages/<pageId>/assets/…        pictures, recordings and attached files
 *   <Profile>/Brainstorm/pages/<pageId>/versions/…      page history snapshots
 *   <Profile>/Brainstorm/notes/<noteId>/…               Keep-style note pictures, drawings, voice
 *
 * Pages in password-protected sections are encrypted (page, assets and history).
 */
object Brainstorm {
    private const val VERSION_EVERY_MS = 10 * 60_000L
    private const val VERSIONS_KEPT = 40
    const val TRASH_DAYS = 60L

    @OptIn(ExperimentalCoroutinesApi::class)
    private val writer = Dispatchers.IO.limitedParallelism(1)

    val SectionColors = listOf(0xFF7C5CFF, 0xFF4F8CFF, 0xFF2BD9A8, 0xFFFFB74D, 0xFFFF7A59, 0xFFEF5350, 0xFFAB47BC, 0xFF26C6DA, 0xFF9CCC65, 0xFF8D6E63,
        0xFF3EE6C4, 0xFFE040FB, 0xFF536DFE, 0xFFE0955E, 0xFF7CC8FF, 0xFFB8F15A).map { it.toInt() }

    // ---------------------------------------------------------------- paths
    fun root(app: AppContainer, profile: Profile): File = app.storage.dir(profile, Folder.BRAINSTORM)
    fun pageDir(app: AppContainer, page: PageEntity): File = File(root(app, Profile.from(page.profile)), "pages/${page.id}")
    fun pageFile(app: AppContainer, page: PageEntity) = File(pageDir(app, page), "page.json")
    fun assetDir(app: AppContainer, page: PageEntity) = File(pageDir(app, page), "assets").also { it.mkdirs() }
    fun versionDir(app: AppContainer, page: PageEntity) = File(pageDir(app, page), "versions")
    fun noteDir(app: AppContainer, profile: Profile, noteId: Long) = File(root(app, profile), "notes/$noteId").also { it.mkdirs() }

    suspend fun keyFor(app: AppContainer, sectionId: Long): SecretKey? {
        val s = app.db.brainstorm().section(sectionId) ?: return null
        if (!s.locked) return null
        return SectionKeys.get(sectionId) ?: throw LockedException()
    }

    private fun readBytes(f: File, key: SecretKey?): ByteArray {
        val b = f.readBytes()
        return if (PageCrypto.isEncrypted(b)) PageCrypto.decrypt(key ?: throw LockedException(), b) else b
    }

    private fun writeBytes(f: File, bytes: ByteArray, key: SecretKey?) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, ".${f.name}.tmp")
        tmp.writeBytes(if (key != null) PageCrypto.encrypt(key, bytes) else bytes)
        if (!tmp.renameTo(f)) { f.writeBytes(tmp.readBytes()); tmp.delete() }
    }

    // ---------------------------------------------------------------- page content
    suspend fun read(app: AppContainer, page: PageEntity): String = withContext(Dispatchers.IO) {
        val f = pageFile(app, page)
        if (!f.exists()) return@withContext PageTemplates.build("blank", page.title, page.createdAt)
        String(readBytes(f, keyFor(app, page.sectionId)))
    }

    /** Writes the canvas, keeps a history snapshot every 10 minutes, and refreshes search text & tags. */
    suspend fun save(app: AppContainer, pageId: Long, json: String) = withContext(writer) {
        val dao = app.db.brainstorm()
        val page = dao.page(pageId) ?: return@withContext
        val key = keyFor(app, page.sectionId)
        val f = pageFile(app, page)
        if (f.exists()) {
            val vdir = versionDir(app, page)
            val last = vdir.listFiles()?.maxOfOrNull { it.lastModified() } ?: 0L
            if (System.currentTimeMillis() - last > VERSION_EVERY_MS) {
                vdir.mkdirs()
                runCatching { f.copyTo(File(vdir, "${System.currentTimeMillis()}.json"), overwrite = true) }
                vdir.listFiles()?.sortedByDescending { it.name }?.drop(VERSIONS_KEPT)?.forEach { it.delete() }
            }
        }
        writeBytes(f, json.toByteArray(), key)
        val ex = PageText.extract(json)
        dao.updatePage(
            page.copy(
                title = ex.title,
                preview = if (key != null) "" else ex.preview,
                searchText = if (key != null) "" else ex.text.take(60_000),
                tagIndex = if (key != null) "" else ex.tagIndex,
                wordCount = ex.words,
                updatedAt = Time.now(),
            )
        )
    }

    data class Version(val file: File, val time: Long)

    fun versions(app: AppContainer, page: PageEntity): List<Version> =
        versionDir(app, page).listFiles { f -> f.extension == "json" }?.mapNotNull { f -> f.nameWithoutExtension.toLongOrNull()?.let { Version(f, it) } }
            ?.sortedByDescending { it.time } ?: emptyList()

    suspend fun readVersion(app: AppContainer, page: PageEntity, v: Version): String = withContext(Dispatchers.IO) { String(readBytes(v.file, keyFor(app, page.sectionId))) }

    suspend fun restoreVersion(app: AppContainer, page: PageEntity, v: Version) {
        val json = readVersion(app, page, v)
        save(app, page.id, json)
    }

    // ---------------------------------------------------------------- assets
    suspend fun addAsset(app: AppContainer, page: PageEntity, ext: String, write: (File) -> Unit): String = withContext(Dispatchers.IO) {
        val key = keyFor(app, page.sectionId)
        val name = UUID.randomUUID().toString().take(13) + "." + ext.lowercase().ifBlank { "bin" }
        val target = File(assetDir(app, page), name)
        if (key == null) write(target)
        else {
            val tmp = File(app.context.cacheDir, "bs_" + name)
            write(tmp)
            writeBytes(target, tmp.readBytes(), key)
            tmp.delete()
        }
        "asset:$name"
    }

    suspend fun addAssetFromUri(app: AppContainer, page: PageEntity, uri: Uri, ext: String): String =
        addAsset(app, page, ext) { f -> app.context.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } } }

    /** Asset bytes (decrypted when the section is unlocked). */
    fun assetBytes(app: AppContainer, page: PageEntity, ref: String, key: SecretKey?): ByteArray? {
        val name = File(ref.removePrefix("asset:")).name
        val f = File(assetDir(app, page), name)
        if (!f.isFile) return null
        return runCatching { readBytes(f, key) }.getOrNull()
    }

    /** A readable copy of an asset for opening/sharing (decrypted into the cache for locked sections). */
    suspend fun assetFile(app: AppContainer, page: PageEntity, ref: String, displayName: String? = null): File? = withContext(Dispatchers.IO) {
        val name = File(ref.removePrefix("asset:")).name
        val f = File(assetDir(app, page), name)
        if (!f.isFile) return@withContext null
        val key = keyFor(app, page.sectionId)
        val head = f.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
        if (!PageCrypto.isEncrypted(head + byteArrayOf(0)) && displayName == null) return@withContext f
        val out = File(app.storage.openedDir, Files.sanitize(displayName ?: name))
        out.writeBytes(readBytes(f, key))
        out
    }

    fun mime(name: String) = Files.mime(File(name))

    fun dataUri(bytes: ByteArray, name: String): String {
        val tmp = File.createTempFile("bs_img_", "." + File(name).extension.ifBlank { "jpg" }) // prefix must be ≥ 3 chars
        return try { tmp.writeBytes(bytes); EditorController.imageDataUri(tmp, 1400) ?: "" } finally { tmp.delete() }
    }

    // ---------------------------------------------------------------- notebooks, sections, pages
    suspend fun ensureStarter(app: AppContainer, profile: Profile): Long? {
        val dao = app.db.brainstorm()
        if (dao.notebooks(profile.name).isNotEmpty()) return null
        val nb = dao.insertNotebook(NotebookEntity(profile = profile.name, name = if (profile == Profile.PROFESSIONAL) "Work Notebook" else "My Notebook", color = SectionColors[0]))
        val sec = dao.insertSection(SectionEntity(notebookId = nb, name = "Quick Notes", color = SectionColors[1]))
        val p = createPage(app, profile, sec, "blank", "Welcome to Brainstorm")
        save(app, p, welcomePage())
        dao.insertSection(SectionEntity(notebookId = nb, name = "Ideas", color = SectionColors[3], sortOrder = 1))
        return nb
    }

    private fun welcomePage(): String {
        val t = PageTemplates
        val items = JSONArray()
            .put(t.text(20, 90, 370, "<h2>Your thinking space</h2><p>Tap anywhere on the page to start a <b>text box</b>. Drag its top bar to move it, the right edge to resize.</p>"))
            .put(t.text(20, 220, 370, "<p data-tag=\"todo\" data-done=\"0\">Tick me — <b>To Do</b> tags collect in <i>Brainstorm › Tags</i></p><p data-tag=\"important\">Tags: Important, Question, Idea, Critical and more (Home › Tag)</p><p data-tag=\"idea\">Switch to <b>Draw</b> for pens, highlighter, shapes, eraser and lasso</p><p>Quick math: type <b>12*7=</b> then a space</p>"))
            .put(t.text(20, 420, 370, "<h3>Insert</h3><ul><li>Pictures (text inside them becomes searchable)</li><li>Voice recordings with live transcripts</li><li>Files, tables, links to other pages</li></ul><h3>Share</h3><ul><li>Export to PDF, Word, Markdown</li><li><b>Live Share</b> a page to anyone on your Wi-Fi</li></ul>"))
        val ink = JSONArray().put(t.line(250.0, 76.0, 380.0, 76.0, "#7c5cff", 3.0, "arrow"))
        return JSONObject().put("v", 1).put("title", "Welcome to Brainstorm").put("created", System.currentTimeMillis())
            .put("bg", JSONObject().put("color", "#ffffff").put("rule", "none")).put("items", items).put("ink", ink).toString()
    }

    suspend fun createNotebook(app: AppContainer, profile: Profile, name: String, color: Int): Long {
        val dao = app.db.brainstorm()
        val order = (dao.notebooks(profile.name).maxOfOrNull { it.sortOrder } ?: -1) + 1
        val nb = dao.insertNotebook(NotebookEntity(profile = profile.name, name = name, color = color, sortOrder = order))
        val sec = dao.insertSection(SectionEntity(notebookId = nb, name = "New Section 1", color = SectionColors[(order + 1) % SectionColors.size]))
        createPage(app, profile, sec, "blank", "")
        return nb
    }

    suspend fun createSection(app: AppContainer, notebookId: Long, name: String, color: Int, group: String = ""): Long {
        val dao = app.db.brainstorm()
        val order = (dao.sections(notebookId).maxOfOrNull { it.sortOrder } ?: -1) + 1
        return dao.insertSection(SectionEntity(notebookId = notebookId, name = name, color = color, groupName = group, sortOrder = order))
    }

    /** New page at the end of the section (or right after [after], as a subpage when [level] > 0). */
    suspend fun createPage(app: AppContainer, profile: Profile, sectionId: Long, template: String, title: String, level: Int = 0, after: PageEntity? = null, json: String? = null): Long {
        val dao = app.db.brainstorm()
        val pages = dao.pages(sectionId)
        val order = if (after != null) {
            pages.filter { it.sortOrder > after.sortOrder }.forEach { dao.updatePage(it.copy(sortOrder = it.sortOrder + 1)) }
            after.sortOrder + 1
        } else (pages.maxOfOrNull { it.sortOrder } ?: -1) + 1
        val id = dao.insertPage(PageEntity(profile = profile.name, sectionId = sectionId, title = title, level = level, sortOrder = order))
        save(app, id, json ?: PageTemplates.build(template, title))
        dao.page(id)?.let { app.db.brainstorm().updateNotebookTouched(app, it) }
        return id
    }

    private suspend fun com.blacklab.app.data.BrainstormDao.updateNotebookTouched(app: AppContainer, page: PageEntity) {
        val s = section(page.sectionId) ?: return
        notebook(s.notebookId)?.let { updateNotebook(it.copy(updatedAt = Time.now())) }
    }

    suspend fun duplicatePage(app: AppContainer, page: PageEntity): Long = copyPageTo(app, page, page.sectionId, after = page, titleSuffix = " (copy)")

    /** Copies a page (canvas, assets, re-encrypting as needed) into [sectionId]. */
    suspend fun copyPageTo(app: AppContainer, page: PageEntity, sectionId: Long, after: PageEntity? = null, titleSuffix: String = ""): Long = withContext(Dispatchers.IO) {
        val dao = app.db.brainstorm()
        val srcKey = keyFor(app, page.sectionId)
        val dstKey = keyFor(app, sectionId)
        val json = read(app, page).let { j -> if (titleSuffix.isEmpty()) j else PageText.model(j).put("title", page.title + titleSuffix).toString() }
        val id = createPage(app, Profile.from(page.profile), sectionId, "blank", page.title + titleSuffix, page.level, after, json)
        val np = dao.page(id)!!
        assetDir(app, page).listFiles()?.forEach { f -> writeBytes(File(assetDir(app, np), f.name), readBytes(f, srcKey), dstKey) }
        id
    }

    /** Moves a page to another section (any notebook); re-encrypts when crossing a section password. */
    suspend fun movePage(app: AppContainer, page: PageEntity, sectionId: Long) = withContext(writer) {
        if (page.sectionId == sectionId) return@withContext
        val dao = app.db.brainstorm()
        val srcKey = keyFor(app, page.sectionId)
        val dstKey = keyFor(app, sectionId)
        if (srcKey != dstKey) recrypt(app, page, srcKey, dstKey)
        val order = (dao.pages(sectionId).maxOfOrNull { it.sortOrder } ?: -1) + 1
        val moved = page.copy(sectionId = sectionId, sortOrder = order, level = 0)
        dao.updatePage(moved)
        if (dstKey == null && srcKey != null) {
            // was hidden from search while locked; index it again
            val ex = PageText.extract(String(readBytes(pageFile(app, moved), null)))
            dao.updatePage(moved.copy(preview = ex.preview, searchText = ex.text.take(60_000), tagIndex = ex.tagIndex))
        } else if (dstKey != null) dao.updatePage(moved.copy(preview = "", searchText = "", tagIndex = ""))
    }

    private fun recrypt(app: AppContainer, page: PageEntity, from: SecretKey?, to: SecretKey?) {
        val files = listOfNotNull(pageFile(app, page).takeIf { it.exists() }) +
            (assetDir(app, page).listFiles()?.toList() ?: emptyList()) + (versionDir(app, page).listFiles()?.toList() ?: emptyList())
        files.forEach { f -> writeBytes(f, readBytes(f, from), to) }
    }

    suspend fun reorder(app: AppContainer, pages: List<PageEntity>) {
        val dao = app.db.brainstorm()
        pages.forEachIndexed { i, p -> if (p.sortOrder != i) dao.updatePage(p.copy(sortOrder = i)) }
    }

    suspend fun trashPage(app: AppContainer, page: PageEntity) {
        app.db.brainstorm().updatePage(page.copy(deletedAt = Time.now()))
        app.db.brainstorm().deleteSharesFor("PAGE", page.id)
    }

    /** Restores a page from the recycle bin; if its section is gone it lands in "Restored pages". */
    suspend fun restorePage(app: AppContainer, page: PageEntity): Boolean {
        val dao = app.db.brainstorm()
        var sectionId = page.sectionId
        if (dao.section(sectionId) == null) {
            // pages from a deleted password-protected section stay encrypted and their key went with the section
            if (pageFile(app, page).let { it.exists() && PageCrypto.isEncrypted(it.readBytes()) }) return false
            val profile = Profile.from(page.profile)
            val nb = dao.notebooks(profile.name).firstOrNull()?.id ?: createNotebook(app, profile, "My Notebook", SectionColors[0])
            sectionId = dao.sections(nb).firstOrNull { it.name == "Restored pages" }?.id ?: createSection(app, nb, "Restored pages", SectionColors[5])
        }
        val order = (dao.pages(sectionId).maxOfOrNull { it.sortOrder } ?: -1) + 1
        dao.updatePage(page.copy(sectionId = sectionId, deletedAt = null, sortOrder = order, level = 0))
        return true
    }

    suspend fun purgePage(app: AppContainer, page: PageEntity) = withContext(Dispatchers.IO) {
        pageDir(app, page).deleteRecursively()
        app.db.brainstorm().deletePageComments(page.id)
        app.db.brainstorm().deleteSharesFor("PAGE", page.id)
        app.db.brainstorm().deletePage(page)
    }

    suspend fun purgeOld(app: AppContainer) {
        app.db.brainstorm().deletedBefore(Time.now() - TRASH_DAYS * 86_400_000L).forEach { purgePage(app, it) }
    }

    suspend fun deleteSection(app: AppContainer, section: SectionEntity) {
        val dao = app.db.brainstorm()
        dao.allPagesIn(section.id).forEach { if (it.deletedAt == null) trashPage(app, it) }
        SectionKeys.lock(section.id)
        dao.deleteSection(section)
    }

    suspend fun deleteNotebook(app: AppContainer, nb: NotebookEntity) {
        val dao = app.db.brainstorm()
        dao.sections(nb.id).forEach { deleteSection(app, it) }
        dao.deleteNotebook(nb)
    }

    // ---------------------------------------------------------------- section passwords
    suspend fun lockSection(app: AppContainer, section: SectionEntity, password: String, hint: String) = withContext(writer) {
        val salt = PageCrypto.newSalt()
        val key = PageCrypto.deriveKey(password.toCharArray(), salt)
        val dao = app.db.brainstorm()
        dao.allPagesIn(section.id).forEach { p ->
            recrypt(app, p, null, key)
            dao.updatePage(p.copy(preview = "", searchText = "", tagIndex = ""))
            dao.deleteSharesFor("PAGE", p.id)
        }
        dao.updateSection(section.copy(locked = true, lockSalt = salt, lockCheck = PageCrypto.check(key), lockHint = hint))
        SectionKeys.put(section.id, key)
    }

    suspend fun unlockSection(section: SectionEntity, password: String): Boolean = withContext(Dispatchers.Default) {
        val key = PageCrypto.deriveKey(password.toCharArray(), section.lockSalt)
        if (!PageCrypto.verify(key, section.lockCheck)) return@withContext false
        SectionKeys.put(section.id, key); true
    }

    suspend fun removeLock(app: AppContainer, section: SectionEntity, password: String): Boolean = withContext(writer) {
        val key = PageCrypto.deriveKey(password.toCharArray(), section.lockSalt)
        if (!PageCrypto.verify(key, section.lockCheck)) return@withContext false
        val dao = app.db.brainstorm()
        dao.allPagesIn(section.id).forEach { p ->
            recrypt(app, p, key, null)
            val f = pageFile(app, p)
            if (f.exists()) { val ex = PageText.extract(f.readText()); dao.updatePage(p.copy(preview = ex.preview, searchText = ex.text.take(60_000), tagIndex = ex.tagIndex)) }
        }
        dao.updateSection(section.copy(locked = false, lockSalt = "", lockCheck = "", lockHint = ""))
        SectionKeys.lock(section.id)
        true
    }

    // ---------------------------------------------------------------- export & share
    fun inkPng(m: JSONObject): String? {
        val b = PageHtml.inkBounds(m) ?: return null
        val w = (b.x2 - b.x1).toInt().coerceIn(1, 3000); val h = (b.y2 - b.y1).toInt().coerceIn(1, 4000)
        val scale = 2f
        val bmp = Bitmap.createBitmap((w * scale).toInt(), (h * scale).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(Color.WHITE); c.scale(scale, scale); c.translate(-b.x1.toFloat(), -b.y1.toFloat())
        val ink = m.optJSONArray("ink") ?: return null
        for (k in 0 until ink.length()) {
            val s = ink.optJSONObject(k) ?: continue
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = PageHtml.strokeWidth(s).toFloat()
                color = runCatching { Color.parseColor(s.optString("c", "#000000")) }.getOrDefault(Color.BLACK)
                if (s.optBoolean("hl")) alpha = 97
                strokeCap = if (s.optBoolean("hl")) Paint.Cap.BUTT else Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            }
            c.drawPath(androidPath(s), paint)
        }
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun androidPath(s: JSONObject): Path {
        val p = s.optJSONArray("pts") ?: return Path()
        val v = FloatArray(p.length()) { p.optDouble(it, 0.0).toFloat() }
        val path = Path()
        when (s.optString("shape")) {
            "line", "arrow" -> {
                path.moveTo(v[0], v[1]); path.lineTo(v[2], v[3])
                if (s.optString("shape") == "arrow") {
                    val a = kotlin.math.atan2((v[3] - v[1]).toDouble(), (v[2] - v[0]).toDouble()); val l = max(10.0, s.optDouble("w", 2.0) * 4)
                    path.moveTo((v[2] - l * kotlin.math.cos(a - 0.45)).toFloat(), (v[3] - l * kotlin.math.sin(a - 0.45)).toFloat()); path.lineTo(v[2], v[3])
                    path.lineTo((v[2] - l * kotlin.math.cos(a + 0.45)).toFloat(), (v[3] - l * kotlin.math.sin(a + 0.45)).toFloat())
                }
            }
            "rect" -> path.addRect(minOf(v[0], v[2]), minOf(v[1], v[3]), maxOf(v[0], v[2]), maxOf(v[1], v[3]), Path.Direction.CW)
            "ellipse" -> path.addOval(minOf(v[0], v[2]), minOf(v[1], v[3]), maxOf(v[0], v[2]), maxOf(v[1], v[3]), Path.Direction.CW)
            else -> {
                if (v.size < 3) return path
                path.moveTo(v[0], v[1])
                if (v.size < 6) path.lineTo(v[0] + 0.1f, v[1])
                var i = 3
                while (i < v.size - 3) { path.quadTo(v[i], v[i + 1], (v[i] + v[i + 3]) / 2, (v[i + 1] + v[i + 4]) / 2); i += 3 }
                if (v.size >= 6) path.lineTo(v[v.size - 3], v[v.size - 2])
            }
        }
        return path
    }

    /** Page as a stand-alone HTML string with pictures inlined (print, export). */
    suspend fun staticHtml(app: AppContainer, page: PageEntity, json: String? = null): String = withContext(Dispatchers.IO) {
        val j = json ?: read(app, page)
        val key = keyFor(app, page.sectionId)
        PageHtml.static(j, { ref -> assetBytes(app, page, ref, key)?.let { dataUri(it, ref) } ?: "" })
    }

    suspend fun export(app: AppContainer, page: PageEntity, format: ExportFormat): File = withContext(Dispatchers.IO) {
        val json = read(app, page)
        val key = keyFor(app, page.sectionId)
        val dir = app.storage.dir(Profile.from(page.profile), Folder.EXPORTS)
        val title = page.title.ifBlank { "Untitled page" }
        if (format == ExportFormat.HTML) {
            Files.unique(dir, title, "html").also { it.writeText(staticHtml(app, page, json)) }
        } else {
            val body = PageHtml.linear(json, { ref -> assetBytes(app, page, ref, key)?.let { dataUri(it, ref) } ?: "" }, inkPng(PageText.model(json)))
            DocExporter.export(body, title, PageSetup(), format, dir)
        }.also { app.storage.scan(it) }
    }

    /** Whole notebook as a portable .blnotebook package (zip). Locked sections stay encrypted inside. */
    suspend fun exportNotebook(app: AppContainer, nb: NotebookEntity): File = withContext(Dispatchers.IO) {
        val dao = app.db.brainstorm()
        val out = Files.unique(app.storage.dir(Profile.from(nb.profile), Folder.EXPORTS), nb.name, "blnotebook")
        val manifest = JSONObject().put("format", "blacklab-notebook").put("version", 1).put("exportedAt", Time.now())
            .put("notebook", JSONObject().put("name", nb.name).put("color", nb.color).put("emoji", nb.emoji))
        val sections = JSONArray()
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            dao.sections(nb.id).forEach { s ->
                val pages = JSONArray()
                dao.pages(s.id).forEach { p ->
                    pages.put(JSONObject().put("id", p.id).put("title", p.title).put("level", p.level).put("createdAt", p.createdAt).put("updatedAt", p.updatedAt))
                    val dir = pageDir(app, p)
                    dir.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.forEach { f ->
                        zip.putNextEntry(ZipEntry("pages/${p.id}/" + f.relativeTo(dir).path)); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                    }
                }
                sections.put(JSONObject().put("name", s.name).put("color", s.color).put("group", s.groupName).put("locked", s.locked)
                    .put("salt", s.lockSalt).put("check", s.lockCheck).put("hint", s.lockHint).put("pages", pages))
            }
            manifest.put("sections", sections)
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toString(1).toByteArray()); zip.closeEntry()
        }
        app.storage.scan(out)
        out
    }

    suspend fun importNotebook(app: AppContainer, profile: Profile, uri: Uri): Long = withContext(Dispatchers.IO) {
        val tmp = File(app.context.cacheDir, "import_${System.nanoTime()}").also { it.mkdirs() }
        try {
            ZipInputStream(app.context.contentResolver.openInputStream(uri)!!.buffered()).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    val target = File(tmp, e.name).canonicalFile
                    if (!e.isDirectory && target.path.startsWith(tmp.canonicalPath + File.separator)) { target.parentFile?.mkdirs(); target.outputStream().use { zip.copyTo(it) } }
                    zip.closeEntry(); e = zip.nextEntry
                }
            }
            val m = JSONObject(File(tmp, "manifest.json").readText())
            require(m.optString("format") == "blacklab-notebook") { "Not a BlackLab notebook" }
            val dao = app.db.brainstorm()
            val n = m.getJSONObject("notebook")
            val nbId = dao.insertNotebook(NotebookEntity(profile = profile.name, name = n.optString("name", "Imported notebook"), color = n.optInt("color", SectionColors[0]), emoji = n.optString("emoji"),
                sortOrder = (dao.notebooks(profile.name).maxOfOrNull { it.sortOrder } ?: -1) + 1))
            val secs = m.optJSONArray("sections") ?: JSONArray()
            for (i in 0 until secs.length()) {
                val s = secs.getJSONObject(i)
                val sid = dao.insertSection(SectionEntity(notebookId = nbId, name = s.optString("name", "Section"), color = s.optInt("color", SectionColors[1]), groupName = s.optString("group"),
                    sortOrder = i, locked = s.optBoolean("locked"), lockSalt = s.optString("salt"), lockCheck = s.optString("check"), lockHint = s.optString("hint")))
                val pages = s.optJSONArray("pages") ?: JSONArray()
                for (k in 0 until pages.length()) {
                    val p = pages.getJSONObject(k)
                    val src = File(tmp, "pages/${p.optLong("id")}")
                    val id = dao.insertPage(PageEntity(profile = profile.name, sectionId = sid, title = p.optString("title"), level = p.optInt("level"), sortOrder = k,
                        createdAt = p.optLong("createdAt", Time.now()), updatedAt = p.optLong("updatedAt", Time.now())))
                    val np = dao.page(id)!!
                    if (src.isDirectory) src.copyRecursively(pageDir(app, np), overwrite = true)
                    val f = pageFile(app, np)
                    if (f.exists() && !s.optBoolean("locked")) {
                        val ex = PageText.extract(f.readText())
                        dao.updatePage(np.copy(title = ex.title, preview = ex.preview, searchText = ex.text.take(60_000), tagIndex = ex.tagIndex, wordCount = ex.words))
                    }
                }
            }
            nbId
        } finally { tmp.deleteRecursively() }
    }

    // ---------------------------------------------------------------- smart features
    suspend fun summarize(app: AppContainer, text: String): Pair<String, String> {
        if (text.isBlank()) return "There's nothing on this page to summarize yet." to "BlackLab"
        if (app.settings.current.asstUseNano && Nano.ready) {
            Nano.generate("Summarize these notes in 3 to 5 short bullet points. Use '- ' bullets, no intro.\n\nNotes:\n${text.take(3500)}", 0.2f, 300)
                ?.takeIf { it.isNotBlank() }?.let { return it to "Gemini Nano · on-device" }
        }
        val sentences = text.split(Regex("(?<=[.!?])\\s+|\\n+")).map { it.trim() }.filter { it.split(' ').size >= 4 }
        val picks = sentences.take(1) + sentences.drop(1).sortedByDescending { it.length }.take(3)
        return (if (picks.isEmpty()) text.take(300) else picks.joinToString("\n") { "- " + it.take(220) }) to "On-device extract"
    }

    suspend fun ideas(app: AppContainer, topic: String, context: String = ""): Pair<List<String>, String> {
        if (app.settings.current.asstUseNano && Nano.ready) {
            val out = Nano.generate("Brainstorm 6 distinct, practical ideas about: \"$topic\". ${if (context.isNotBlank()) "Context: ${context.take(800)}. " else ""}Reply with one idea per line, each under 8 words, no numbering.", 0.8f, 200)
            val list = out?.lines()?.map { it.trim().trimStart('-', '*', '•', ' ', '1', '2', '3', '4', '5', '6', '7', '8', '9', '0', '.', ')').trim() }?.filter { it.length in 3..80 }?.distinct()?.take(8)
            if (!list.isNullOrEmpty()) return list to "Gemini Nano · on-device"
        }
        return PageTemplates.prompts(topic) to "Idea starters (5W1H + SCAMPER)"
    }

    /** Action items from page text → To-Do tasks (Gemini Nano when available, cue phrases otherwise). */
    suspend fun actionItems(app: AppContainer, text: String): List<String> {
        if (app.settings.current.asstUseNano && Nano.ready) {
            val out = Nano.generate("List the action items (tasks someone must do) in these notes, one per line starting with '- '. If none, reply NONE.\n\n${text.take(3500)}", 0.2f, 300)
            val list = out?.lines()?.filter { it.trim().startsWith("-") }?.map { it.trim().trimStart('-', ' ').trim() }?.filter { it.length > 3 }
            if (!list.isNullOrEmpty()) return list
        }
        return MeetingActions.heuristic(text).second
    }

    suspend fun createTasks(app: AppContainer, profile: Profile, titles: List<String>, source: String): Int {
        titles.distinct().take(25).forEach { t ->
            val p = QuickParse.parse(t)
            TaskOps.save(app, TaskEntity(profile = profile.name, title = p.title.ifBlank { t }.take(140).replaceFirstChar { it.uppercase() },
                notes = "From Brainstorm: $source", dueDay = p.dueDay?.toEpochDay(), dueMinute = p.dueMinute,
                reminderAt = if (p.dueDay != null && p.dueMinute != null) Time.millis(p.dueDay, p.dueMinute) else null, tags = "brainstorm"))
        }
        return titles.distinct().take(25).size
    }

    /** Ticks or unticks the n-th matching To Do paragraph on a page (Tags view). */
    suspend fun setTodo(app: AppContainer, page: PageEntity, text: String, done: Boolean) {
        val json = read(app, page)
        val m = PageText.model(json)
        val items = m.optJSONArray("items") ?: return
        var changed = false
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            if (it.optString("t") != "text") continue
            val doc = org.jsoup.Jsoup.parseBodyFragment(it.optString("html"))
            doc.select("[data-tag=todo]").firstOrNull { el -> el.text().replace('|', '/').replace('\n', ' ').trim() == text }?.let { el ->
                el.attr("data-done", if (done) "1" else "0")
                it.put("html", doc.body().html()); changed = true
            }
            if (changed) break
        }
        if (changed) save(app, page.id, m.toString())
    }

    fun shareFile(context: Context, f: File, subject: String) = Files.share(context, listOf(f), subject)
}

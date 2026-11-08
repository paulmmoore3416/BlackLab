package com.blacklab.app.core

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Base64
import androidx.sqlite.db.SimpleSQLiteQuery
import com.blacklab.app.AppContainer
import com.blacklab.app.BuildConfig
import com.blacklab.app.data.BlackLabDatabase
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Encrypted backups of everything that isn't already a file in the BlackLab folder: the database, settings,
 * assistant history, Garage (vehicles, service, fuel, trips), saved knowledge conversations, playlists and the
 * custom wake word. Signatures, the Behavioral Health workspace and crash logs are never included.
 *
 * Backups are written to BlackLab/Backups as `.blbackup` files (see [BackupCrypto]); older plain `.zip` backups
 * still restore. A restore is checked completely — decrypted, unpacked, database opened, integrity checked and
 * schema version compared — in a scratch folder, and the current data is backed up, before anything is replaced.
 */
class BackupManager(private val context: Context, private val app: AppContainer) {

    companion object {
        const val EXT = "blbackup"
        private const val PREFIX = "BlackLab_backup_"
        private const val SAFETY_PREFIX = "BlackLab_before-restore_"
        private const val DATA_KEY = "backup_data_key"
        private const val PASS_WRAP = "backup_pass_wrap"
        private const val PREFS_NAME = "blacklab_settings.xml"
        /** App-private files and folders (relative to filesDir) that go into every backup. */
        val FILES = listOf("assistant", "garage", "knowledge", "facility", "playlists.json", "wake")
        /** Tables counted for the restore preview. */
        val COUNTED = listOf("tasks" to "tasks", "events" to "events", "documents" to "documents", "journal" to "journal entries",
            "notes" to "notes", "nb_pages" to "notebook pages", "courses" to "courses", "certifications" to "certifications", "recordings" to "recordings")
        private const val MAX_ENTRY = 2L shl 30
    }

    /** What a backup holds, read without changing anything. */
    data class Inspection(
        val source: String,
        val encrypted: Boolean,
        val fromThisInstall: Boolean,
        val created: Long,
        val appVersion: String,
        val schema: Int,
        val counts: List<Pair<String, Int>>,
        val files: List<String>,
        internal val stage: File,
    ) {
        val summary: String get() = counts.filter { it.second > 0 }.joinToString(" · ") { "${it.second} ${it.first}" }.ifBlank { "No records" }
    }

    private val scratch get() = File(context.cacheDir, "restore")

    // ---- keys -------------------------------------------------------------------------------------------

    @Synchronized
    private fun dataKey(): ByteArray {
        app.secrets.get(DATA_KEY)?.let { return Base64.decode(it, Base64.NO_WRAP) }
        val k = BackupCrypto.newKey()
        app.secrets.put(DATA_KEY, Base64.encodeToString(k, Base64.NO_WRAP))
        return k
    }

    val hasPassphrase: Boolean get() = app.secrets.has(PASS_WRAP)

    /** Sets (or changes) the passphrase that lets backups made from now on be restored on any phone. */
    fun setPassphrase(passphrase: CharArray) {
        require(passphrase.size >= 8) { "Use at least 8 characters" }
        val wrap = BackupCrypto.wrapKey(dataKey(), passphrase)
        passphrase.fill(' ')
        app.secrets.put(PASS_WRAP, wrap.toString())
    }

    private fun passWrap(): JSONObject? = app.secrets.get(PASS_WRAP)?.let { runCatching { JSONObject(it) }.getOrNull() }

    // ---- backup -----------------------------------------------------------------------------------------

    suspend fun backupNow(): File = write(PREFIX).also {
        app.settings.update { putLong(Keys.LAST_BACKUP_DAY, Time.today().toEpochDay()) }
        prune(PREFIX, 14)
    }

    private fun write(prefix: String): File {
        val dir = app.storage.backupsDir
        val out = File(dir, "$prefix${BlackLabStorage.timestamp()}.$EXT")
        val tmp = File(dir, ".${out.name}.part")
        val snap = File(context.cacheDir, "backup-db-${System.nanoTime()}.db")
        try {
            val dbFiles = snapshotDatabase(snap)
            tmp.outputStream().buffered().use { os ->
                BackupCrypto.encrypt(os, dataKey(), passWrap(), BuildConfig.VERSION_NAME, schemaVersion()) { sink ->
                    ZipOutputStream(sink.buffered()).use { zip ->
                        dbFiles.forEach { (name, f) -> zip.add("db/$name", f) }
                        File(context.applicationInfo.dataDir, "shared_prefs/$PREFS_NAME").takeIf { it.isFile }?.let { zip.add("prefs/$PREFS_NAME", it) }
                        FILES.map { File(context.filesDir, it) }.filter { it.exists() }.forEach { root ->
                            root.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.forEach { f ->
                                zip.add("files/" + f.relativeTo(context.filesDir).invariantSeparatorsPath, f)
                            }
                        }
                        zip.putNextEntry(ZipEntry("manifest.json"))
                        zip.write(JSONObject().put("app", BuildConfig.VERSION_NAME).put("schema", schemaVersion()).put("created", System.currentTimeMillis())
                            .toString().toByteArray())
                        zip.closeEntry()
                    }
                }
            }
            if (!tmp.renameTo(out)) throw IOException("Couldn't save the backup")
            return out
        } finally {
            tmp.delete()
            snap.parentFile?.listFiles { f -> f.name.startsWith(snap.name) }?.forEach { it.delete() }
        }
    }

    /** A consistent single-file copy of the live database (VACUUM INTO), or the raw files if that isn't possible. */
    private fun snapshotDatabase(snap: File): List<Pair<String, File>> {
        val ok = runCatching {
            snap.delete()
            app.db.openHelper.writableDatabase.execSQL("VACUUM INTO '${snap.absolutePath.replace("'", "''")}'")
            snap.isFile && snap.length() > 0
        }.getOrDefault(false)
        if (ok) return listOf(BlackLabDatabase.NAME to snap)
        runCatching { app.db.query(SimpleSQLiteQuery("PRAGMA wal_checkpoint(TRUNCATE)")).use { it.moveToFirst() } }
        val db = context.getDatabasePath(BlackLabDatabase.NAME)
        return listOf(db, File(db.path + "-wal")).filter { it.exists() }.map { it.name to it }
    }

    private fun schemaVersion(): Int = runCatching { app.db.openHelper.readableDatabase.version }.getOrDefault(0)

    private fun ZipOutputStream.add(name: String, f: File) {
        putNextEntry(ZipEntry(name).apply { time = f.lastModified() })
        f.inputStream().use { it.copyTo(this) }
        closeEntry()
    }

    private fun prune(prefix: String, keep: Int) {
        app.storage.backupsDir.listFiles { f -> f.name.startsWith(prefix) && (f.extension == EXT || f.extension == "zip") }
            ?.sortedByDescending { it.lastModified() }?.drop(keep)?.forEach { it.delete() }
    }

    suspend fun autoBackupIfDue() {
        val s = app.settings.current
        if (!s.onboarded || !s.autoBackup) return
        if (s.lastBackupDay >= Time.today().toEpochDay()) return
        runCatching { backupNow() }
    }

    fun listBackups(): List<File> =
        app.storage.backupsDir.listFiles { f -> !f.name.startsWith(".") && (f.extension == EXT || f.extension == "zip") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** Old backups written before 5.10 — readable by anything with storage access. */
    fun unencryptedBackups(): List<File> = listBackups().filter { it.extension == "zip" }

    // ---- inspect & restore ------------------------------------------------------------------------------

    fun inspect(file: File, passphrase: CharArray? = null): Inspection = inspect(file.name, passphrase) { file.inputStream() }

    fun inspect(uri: Uri, passphrase: CharArray? = null): Inspection =
        inspect(uri.lastPathSegment ?: "backup", passphrase) { context.contentResolver.openInputStream(uri) ?: throw IOException("Can't open that file") }

    /**
     * Decrypts and unpacks a backup into a scratch folder and opens its database to check it.
     * Throws [BackupCrypto.NeedsPassphrase] / [BackupCrypto.WrongPassphrase] when a passphrase is needed.
     */
    private fun inspect(source: String, passphrase: CharArray?, open: () -> InputStream): Inspection {
        scratch.deleteRecursively()
        val stage = File(scratch, "stage").apply { mkdirs() }
        val zip = File(scratch, "backup.zip")
        var encrypted = false
        var fromHere = true
        var created = 0L
        var appVersion = ""
        try {
            open().buffered().use { input ->
                input.mark(8)
                val head = ByteArray(4).also { input.read(it) }
                input.reset()
                if (BackupCrypto.isEncrypted(head)) {
                    encrypted = true
                    val h = BackupCrypto.readHeader(input)
                    created = h.created; appVersion = h.appVersion
                    val local = dataKey()
                    val key = when {
                        BackupCrypto.keyId(local) == h.keyId -> local
                        h.wrap == null -> throw BackupCrypto.NotRestorable()
                        passphrase == null -> throw BackupCrypto.NeedsPassphrase()
                        else -> BackupCrypto.unwrapKey(h.wrap, passphrase).also { fromHere = false }
                    }
                    zip.outputStream().buffered().use { BackupCrypto.decrypt(input, h, key, it) }
                } else {
                    zip.outputStream().use { input.copyTo(it) }
                }
            }
            val files = unpack(zip, stage)
            zip.delete()
            val db = File(stage, "db/${BlackLabDatabase.NAME}")
            if (!db.isFile) throw IOException("Not a BlackLab backup — it has no database")
            val (schema, counts) = checkDatabase(db)
            val current = schemaVersion()
            if (current in 1 until schema) throw IOException("This backup was made by a newer BlackLab ($appVersion). Update BlackLab first, then restore it.")
            if (created == 0L) created = db.lastModified()
            return Inspection(source, encrypted, fromHere, created, appVersion, schema, counts, files, stage)
        } catch (e: Exception) {
            scratch.deleteRecursively()
            throw e
        } finally {
            passphrase?.fill(' ')
        }
    }

    /** Extracts only the entries a backup can contain, refusing paths that escape the folder. */
    private fun unpack(zip: File, stage: File): List<String> {
        val root = stage.canonicalFile
        val out = ArrayList<String>()
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val name = e.name
                val allowed = !e.isDirectory && (
                    name == "db/${BlackLabDatabase.NAME}" || name == "db/${BlackLabDatabase.NAME}-wal" ||
                        name == "prefs/$PREFS_NAME" || name == "assistant/history.json" || // 5.9 and earlier
                        FILES.any { name == "files/$it" || name.startsWith("files/$it/") })
                if (allowed) {
                    val rel = if (name == "assistant/history.json") "files/assistant/history.json" else name
                    val target = File(root, rel).canonicalFile
                    if (!target.path.startsWith(root.path + File.separator)) throw IOException("Damaged backup (bad path $name)")
                    target.parentFile?.mkdirs()
                    var total = 0L
                    target.outputStream().use { os ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = z.read(buf); if (n < 0) break
                            total += n
                            if (total > MAX_ENTRY) throw IOException("Damaged backup (entry too large)")
                            os.write(buf, 0, n)
                        }
                    }
                    if (rel.startsWith("files/")) out += rel.removePrefix("files/")
                }
                z.closeEntry()
                e = z.nextEntry
            }
        }
        return out
    }

    private fun checkDatabase(db: File): Pair<Int, List<Pair<String, Int>>> {
        val sql = try {
            SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READWRITE)
        } catch (e: Exception) {
            throw IOException("The backup's database can't be opened (${e.message})")
        }
        try {
            sql.rawQuery("PRAGMA quick_check", null).use { c -> if (!c.moveToFirst() || c.getString(0) != "ok") throw IOException("The backup's database is damaged") }
            val tables = sql.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
            if ("room_master_table" !in tables || "tasks" !in tables) throw IOException("Not a BlackLab database")
            val counts = COUNTED.filter { it.first in tables }.map { (t, label) ->
                label to sql.rawQuery("SELECT COUNT(*) FROM `$t`", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            }
            return sql.version to counts
        } finally {
            sql.close() // checkpoints the WAL into the file
            File(db.path + "-wal").delete(); File(db.path + "-shm").delete(); File(db.path + "-journal").delete()
        }
    }

    /** Throws away an inspection that won't be restored. */
    fun discard() { scratch.deleteRecursively() }

    /**
     * Replaces the current data with an inspected backup. The current data is first saved as a
     * `BlackLab_before-restore_…` backup. The app must exit right afterwards.
     */
    fun restore(i: Inspection) {
        val stage = i.stage
        val db = File(stage, "db/${BlackLabDatabase.NAME}")
        if (!db.isFile) throw IOException("The restore was interrupted — choose the backup again")
        runCatching { write(SAFETY_PREFIX) }.onFailure { throw IOException("Couldn't save your current data first, so nothing was restored (${it.message})") }
        prune(SAFETY_PREFIX, 3)

        app.db.close()
        val dbFile = context.getDatabasePath(BlackLabDatabase.NAME)
        listOf("-wal", "-shm", "-journal").forEach { File(dbFile.path + it).delete() }
        replace(db, dbFile)
        File(stage, "prefs/$PREFS_NAME").takeIf { it.isFile }?.let { replace(it, File(context.applicationInfo.dataDir, "shared_prefs/$PREFS_NAME")) }
        val staged = File(stage, "files")
        FILES.forEach { name ->
            val src = File(staged, name)
            if (!src.exists()) return@forEach
            val dest = File(context.filesDir, name)
            if (src.isDirectory) {
                dest.deleteRecursively()
                if (!src.renameTo(dest)) { src.copyRecursively(dest, overwrite = true) }
            } else replace(src, dest)
        }
        scratch.deleteRecursively()
    }

    private fun replace(src: File, dest: File) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, ".${dest.name}.restore")
        src.copyTo(tmp, overwrite = true)
        if (!tmp.renameTo(dest)) { tmp.delete(); throw IOException("Couldn't write ${dest.name}") }
    }
}

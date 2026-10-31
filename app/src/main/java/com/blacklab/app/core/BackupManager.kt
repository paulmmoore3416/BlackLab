package com.blacklab.app.core

import android.content.Context
import android.net.Uri
import androidx.sqlite.db.SimpleSQLiteQuery
import com.blacklab.app.AppContainer
import com.blacklab.app.data.BlackLabDatabase
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Zips the database + settings into BlackLab/Backups. Media and documents already live as files. */
class BackupManager(private val context: Context, private val app: AppContainer) {

    suspend fun backupNow(): File {
        runCatching { app.db.query(SimpleSQLiteQuery("PRAGMA wal_checkpoint(TRUNCATE)")).use { it.moveToFirst() } }
        val out = File(app.storage.backupsDir, "BlackLab_backup_${BlackLabStorage.timestamp()}.zip")
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            val dbFile = context.getDatabasePath(BlackLabDatabase.NAME)
            listOf(dbFile, File(dbFile.path + "-wal"), File(dbFile.path + "-shm")).filter { it.exists() }.forEach { f ->
                zip.putNextEntry(ZipEntry("db/${f.name}"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            val prefs = File(context.applicationInfo.dataDir, "shared_prefs/blacklab_settings.xml")
            if (prefs.exists()) {
                zip.putNextEntry(ZipEntry("prefs/blacklab_settings.xml"))
                prefs.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            // Assistant conversation (API keys live in a separate encrypted store and are never backed up)
            val history = File(context.filesDir, "assistant/history.json")
            if (history.exists()) {
                zip.putNextEntry(ZipEntry("assistant/history.json"))
                history.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        app.settings.update { putLong(Keys.LAST_BACKUP_DAY, Time.today().toEpochDay()) }
        // keep the 14 most recent
        app.storage.backupsDir.listFiles { f -> f.name.startsWith("BlackLab_backup_") && f.extension == "zip" }
            ?.sortedByDescending { it.lastModified() }?.drop(14)?.forEach { it.delete() }
        return out
    }

    suspend fun autoBackupIfDue() {
        val s = app.settings.current
        if (!s.onboarded || !s.autoBackup) return
        if (s.lastBackupDay >= Time.today().toEpochDay()) return
        runCatching { backupNow() }
    }

    fun listBackups(): List<File> =
        app.storage.backupsDir.listFiles { f -> f.extension == "zip" }?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** Restores a backup zip. The app must restart afterwards. */
    fun restore(zipFile: File): Boolean = restore { zipFile.inputStream() }

    fun restore(uri: Uri): Boolean = restore { context.contentResolver.openInputStream(uri)!! }

    private fun restore(open: () -> java.io.InputStream): Boolean = runCatching {
        app.db.close()
        val dbDir = context.getDatabasePath(BlackLabDatabase.NAME).parentFile!!
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        var restoredDb = false
        ZipInputStream(open().buffered()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                val name = File(e.name).name
                val target = when {
                    e.name.startsWith("db/") -> File(dbDir, name).also { restoredDb = true }
                    e.name.startsWith("prefs/") -> File(prefsDir, name)
                    e.name == "assistant/history.json" -> File(context.filesDir, e.name).also { it.parentFile?.mkdirs() }
                    else -> null
                }
                if (target != null && (name.startsWith("blacklab") || name == "history.json")) {
                    if (e.name.startsWith("db/") && name == BlackLabDatabase.NAME) {
                        File(dbDir, "$name-wal").delete(); File(dbDir, "$name-shm").delete()
                    }
                    target.outputStream().use { zip.copyTo(it) }
                }
                zip.closeEntry()
                e = zip.nextEntry
            }
        }
        restoredDb
    }.getOrDefault(false)
}

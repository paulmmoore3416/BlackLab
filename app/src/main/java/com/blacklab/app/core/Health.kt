package com.blacklab.app.core

/**
 * Settings › Health: one page that says whether BlackLab is in good shape — backups, data files, the desktop
 * link, the Obsidian vault, crashes, storage and on-device AI — with a way to fix each problem.
 * The evaluation is pure (unit tested in HealthTest); the screen gathers the inputs.
 */
object Health {
    enum class Level { OK, INFO, WARN, BAD }

    data class Check(val id: String, val title: String, val level: Level, val detail: String, val fixLabel: String? = null, val fixRoute: String? = null)

    data class Inputs(
        val now: Long,
        val newestBackupAt: Long?,
        val backupCount: Int,
        val plainBackups: Int,
        val autoBackup: Boolean,
        val backupPassphraseSet: Boolean,
        val storeProblems: Map<String, String>,
        val storeReadOnly: List<String>,
        val dbVersion: Int,
        val desktopEnabled: Boolean,
        val desktopState: String,
        val desktopLatencyMs: Long,
        val vaultEnabled: Boolean,
        val vaultLastSyncAt: Long,
        val crashes7d: Int,
        val lastCrashTitle: String?,
        val freeBytes: Long,
        val nanoStatus: String,
        val indexPassages: Int,
    )

    private const val DAY = 86_400_000L

    fun ago(ms: Long, now: Long): String {
        val d = now - ms
        return when {
            d < 3_600_000L -> "${(d / 60_000L).coerceAtLeast(1)} min ago"
            d < DAY -> "${d / 3_600_000L} h ago"
            else -> "${d / DAY} days ago"
        }
    }

    fun evaluate(i: Inputs): List<Check> {
        val out = ArrayList<Check>()
        // Backups
        out += when {
            i.newestBackupAt == null -> Check("backup", "Backups", Level.BAD, "No backup yet. If this phone is lost, everything goes with it.", "Back up now", "settings")
            i.now - i.newestBackupAt > 7 * DAY -> Check("backup", "Backups", Level.WARN, "Last backup ${ago(i.newestBackupAt, i.now)}" +
                (if (!i.autoBackup) " — automatic backups are off." else "."), "Open backups", "settings")
            else -> Check("backup", "Backups", Level.OK, "Last backup ${ago(i.newestBackupAt, i.now)} · ${i.backupCount} kept" +
                (if (i.autoBackup) " · daily automatic" else " · automatic is off"))
        }
        if (i.plainBackups > 0) out += Check("backup_plain", "Unencrypted backups", Level.WARN,
            "${i.plainBackups} old .zip backup${if (i.plainBackups == 1) "" else "s"} from before 5.10 can be read by any app with storage access.", "Review", "settings")
        if (!i.backupPassphraseSet) out += Check("backup_pass", "Backup passphrase", Level.INFO,
            "Without a passphrase, backups restore only on this phone. Set one so a new phone can restore them.", "Set passphrase", "settings")
        // Data files
        if (i.storeProblems.isEmpty() && i.storeReadOnly.isEmpty()) out += Check("stores", "Data files", Level.OK, "Facility, Garage, drive log and recalls read cleanly · database v${i.dbVersion}")
        i.storeProblems.forEach { (name, p) -> out += Check("store_$name", name, Level.BAD, p) }
        i.storeReadOnly.forEach { name -> out += Check("store_ro_$name", name, Level.WARN, "Saved by a newer BlackLab, so changes aren't written. Update the app.", "Check for updates", "updates") }
        // Desktop & vault
        out += when {
            !i.desktopEnabled -> Check("desktop", "Desktop link", Level.INFO, "Off — the desktop AI, jobs and the Obsidian vault need it.", "Turn on", "assistant/settings")
            i.desktopState == "ONLINE" -> Check("desktop", "Desktop link", Level.OK, "Connected · ${i.desktopLatencyMs} ms")
            i.desktopState == "NOT_PAIRED" -> Check("desktop", "Desktop link", Level.WARN, "Not paired. Run ./daddyshome on the computer with the phone plugged in.")
            i.desktopState == "CHECKING" -> Check("desktop", "Desktop link", Level.INFO, "Checking…")
            else -> Check("desktop", "Desktop link", Level.WARN, "The computer isn't reachable. Plug in the phone or turn on Wireless debugging.")
        }
        out += when {
            !i.vaultEnabled -> Check("vault", "Obsidian vault", Level.INFO, "Mirroring is off.", "Settings", "settings")
            i.vaultLastSyncAt <= 0 -> Check("vault", "Obsidian vault", Level.INFO, "Not synced yet — it syncs while the desktop link is up.")
            i.now - i.vaultLastSyncAt > 2 * DAY -> Check("vault", "Obsidian vault", Level.WARN, "Last synced ${ago(i.vaultLastSyncAt, i.now)}.")
            else -> Check("vault", "Obsidian vault", Level.OK, "Synced ${ago(i.vaultLastSyncAt, i.now)}")
        }
        // Crashes
        out += if (i.crashes7d == 0) Check("crashes", "Stability", Level.OK, "No crashes in the last 7 days")
        else Check("crashes", "Stability", if (i.crashes7d >= 3) Level.BAD else Level.WARN,
            "${i.crashes7d} crash${if (i.crashes7d == 1) "" else "es"} in 7 days" + (i.lastCrashTitle?.let { " · last: $it" } ?: ""), "Crash log", "diagnostics")
        // Storage
        val gb = i.freeBytes / 1_000_000_000.0
        out += when {
            gb < 2 -> Check("storage", "Storage", Level.BAD, "Only %.1f GB free — recordings and photos can fail to save.".format(gb), "Library", "library")
            gb < 8 -> Check("storage", "Storage", Level.WARN, "%.1f GB free".format(gb))
            else -> Check("storage", "Storage", Level.OK, "%.0f GB free".format(gb))
        }
        // On-device AI & search
        out += Check("ai", "On-device AI", if (i.nanoStatus == "AVAILABLE") Level.OK else Level.INFO,
            (if (i.nanoStatus == "AVAILABLE") "Gemini Nano ready" else "Gemini Nano: ${i.nanoStatus.lowercase().replace('_', ' ')}") + " · Ask my files: ${i.indexPassages} passages")
        return out.sortedBy { -it.level.ordinal }
    }

    fun summary(checks: List<Check>): String {
        val bad = checks.count { it.level == Level.BAD }
        val warn = checks.count { it.level == Level.WARN }
        return when {
            bad > 0 -> "$bad problem${if (bad == 1) "" else "s"} need attention"
            warn > 0 -> "$warn thing${if (warn == 1) "" else "s"} to look at"
            else -> "All good"
        }
    }
}

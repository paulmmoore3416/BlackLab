package com.blacklab.app.core

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.blacklab.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A crash log that never leaves the phone unless you share it. Records uncaught exceptions as they
 * happen, plus ANRs and native crashes that Android reports on the next start. Kept in the app's
 * private storage (not in backups, not in the BlackLab folder); the newest [KEEP] are kept.
 */
object CrashLog {
    const val KEEP = 30
    private const val PREFS = "blacklab_crashlog"
    private const val KEY_EXIT_TS = "last_exit_ts"
    private const val KEY_SEEN = "seen_until"
    private const val KEY_ANNOUNCED = "announced_until"

    data class Entry(val file: File, val time: Long, val kind: String, val title: String) {
        fun text(): String = runCatching { file.readText() }.getOrDefault("")
    }

    /** Count of reports newer than the last time the diagnostics screen was opened. */
    val unseen = MutableStateFlow(0)

    fun dir(context: Context) = File(context.filesDir, "crashes").also { it.mkdirs() }

    // ----------------------------------------------------------------- pure (unit tested)

    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneId.systemDefault())
    private val human = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault())

    fun fileName(time: Long, kind: String) = "${stamp.format(Instant.ofEpochMilli(time))}_${kind.lowercase()}.txt"

    fun stackTrace(t: Throwable): String = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString()

    /** Report body: what happened and on which build/device. No user content is added here. */
    fun format(time: Long, kind: String, title: String, body: String, version: String, build: Int, device: String, android: String, thread: String? = null): String = buildString {
        append("BlackLab problem report\n")
        append("Type:    ").append(kind).append('\n')
        append("When:    ").append(human.format(Instant.ofEpochMilli(time))).append('\n')
        append("Version: ").append(version).append(" (build ").append(build).append(")\n")
        append("Device:  ").append(device).append(" · Android ").append(android).append('\n')
        if (thread != null) append("Thread:  ").append(thread).append('\n')
        append("Summary: ").append(title).append("\n\n")
        append(body.trimEnd()).append('\n')
    }

    /** Keeps the newest [keep] report files. */
    fun prune(dir: File, keep: Int = KEEP) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }?.sortedByDescending { it.name } ?: return
        files.drop(keep).forEach { it.delete() }
    }

    fun parse(f: File): Entry? = runCatching {
        val head = f.bufferedReader().use { r -> List(8) { r.readLine() ?: "" } }
        val kind = head.firstOrNull { it.startsWith("Type:") }?.substringAfter(':')?.trim().orEmpty()
        val title = head.firstOrNull { it.startsWith("Summary:") }?.substringAfter(':')?.trim().orEmpty()
        Entry(f, f.lastModified(), kind.ifBlank { "Crash" }, title)
    }.getOrNull()

    // ----------------------------------------------------------------- device

    private fun device() = "${Build.MANUFACTURER} ${Build.MODEL}"
    private fun android() = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    fun write(context: Context, time: Long, kind: String, title: String, body: String, thread: String? = null): File {
        val d = dir(context)
        val f = File(d, fileName(time, kind))
        f.writeText(format(time, kind, title, body.take(256 * 1024), BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, device(), android(), thread))
        f.setLastModified(time)
        prune(d)
        return f
    }

    /** Installs the uncaught-exception hook. The previous handler still runs, so Android shows its usual dialog. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val root = generateSequence(error) { it.cause }.last()
                write(app, System.currentTimeMillis(), "Crash", "${root.javaClass.simpleName}: ${root.message.orEmpty()}".take(300), stackTrace(error), thread.name)
            }
            previous?.uncaughtException(thread, error)
        }
        runCatching { importExitReasons(app) }
        refreshUnseen(app)
    }

    /** Copies ANRs and native crashes Android recorded for previous runs into the log. */
    fun importExitReasons(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val since = prefs.getLong(KEY_EXIT_TS, 0L)
        val am = context.getSystemService(ActivityManager::class.java)
        val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 16).filter { it.timestamp > since }
        if (since == 0L) { // first run with the log: don't import history from before it existed
            prefs.edit().putLong(KEY_EXIT_TS, exits.maxOfOrNull { it.timestamp } ?: System.currentTimeMillis()).apply()
            return
        }
        exits.forEach { e ->
            val kind = when (e.reason) {
                ApplicationExitInfo.REASON_ANR -> "ANR"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Resource limit"
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Start-up failure"
                else -> null // Java crashes are recorded live; normal exits are ignored
            } ?: return@forEach
            val trace = if (e.reason == ApplicationExitInfo.REASON_ANR) runCatching {
                e.traceInputStream?.bufferedReader()?.use { r -> r.readText().take(96 * 1024) }
            }.getOrNull().orEmpty() else ""
            val body = buildString {
                append("Process: ").append(e.processName).append(" · importance ").append(e.importance).append('\n')
                append("Description: ").append(e.description ?: "—").append('\n')
                if (trace.isNotBlank()) append("\n").append(trace)
            }
            write(context, e.timestamp, kind, (e.description ?: kind).take(300), body)
        }
        prefs.edit().putLong(KEY_EXIT_TS, maxOf(since, exits.maxOfOrNull { it.timestamp } ?: since)).apply()
    }

    fun entries(context: Context): List<Entry> =
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(".txt") }.orEmpty().sortedByDescending { it.name }.mapNotNull(::parse)

    fun refreshUnseen(context: Context) {
        val seen = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_SEEN, 0L)
        unseen.value = entries(context).count { it.time > seen }
    }

    /** Reports not yet mentioned to the user; each report is announced once. */
    fun takeAnnouncement(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val since = maxOf(prefs.getLong(KEY_ANNOUNCED, 0L), prefs.getLong(KEY_SEEN, 0L))
        val n = entries(context).count { it.time > since }
        prefs.edit().putLong(KEY_ANNOUNCED, System.currentTimeMillis()).apply()
        return n
    }

    fun markSeen(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_SEEN, System.currentTimeMillis()).apply()
        unseen.value = 0
    }

    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
        markSeen(context)
    }

    /** All reports in one text file in the cache, ready for the share sheet. */
    fun exportAll(context: Context): File {
        val out = File(context.cacheDir, "BlackLab-problem-reports.txt")
        out.writeText(entries(context).joinToString("\n" + "=".repeat(60) + "\n\n") { it.text() })
        return out
    }
}

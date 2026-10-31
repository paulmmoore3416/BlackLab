package com.blacklab.app.link

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.blacklab.app.AppContainer
import com.blacklab.app.app
import com.blacklab.app.assistant.Drive
import com.blacklab.app.assistant.GoogleAuth
import com.blacklab.app.assistant.Origin
import com.blacklab.app.assistant.isOnline
import com.blacklab.app.core.SettingsSchema
import com.blacklab.app.core.Time
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Cloud sync through the hidden appDataFolder of the user's own Google Drive, so the BlackLab
 * web UI (GitHub Pages) works from anywhere after Google sign-in. Only this app's OAuth project
 * can read that folder.
 *
 *  - `blacklab-sync.json`  phone → web: settings schema + values, data snapshot, command results, acks
 *  - `blacklab-inbox.json` web → phone: settings changes and assistant commands
 *
 * The phone never rewrites the inbox; it acknowledges what it applied in the sync file and the web
 * UI prunes acknowledged entries, so the two sides never race on the same file.
 */
object CloudSync {
    const val SYNC_FILE = "blacklab-sync.json"
    const val INBOX_FILE = "blacklab-inbox.json"

    val lastSync = MutableStateFlow(0L)
    val lastError = MutableStateFlow<String?>(null)
    private val lock = Mutex()

    fun schedule(context: Context) {
        val s = context.app.settings.current
        val wm = WorkManager.getInstance(context)
        if (!s.cloudSync) { wm.cancelUniqueWork("cloud-sync"); return }
        val req = PeriodicWorkRequestBuilder<SyncWorker>(s.cloudSyncMinutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork("cloud-sync", ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    suspend fun sync(app: AppContainer): Boolean = lock.withLock {
        withContext(Dispatchers.IO) {
            val s = app.settings.current
            if (!s.cloudSync || s.asstOfflineOnly || !app.context.isOnline()) return@withContext false
            val token = GoogleAuth.token(app.context) ?: run { lastError.value = "Not signed in to Google"; return@withContext false }
            runCatching {
                val prev = Drive.appDataRead(token, SYNC_FILE)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
                val acks = prev.optJSONObject("acks") ?: JSONObject()
                var settingsTs = acks.optLong("settingsTs", 0)
                val done = acks.optJSONArray("commands")?.let { a -> (0 until a.length()).map { a.getString(it) } }?.toMutableList() ?: mutableListOf()
                val results = prev.optJSONArray("results") ?: JSONArray()

                // 1. apply what the web UI queued
                Drive.appDataRead(token, INBOX_FILE)?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { inbox ->
                    val ts = inbox.optLong("settingsTs", 0)
                    val changes = inbox.optJSONObject("settings")
                    if (changes != null && changes.length() > 0 && ts > settingsTs) {
                        SettingsSchema.apply(app.settings, changes)
                        app.requestReminderSync()
                        settingsTs = ts
                    }
                    val cmds = inbox.optJSONArray("commands") ?: JSONArray()
                    for (i in 0 until cmds.length()) {
                        val c = cmds.getJSONObject(i)
                        val id = c.optString("id")
                        if (id.isBlank() || id in done) continue
                        val reply = app.assistant.handle(c.optString("text"), Origin.CLOUD)
                        results.put(JSONObject().put("id", id).put("text", c.optString("text")).put("reply", reply.text).put("source", reply.source).put("time", Time.now()))
                        done += id
                    }
                }
                val trimmed = JSONArray().apply { (maxOf(0, results.length() - 40) until results.length()).forEach { put(results.get(it)) } }

                // 2. publish the current picture
                val doc = JSONObject()
                    .put("format", 1)
                    .put("updatedAt", Time.now())
                    .put("schema", SettingsSchema.toJson())
                    .put("settings", SettingsSchema.values(app.settings.prefs))
                    .put("snapshot", Snapshot.build(app))
                    .put("results", trimmed)
                    .put("acks", JSONObject().put("settingsTs", settingsTs).put("commands", JSONArray(done.takeLast(200))))
                if (!Drive.appDataWrite(token, SYNC_FILE, doc.toString())) error("Drive write failed")
                lastSync.value = Time.now()
                lastError.value = null
                true
            }.getOrElse { lastError.value = it.message; false }
        }
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = if (CloudSync.sync(applicationContext.app)) Result.success() else Result.retry()
}

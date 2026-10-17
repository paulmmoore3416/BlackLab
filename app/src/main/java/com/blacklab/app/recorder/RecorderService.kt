package com.blacklab.app.recorder

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.blacklab.app.R
import com.blacklab.app.app
import com.blacklab.app.core.BlackLabStorage
import com.blacklab.app.core.Folder
import com.blacklab.app.core.Time
import com.blacklab.app.data.RecordingEntity
import com.blacklab.app.reminders.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class RecState(
    val status: Status = Status.IDLE,
    val elapsedMs: Long = 0,
    val level: Float = 0f,
    val db: Float = -90f,
    val peakDb: Float = -90f,
    val levels: List<Float> = emptyList(),
    val markers: List<Pair<Long, String>> = emptyList(),
    val partial: String = "",
    val transcript: String = "",
    val transcriptError: String? = null,
    val bytes: Long = 0,
    val summary: String = "",
    val lastSavedId: Long? = null,
    val error: String? = null,
) {
    enum class Status { IDLE, RECORDING, PAUSED }
}

/** Foreground service so recording continues with the screen off or in other apps. */
class RecorderService : Service() {

    companion object {
        const val ACTION_START = "com.blacklab.app.rec.START"
        const val ACTION_PAUSE = "com.blacklab.app.rec.PAUSE"
        const val ACTION_RESUME = "com.blacklab.app.rec.RESUME"
        const val ACTION_STOP = "com.blacklab.app.rec.STOP"
        const val ACTION_CANCEL = "com.blacklab.app.rec.CANCEL"
        const val ACTION_MARK = "com.blacklab.app.rec.MARK"
        private const val NOTIF_ID = 6060

        val state = MutableStateFlow(RecState())
        @Volatile var pendingConfig: RecConfig = RecConfig()
        /** Input device chosen for this app session (null = automatic routing). */
        val preferredDevice = MutableStateFlow<Int?>(null)

        fun send(context: Context, action: String) {
            val i = Intent(context, RecorderService::class.java).setAction(action)
            if (action == ACTION_START) ContextCompat.startForegroundService(context, i) else context.startService(i)
        }

        fun mark(context: Context, label: String? = null) {
            pendingMarkLabel = label
            send(context, ACTION_MARK)
        }

        @Volatile private var pendingMarkLabel: String? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: AudioEngine? = null
    private var file: File? = null
    private var ticker: Job? = null
    private var profile = "PERSONAL"
    private val transcript = StringBuilder()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_PAUSE -> { engine?.paused = true; state.update { it.copy(status = RecState.Status.PAUSED) }; updateNotification() }
            ACTION_RESUME -> { engine?.paused = false; state.update { it.copy(status = RecState.Status.RECORDING) }; updateNotification() }
            ACTION_MARK -> engine?.let { e ->
                val label = pendingMarkLabel ?: "Marker ${state.value.markers.size + 1}"
                pendingMarkLabel = null
                state.update { it.copy(markers = it.markers + (e.elapsedMs to label)) }
            }
            ACTION_STOP -> finish(save = true)
            ACTION_CANCEL -> finish(save = false)
        }
        return START_NOT_STICKY
    }

    private fun start() {
        if (engine != null) return
        val app = applicationContext.app
        val cfg = pendingConfig
        profile = app.settings.current.activeProfile.name
        val dir = app.storage.dir(app.settings.current.activeProfile, Folder.RECORDINGS)
        val f = File(dir, "REC_${BlackLabStorage.timestamp()}.${cfg.ext}")
        file = f
        transcript.clear()
        state.value = RecState(status = RecState.Status.RECORDING, summary = cfg.summary())

        ServiceCompat.startForeground(this, NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

        val transcriber = if (cfg.liveTranscript) LiveTranscriber(this, object : LiveTranscriber.Listener {
            override fun onPartial(text: String) { state.update { it.copy(partial = text) } }
            override fun onFinal(text: String) {
                val ts = Time.duration(engine?.elapsedMs ?: 0)
                transcript.append("[").append(ts).append("] ").append(text.trim()).append('\n')
                state.update { it.copy(transcript = transcript.toString(), partial = "") }
            }
            override fun onError(message: String) { state.update { it.copy(transcriptError = message) } }
        }) else null

        val levels = ArrayDeque<Float>()
        val e = AudioEngine(this, cfg, f, onLevel = { lvl, db ->
            synchronized(levels) { levels.addLast(lvl); while (levels.size > 160) levels.removeFirst() }
            state.update { it.copy(level = lvl, db = db, peakDb = maxOf(it.peakDb, db)) }
        }, transcriber = transcriber)
        val err = e.start()
        if (err != null) {
            state.value = RecState(error = err)
            stopSelf()
            return
        }
        engine = e
        ticker = scope.launch {
            var n = 0
            while (isActive) {
                state.update { it.copy(elapsedMs = e.elapsedMs, bytes = f.length(), levels = synchronized(levels) { levels.toList() }) }
                if (++n % 10 == 0) updateNotification()
                delay(100)
            }
        }
    }

    private fun finish(save: Boolean) {
        val e = engine ?: run { stopSelf(); return }
        engine = null
        ticker?.cancel()
        val f = file
        val snapshot = state.value
        scope.launch {
            e.stop()
            var savedId: Long? = null
            if (f != null) {
                if (!save || e.framesWritten == 0L) f.delete()
                else {
                    val app = applicationContext.app
                    app.storage.scan(f)
                    val title = "Recording " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US))
                    if (transcript.isNotBlank()) File(f.parentFile, f.nameWithoutExtension + ".txt").writeText(transcript.toString())
                    savedId = app.db.recordings().upsert(
                        RecordingEntity(
                            profile = profile, filePath = app.storage.relative(f), title = title, durationMs = e.elapsedMs,
                            markers = snapshot.markers.joinToString("\n") { "${it.first}|${it.second}" },
                            transcript = transcript.toString(), format = e.config.summary(),
                        )
                    )
                }
            }
            state.value = RecState(lastSavedId = savedId)
            ServiceCompat.stopForeground(this@RecorderService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun notification(): Notification {
        val s = state.value
        fun action(act: String, req: Int) = PendingIntent.getService(this, req, Intent(this, RecorderService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val paused = s.status == RecState.Status.PAUSED
        return NotificationCompat.Builder(this, Notifications.CH_RECORDING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (paused) "Recording paused" else "Recording · ${Time.duration(s.elapsedMs)}")
            .setContentText(s.summary.ifBlank { "BlackLab recorder" } + if (s.markers.isNotEmpty()) " · ${s.markers.size} markers" else "")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setColor(0xFFE53935.toInt())
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(Notifications.openAppIntent(this, "recorder", 6061))
            .addAction(0, if (paused) "Resume" else "Pause", action(if (paused) ACTION_RESUME else ACTION_PAUSE, 1))
            .addAction(0, "Marker", action(ACTION_MARK, 2))
            .addAction(0, "Stop & save", action(ACTION_STOP, 3))
            .build()
    }

    private fun updateNotification() {
        if (engine == null) return
        runCatching { getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_ID, notification()) }
    }

    override fun onDestroy() {
        engine?.stop(); engine = null
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }
}

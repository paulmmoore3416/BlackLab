package com.blacklab.app.dictation

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.blacklab.app.R
import com.blacklab.app.app
import com.blacklab.app.core.Folder
import com.blacklab.app.core.Geo
import com.blacklab.app.core.GeoTag
import com.blacklab.app.core.Time
import com.blacklab.app.recorder.AudioEngine
import com.blacklab.app.recorder.LiveTranscriber
import com.blacklab.app.recorder.RecConfig
import com.blacklab.app.recorder.SttOptions
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

data class DictState(
    val status: Status = Status.IDLE,
    val elapsedMs: Long = 0,
    val level: Float = 0f,
    val levels: List<Float> = emptyList(),
    val segments: List<Segment> = emptyList(),
    val partial: String = "",
    val markers: List<Pair<Long, String>> = emptyList(),
    val geo: GeoTag? = null,
    val lastCommand: String? = null,
    val commandAt: Long = 0,
    val sttError: String? = null,
    val error: String? = null,
    val savedFolder: String? = null,
) {
    enum class Status { IDLE, STANDBY, RECORDING, PAUSED }
    val active: Boolean get() = status != Status.IDLE
}

/**
 * Hands-free dictation for meetings, conferences and interviews. Records audio and transcribes it live;
 * voice commands ("pause recording", "resume recording", "stop recording", "new paragraph"…) control it
 * while you speak. Each session is saved to its own dated folder with audio, transcript and location.
 */
class DictationService : Service() {
    companion object {
        const val ACTION_START = "com.blacklab.app.dict.START"
        const val ACTION_STANDBY = "com.blacklab.app.dict.STANDBY"
        const val ACTION_PAUSE = "com.blacklab.app.dict.PAUSE"
        const val ACTION_RESUME = "com.blacklab.app.dict.RESUME"
        const val ACTION_STOP = "com.blacklab.app.dict.STOP"
        const val ACTION_CANCEL = "com.blacklab.app.dict.CANCEL"
        const val ACTION_MARK = "com.blacklab.app.dict.MARK"
        private const val NOTIF_ID = 6070

        val state = MutableStateFlow(DictState())

        fun send(context: Context, action: String) {
            val i = Intent(context, DictationService::class.java).setAction(action)
            if (action == ACTION_START || action == ACTION_STANDBY) ContextCompat.startForegroundService(context, i) else context.startService(i)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: AudioEngine? = null
    private var transcriber: LiveTranscriber? = null
    private var folder: File? = null
    private var audio: File? = null
    private var ticker: Job? = null
    private var startedAt = 0L
    private var profileName = ""
    private val segments = mutableListOf<Segment>()
    private var segStartMs: Long? = null
    private var lastFinalMs = 0L
    private var nextIsPara = true
    /** Command already executed from a partial result in the current utterance. */
    private var handled: VoiceCommands.Cmd? = null
    private var statusBeforeCommand = DictState.Status.RECORDING
    private var tone: ToneGenerator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (engine == null) begin(standby = false) else resume()
            ACTION_STANDBY -> if (engine == null) begin(standby = true)
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_MARK -> mark("Marker ${state.value.markers.size + 1}")
            ACTION_STOP -> finish(save = true)
            ACTION_CANCEL -> finish(save = false, restartStandby = false)
        }
        return START_NOT_STICKY
    }

    private fun begin(standby: Boolean) {
        val app = applicationContext.app
        val s = app.settings.current
        profileName = app.settings.profileName(s.activeProfile)
        val root = app.storage.dir(s.activeProfile, Folder.DICTATION)
        startedAt = System.currentTimeMillis()
        val dir = DictationStore.newFolder(root, startedAt)
        folder = dir
        val f = File(dir, dir.name + ".m4a")
        audio = f
        segments.clear(); segStartMs = null; lastFinalMs = 0; nextIsPara = true; handled = null
        state.value = DictState(status = if (standby) DictState.Status.STANDBY else DictState.Status.RECORDING)
        ServiceCompat.startForeground(this, NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 55) }.getOrNull()

        val cfg = RecConfig(
            format = "M4A", sampleRate = 48_000, channels = 1, bitrate = 128_000, source = s.dictSource,
            noiseSuppress = s.dictNoiseSuppress, agc = s.dictAgc, liveTranscript = true,
        )
        val vocab = s.dictVocabulary.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        val t = LiveTranscriber(this, object : LiveTranscriber.Listener {
            override fun onPartial(text: String) = onPartialText(text)
            override fun onFinal(text: String) = onFinalText(text)
            override fun onError(message: String) { state.update { it.copy(sttError = message) } }
        }, SttOptions(formatting = true, vocabulary = vocab, preferOnDevice = s.dictOnDevice))
        transcriber = t
        val last = if (s.dictGeotag) Geo.lastKnown(this) else null
        val e = AudioEngine(this, cfg, f, onLevel = { lvl, _ -> levelsPush(lvl) }, transcriber = t,
            location = last?.let { it.latitude.toFloat() to it.longitude.toFloat() }, listenWhilePaused = true)
        e.paused = standby
        val err = e.start()
        if (err != null) {
            state.value = DictState(error = err)
            dir.deleteRecursively()
            stopSelf(); return
        }
        engine = e
        if (s.dictGeotag) scope.launch {
            val tag = Geo.tag(this@DictationService, withAddress = true, timeoutMs = 10_000) ?: last?.let { GeoTag.of(it) }
            state.update { it.copy(geo = tag) }
        }
        ticker = scope.launch {
            var n = 0
            while (isActive) {
                state.update { it.copy(elapsedMs = e.elapsedMs, levels = synchronized(levels) { levels.toList() }) }
                if (++n % 10 == 0) updateNotification()
                delay(100)
            }
        }
    }

    private val levels = ArrayDeque<Float>()
    private fun levelsPush(l: Float) {
        synchronized(levels) { levels.addLast(l); while (levels.size > 160) levels.removeFirst() }
        state.update { it.copy(level = l) }
    }

    private fun setStatus(st: DictState.Status, command: String? = null) {
        engine?.paused = st != DictState.Status.RECORDING
        if (st == DictState.Status.RECORDING && state.value.status == DictState.Status.STANDBY) startedAt = System.currentTimeMillis()
        state.update { it.copy(status = st, lastCommand = command ?: it.lastCommand, commandAt = if (command != null) System.currentTimeMillis() else it.commandAt) }
        updateNotification()
    }

    private fun beep(kind: Int) { runCatching { tone?.startTone(kind, 140) } }

    private fun pause() { if (state.value.status == DictState.Status.RECORDING) { setStatus(DictState.Status.PAUSED); nextIsPara = true } }
    private fun resume() { if (state.value.status == DictState.Status.PAUSED || state.value.status == DictState.Status.STANDBY) { setStatus(DictState.Status.RECORDING); nextIsPara = true } }

    private fun mark(label: String) {
        val e = engine ?: return
        state.update { it.copy(markers = it.markers + (e.elapsedMs to label)) }
    }

    private fun execute(cmd: VoiceCommands.Cmd) {
        val heard = when (cmd) { VoiceCommands.Cmd.PAUSE -> "pause recording"; VoiceCommands.Cmd.RESUME -> "resume recording"; VoiceCommands.Cmd.STOP -> "stop recording"; VoiceCommands.Cmd.MARK -> "mark that"; else -> null }
        when (cmd) {
            VoiceCommands.Cmd.PAUSE -> { pause(); beep(ToneGenerator.TONE_PROP_BEEP2) }
            VoiceCommands.Cmd.RESUME -> { resume(); beep(ToneGenerator.TONE_PROP_ACK) }
            VoiceCommands.Cmd.STOP -> { beep(ToneGenerator.TONE_PROP_PROMPT); finish(save = true) }
            VoiceCommands.Cmd.MARK -> { mark("Spoken marker"); beep(ToneGenerator.TONE_PROP_BEEP) }
            else -> {}
        }
        heard?.let { h -> state.update { it.copy(lastCommand = h, commandAt = System.currentTimeMillis()) } }
    }

    private fun voiceCommandsOn() = applicationContext.app.settings.current.dictVoiceCommands

    private fun onPartialText(text: String) {
        val e = engine ?: return
        if (segStartMs == null && text.isNotBlank()) segStartMs = e.elapsedMs
        val st = state.value.status
        if (voiceCommandsOn() && handled == null) {
            VoiceCommands.trailing(text)?.let { (cmd, _) ->
                if (cmd == VoiceCommands.Cmd.RESUME || cmd == VoiceCommands.Cmd.PAUSE || cmd == VoiceCommands.Cmd.STOP || cmd == VoiceCommands.Cmd.MARK) {
                    // act immediately (low latency); the words before the command are kept when the final result lands
                    handled = cmd; statusBeforeCommand = st
                    if (cmd == VoiceCommands.Cmd.STOP) { commit(text, st, stripCommand = true); handled = null }
                    execute(cmd)
                    return
                }
            }
        }
        state.update { it.copy(partial = if (st == DictState.Status.RECORDING) text else "") }
    }

    private fun onFinalText(text: String) {
        val st = state.value.status
        val h = handled
        handled = null
        if (h != null) { commit(text, statusBeforeCommand, stripCommand = true); return }
        if (voiceCommandsOn()) VoiceCommands.trailing(text)?.let { (cmd, _) ->
            commit(text, st, stripCommand = true)
            execute(cmd); return
        }
        commit(text, st, stripCommand = false)
    }

    /** Adds what was said to the transcript (only while recording). */
    private fun commit(raw: String, during: DictState.Status, stripCommand: Boolean) {
        val e = engine
        var text = raw.trim()
        if (stripCommand) text = VoiceCommands.trailing(text)?.second ?: text
        val startMs = segStartMs ?: e?.elapsedMs ?: 0
        segStartMs = null
        state.update { it.copy(partial = "") }
        if (during != DictState.Status.RECORDING || text.isBlank()) return
        val (formatted, paraCmd) = if (voiceCommandsOn()) VoiceCommands.inline(text) else text to false
        if (formatted.isBlank()) { nextIsPara = true; return }
        val gapPara = segments.isNotEmpty() && startMs - lastFinalMs > 6_000
        val seg = Segment(startMs, polish(formatted), para = nextIsPara || paraCmd || gapPara)
        nextIsPara = formatted.endsWith("\n\n") || raw.trim().lowercase(Locale.US).endsWith("new paragraph")
        lastFinalMs = e?.elapsedMs ?: startMs
        synchronized(segments) { segments += seg }
        state.update { it.copy(segments = synchronized(segments) { segments.toList() }) }
    }

    private fun polish(t: String): String {
        var s = t.trim()
        if (s.isEmpty()) return s
        s = s.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        if (s.last().isLetterOrDigit()) s += "."
        return s
    }

    private fun finish(save: Boolean, restartStandby: Boolean = true) {
        val e = engine ?: run { stopSelf(); return }
        engine = null
        ticker?.cancel()
        var dir = folder; var f = audio
        val snapshot = state.value
        val app = applicationContext.app
        val again = save && restartStandby && app.settings.current.dictHandsFree
        scope.launch {
            e.stop()
            var saved: String? = null
            if (dir != null && f != null) {
                if (!save || e.framesWritten == 0L) dir!!.deleteRecursively()
                else {
                    // name the session after when recording actually began (hands-free standby may start earlier)
                    val d0 = dir!!
                    if (!d0.name.startsWith(DictationStore.folderName(startedAt))) {
                        val target = DictationStore.uniqueFolder(d0.parentFile!!, startedAt)
                        if (d0.renameTo(target)) {
                            val na = File(target, target.name + ".m4a")
                            File(target, f!!.name).renameTo(na)
                            dir = target; f = na
                        }
                    }
                    val title = "Dictation · " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US))
                    val meta = DictationMeta(
                        title = title, startedAt = startedAt, endedAt = System.currentTimeMillis(), durationMs = e.elapsedMs,
                        geo = snapshot.geo, account = profileName, segments = synchronized(segments) { segments.toList() },
                        markers = snapshot.markers, audio = f.name,
                    )
                    DictationStore.write(dir!!, meta.copy(audio = f!!.name), app.settings.current.dictTimestamps)
                    app.storage.scan(f!!); app.storage.scan(DictationStore.transcriptFile(dir!!))
                    saved = app.storage.relative(dir!!)
                }
            }
            runCatching { tone?.release() }; tone = null
            if (again) {
                state.value = DictState(savedFolder = saved)
                launch(Dispatchers.Main) { begin(standby = true); state.update { it.copy(savedFolder = saved) } }
            } else {
                state.value = DictState(savedFolder = saved)
                ServiceCompat.stopForeground(this@DictationService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun notification(): Notification {
        val s = state.value
        fun action(act: String, req: Int) = PendingIntent.getService(this, req, Intent(this, DictationService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = when (s.status) {
            DictState.Status.STANDBY -> "Dictation standby · say “start recording”"
            DictState.Status.PAUSED -> "Dictation paused · say “resume recording”"
            else -> "Dictating · ${Time.duration(s.elapsedMs)}"
        }
        val words = s.segments.sumOf { it.text.split(Regex("\\s+")).size }
        return NotificationCompat.Builder(this, Notifications.CH_RECORDING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(s.segments.lastOrNull()?.text?.takeLast(80) ?: "Listening…")
            .setSubText(if (words > 0) "$words words" else null)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setColor(0xFF7C4DFF.toInt())
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(Notifications.openAppIntent(this, "dictation", 6071))
            .apply {
                when (s.status) {
                    DictState.Status.RECORDING -> addAction(0, "Pause", action(ACTION_PAUSE, 1))
                    else -> addAction(0, if (s.status == DictState.Status.STANDBY) "Start" else "Resume", action(ACTION_RESUME, 2))
                }
            }
            .addAction(0, "Stop & save", action(ACTION_STOP, 3))
            .build()
    }

    private fun updateNotification() {
        if (engine == null) return
        runCatching { getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_ID, notification()) }
    }

    override fun onDestroy() {
        engine?.stop(); engine = null
        runCatching { tone?.release() }
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }
}

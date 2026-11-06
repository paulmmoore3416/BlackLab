package com.blacklab.app.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import ai.picovoice.porcupine.PorcupineManager
import com.blacklab.app.MainActivity
import com.blacklab.app.R
import com.blacklab.app.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Hands-free wake word with Picovoice Porcupine (free personal AccessKey). Porcupine listens on the
 * phone — no audio leaves it — for the chosen word ("Jarvis", "Computer"… or your own "Hey Todd"
 * .ppn file from console.picovoice.ai). When it hears it: a chime, BlackLab listens for the request
 * with on-device speech recognition, the assistant answers out loud, and the reply also appears as
 * a notification. Then it goes back to waiting. It steps aside while BlackLab is recording or
 * dictating, and Android requires it to start while the app is open (it restarts the next time you
 * open BlackLab after a reboot).
 */
class WakeWordService : Service() {

    enum class State { OFF, STARTING, LISTENING_FOR_WORD, HEARD, ANSWERING, PAUSED, ERROR }
    data class Status(val state: State = State.OFF, val word: String = "", val message: String = "")

    companion object {
        const val CHANNEL = "wake_word"
        private const val NOTIF = 7401
        private const val REPLY_NOTIF = 7402
        const val ACTION_STOP = "com.blacklab.app.action.WAKE_STOP"
        val status = MutableStateFlow(Status())

        val BUILT_IN = listOf("JARVIS" to "Jarvis", "COMPUTER" to "Computer", "PORCUPINE" to "Porcupine", "BUMBLEBEE" to "Bumblebee", "TERMINATOR" to "Terminator",
            "PICOVOICE" to "Picovoice", "AMERICANO" to "Americano", "BLUEBERRY" to "Blueberry", "GRAPEFRUIT" to "Grapefruit", "GRASSHOPPER" to "Grasshopper")
        const val CUSTOM = "CUSTOM"

        fun customFile(context: Context) = File(context.filesDir, "wake/custom.ppn")

        fun start(context: Context) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                status.value = Status(State.ERROR, message = "Microphone permission is needed"); return
            }
            runCatching { ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java)) }
                .onFailure { status.value = Status(State.ERROR, message = "Open BlackLab to start listening (Android only lets the microphone start from the app)") }
        }

        fun stop(context: Context) { context.stopService(Intent(context, WakeWordService::class.java)) }

        /** Start or stop to match the setting — called when the app opens. */
        fun sync(context: Context) {
            val s = context.app.settings.current
            if (s.wakeWord && context.app.secrets.has(Secrets.PICOVOICE)) { if (status.value.state == State.OFF || status.value.state == State.ERROR) start(context) } else stop(context)
        }
    }

    private var porcupine: PorcupineManager? = null
    private var voice: VoiceInput? = null
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var word = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Wake word", NotificationManager.IMPORTANCE_LOW).apply { description = "Shown while BlackLab listens for its wake word"; setShowBadge(false) })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            app.settings.update { putBoolean(com.blacklab.app.core.Keys.WAKE_WORD, false) }
            stopSelf(); return START_NOT_STICKY
        }
        val s = app.settings.current
        word = if (s.wakeKeyword == CUSTOM) "your custom word" else BUILT_IN.firstOrNull { it.first == s.wakeKeyword }?.second ?: "Jarvis"
        status.value = Status(State.STARTING, word)
        try {
            ServiceCompat.startForeground(this, NOTIF, notification("Say “$word”"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            status.value = Status(State.ERROR, word, "Open BlackLab to start listening"); stopSelf(); return START_NOT_STICKY
        }
        startPorcupine()
        watchRecorders()
        return START_STICKY
    }

    private fun startPorcupine() {
        if (porcupine != null) return
        val s = app.settings.current
        val key = app.secrets.get(Secrets.PICOVOICE) ?: run { fail("Add your free Picovoice AccessKey in Assistant settings"); return }
        try {
            val b = PorcupineManager.Builder().setAccessKey(key).setSensitivity((s.wakeSensitivity / 100f).coerceIn(0.1f, 0.95f))
                .setErrorCallback { e -> main.post { fail(e.message ?: "Wake word stopped") } }
            if (s.wakeKeyword == CUSTOM) {
                val f = customFile(this)
                if (!f.exists()) { fail("Import your custom wake word (.ppn) in Assistant settings"); return }
                b.setKeywordPath(f.absolutePath)
            } else b.setKeyword(runCatching { Porcupine.BuiltInKeyword.valueOf(s.wakeKeyword) }.getOrDefault(Porcupine.BuiltInKeyword.JARVIS))
            porcupine = b.build(applicationContext) { main.post { heard() } }.also { it.start() }
            status.value = Status(State.LISTENING_FOR_WORD, word)
            update("Say “$word”")
        } catch (e: PorcupineException) {
            fail(when {
                e.javaClass.simpleName.contains("Activation") -> "Picovoice didn't accept the AccessKey (check it, or the free tier's device limit)"
                e.javaClass.simpleName.contains("Key") -> "The AccessKey isn't valid"
                else -> e.message ?: "Couldn't start the wake word"
            })
        }
    }

    private fun stopPorcupine() { runCatching { porcupine?.stop(); porcupine?.delete() }; porcupine = null }

    private fun recordingBusy() = com.blacklab.app.recorder.RecorderService.state.value.status != com.blacklab.app.recorder.RecState.Status.IDLE ||
        com.blacklab.app.dictation.DictationService.state.value.active

    /** The recorder and dictation need the microphone: step aside while they run. */
    private fun watchRecorders() {
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(2_000)
                val st = status.value.state
                if (recordingBusy() && st == State.LISTENING_FOR_WORD) { stopPorcupine(); status.value = Status(State.PAUSED, word, "Paused while recording"); update("Paused while recording") }
                else if (!recordingBusy() && st == State.PAUSED) startPorcupine()
            }
        }
    }

    private fun heard() {
        if (recordingBusy()) return
        stopPorcupine() // free the microphone for speech recognition
        status.value = Status(State.HEARD, word)
        update("Listening…")
        runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70).startTone(ToneGenerator.TONE_PROP_ACK, 160) }
        main.postDelayed({
            voice = VoiceInput(this, onPartial = { update("“$it”") }, onFinal = { text -> answer(text) }, onError = { _ -> resume() }).also { it.start() }
        }, 220)
    }

    private fun answer(text: String) {
        voice?.stop(); voice = null
        status.value = Status(State.ANSWERING, word, text)
        update("“$text”")
        scope.launch {
            val reply = runCatching { app.assistant.handle(text, Origin.CAR) }.getOrNull()
            if (reply != null && reply.text.isNotBlank()) {
                postReply(text, reply.text)
                app.speaker.speak(reply.text)
                // Wait for the spoken reply to finish before listening for the word again
                var waited = 0
                while (app.speaker.speaking.value && waited < 60_000) { kotlinx.coroutines.delay(250); waited += 250 }
            }
            resume()
        }
    }

    private fun resume() {
        voice?.stop(); voice = null
        if (recordingBusy()) { status.value = Status(State.PAUSED, word, "Paused while recording"); return }
        main.postDelayed({ startPorcupine() }, 400)
    }

    private fun fail(message: String) {
        stopPorcupine()
        status.value = Status(State.ERROR, word, message)
        update(message)
    }

    private fun postReply(asked: String, reply: String) {
        val open = PendingIntent.getActivity(this, 7403, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "assistant").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, com.blacklab.app.reminders.Notifications.CH_REMINDERS).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("“$asked”").setContentText(reply).setStyle(NotificationCompat.BigTextStyle().bigText(reply))
            .setContentIntent(open).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        if (canNotify()) try { NotificationManagerCompat.from(this).notify(REPLY_NOTIF, n) } catch (_: SecurityException) {}
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 7404, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "assistant/settings").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 7405, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("BlackLab is listening for its wake word")
            .setContentText(text).setOngoing(true).setSilent(true).setContentIntent(open).addAction(0, "Turn off", stop).build()
    }

    private fun canNotify() = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun update(text: String) {
        if (canNotify()) try { NotificationManagerCompat.from(this).notify(NOTIF, notification(text)) } catch (_: SecurityException) {}
    }

    override fun onDestroy() {
        stopPorcupine(); voice?.stop(); scope.cancel()
        status.value = Status(State.OFF)
        super.onDestroy()
    }
}

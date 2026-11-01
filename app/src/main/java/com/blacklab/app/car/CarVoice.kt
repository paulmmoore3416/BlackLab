package com.blacklab.app.car

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.media.CarAudioRecord
import androidx.core.content.ContextCompat
import com.blacklab.app.assistant.VoiceInput
import com.blacklab.app.recorder.LiveTranscriber
import com.blacklab.app.recorder.SttOptions

/**
 * Listens through the car's microphone (Car App API 5+, every current Android Auto) and feeds the
 * audio to the phone's on-device speech recognizer, so nothing leaves the phone. On older hosts it
 * falls back to the phone's microphone.
 *
 * [continuous] keeps listening and reports every finished phrase (voice notes); otherwise the first
 * finished phrase ends the session (questions and commands).
 */
class CarVoice(
    private val carContext: CarContext,
    private val continuous: Boolean,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onStopped: () -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var active = false
    private var record: CarAudioRecord? = null
    private var reader: Thread? = null
    private var transcriber: LiveTranscriber? = null
    private var fallback: VoiceInput? = null
    private var focus: AudioFocusRequest? = null
    private var heardAnything = false

    val listening: Boolean get() = active

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(carContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (active) return
        if (!hasMicPermission()) { onError("Allow microphone access for BlackLab on your phone."); return }
        active = true
        heardAnything = false
        if (runCatching { carContext.carAppApiLevel }.getOrDefault(1) >= 5) startCarMic() else startPhoneMic()
        // Give up quietly if nothing is said
        main.postDelayed(silenceCheck, if (continuous) 20_000 else 9_000)
        if (!continuous) main.postDelayed(maxRun, 25_000)
    }

    private val silenceCheck = Runnable { if (active && !heardAnything) { stop(); onError("Didn't catch that.") } }
    private val maxRun = Runnable { if (active) finish(null) }

    private fun startCarMic() {
        val am = carContext.getSystemService(AudioManager::class.java)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change -> if (change == AudioManager.AUDIOFOCUS_LOSS) main.post { stop() } }
            .build()
        runCatching { am?.requestAudioFocus(focus!!) }

        val stt = LiveTranscriber(carContext, object : LiveTranscriber.Listener {
            override fun onPartial(text: String) { heardAnything = true; main.post { if (active) onPartial(text) } }
            override fun onFinal(text: String) {
                heardAnything = true
                main.post {
                    if (!active) return@post
                    if (continuous) onFinal(text) else finish(text)
                }
            }
            override fun onError(message: String) { main.post { if (active) { stop(); onError(message) } } }
        }, SttOptions(formatting = true, preferOnDevice = true))
        transcriber = stt
        if (ContextCompat.checkSelfPermission(carContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            active = false; abandonFocus(); onError("Allow microphone access for BlackLab on your phone."); return
        }
        val rec = runCatching { CarAudioRecord.create(carContext) }.getOrElse {
            active = false; abandonFocus(); onError("The car's microphone isn't available: ${it.message ?: "unknown error"}"); return
        }
        record = rec
        stt.start(blockingFeed = false)
        reader = Thread({
            runCatching {
                rec.startRecording()
                val bytes = ByteArray(CarAudioRecord.AUDIO_CONTENT_BUFFER_SIZE)
                val shorts = ShortArray(bytes.size / 2)
                while (active) {
                    val n = rec.read(bytes, 0, bytes.size)
                    if (n <= 0) break
                    val frames = n / 2
                    for (i in 0 until frames) shorts[i] = ((bytes[2 * i + 1].toInt() shl 8) or (bytes[2 * i].toInt() and 0xFF)).toShort()
                    stt.feed(shorts, frames, 1, CarAudioRecord.AUDIO_CONTENT_SAMPLING_RATE)
                }
            }.onFailure { e -> main.post { if (active) { stop(); onError("Car microphone stopped: ${e.message ?: e.javaClass.simpleName}") } } }
            runCatching { rec.stopRecording() }
        }, "BlackLab-car-mic").apply { start() }
    }

    private fun startPhoneMic() {
        val v = VoiceInput(carContext,
            onPartial = { heardAnything = true; if (active) onPartial(it) },
            onFinal = { heardAnything = true; if (active) { if (continuous) { onFinal(it); fallback?.start() } else finish(it) } },
            onError = { if (active) { stop(); onError(it) } })
        fallback = v
        v.start()
    }

    private fun finish(text: String?) {
        stop()
        if (text.isNullOrBlank()) onError("Didn't catch that.") else onFinal(text)
    }

    fun stop() {
        if (!active) return
        active = false
        main.removeCallbacks(silenceCheck)
        main.removeCallbacks(maxRun)
        runCatching { record?.stopRecording() }
        reader = null
        record = null
        transcriber?.stop(); transcriber = null
        fallback?.stop(); fallback = null
        abandonFocus()
        onStopped()
    }

    private fun abandonFocus() {
        val f = focus ?: return
        runCatching { carContext.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(f) }
        focus = null
    }

    companion object {
        fun micGranted(c: Context) = ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
}

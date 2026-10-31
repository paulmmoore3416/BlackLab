package com.blacklab.app.assistant

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.blacklab.app.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.YearMonth
import java.util.Locale

fun Context.isOnline(): Boolean {
    val cm = getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

/**
 * Speaks assistant replies. ElevenLabs is used when online, allowed and within the monthly
 * character budget; otherwise the on-device Android voice (fully offline) takes over.
 * ElevenLabs audio is cached by text + voice so repeated phrases cost nothing.
 */
class Speaker(private val context: Context, private val app: AppContainer) {
    val speaking = MutableStateFlow(false)
    /** "ElevenLabs", "On-device" — which engine spoke last. */
    val lastEngine = MutableStateFlow("")
    var onDone: (() -> Unit)? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var player: MediaPlayer? = null
    private val usage = context.getSharedPreferences("blacklab_assistant", Context.MODE_PRIVATE)
    private val cacheDir = File(context.cacheDir, "voice").apply { mkdirs() }

    private fun ensureTts(then: () -> Unit) {
        if (tts != null && ttsReady) { then(); return }
        tts = TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.US
                // Prefer a local (offline) male US voice to suit the laid-back persona.
                val voices = runCatching { tts?.voices.orEmpty() }.getOrDefault(emptySet())
                val preferred = listOf("en-us-x-iom-local", "en-us-x-tpd-local", "en-us-x-iol-local")
                (preferred.firstNotNullOfOrNull { n -> voices.firstOrNull { it.name == n } }
                    ?: voices.firstOrNull { it.locale == Locale.US && !it.isNetworkConnectionRequired })?.let { tts?.voice = it }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { speaking.value = true }
                    override fun onDone(id: String?) { speaking.value = false; onDone?.invoke() }
                    @Deprecated("Deprecated in Java") override fun onError(id: String?) { speaking.value = false }
                })
                then()
            }
        }
    }

    fun charsUsedThisMonth(): Int {
        val month = YearMonth.now().toString()
        return if (usage.getString("eleven_month", "") == month) usage.getInt("eleven_chars", 0) else 0
    }

    private fun addUsage(chars: Int) {
        val month = YearMonth.now().toString()
        val used = charsUsedThisMonth() + chars
        usage.edit().putString("eleven_month", month).putInt("eleven_chars", used).apply()
    }

    fun speak(raw: String) {
        stop()
        val text = clean(raw)
        if (text.isBlank()) return
        val s = app.settings.current
        val key = app.secrets.get(Secrets.ELEVENLABS)
        val canEleven = key != null && !s.asstOfflineOnly && s.asstVoiceEngine != "DEVICE" && context.isOnline() &&
            charsUsedThisMonth() + text.length <= s.asstElevenBudget
        if (canEleven) {
            app.appScope.launch {
                val file = runCatching { elevenLabs(key!!, text) }.getOrNull()
                withContext(Dispatchers.Main) { if (file != null) play(file) else speakDevice(text) }
            }
        } else speakDevice(text)
    }

    private fun speakDevice(text: String) {
        val s = app.settings.current
        ensureTts {
            tts?.setSpeechRate(s.asstSpeechRate / 100f)
            tts?.setPitch(s.asstPitch / 100f)
            lastEngine.value = "On-device"
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "bl-" + System.nanoTime())
        }
    }

    private fun play(file: File) {
        lastEngine.value = "ElevenLabs"
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            setDataSource(file.absolutePath)
            setOnCompletionListener { speaking.value = false; it.release(); player = null; onDone?.invoke() }
            setOnErrorListener { mp, _, _ -> speaking.value = false; mp.release(); player = null; true }
            prepare()
            start()
        }
        speaking.value = true
    }

    private suspend fun elevenLabs(key: String, text: String): File? = withContext(Dispatchers.IO) {
        val s = app.settings.current
        val id = sha1("${s.asstElevenVoice}|${s.asstElevenModel}|${s.asstElevenStability}|${s.asstElevenSimilarity}|${s.asstElevenStyle}|$text")
        val cached = File(cacheDir, "$id.mp3")
        if (cached.length() > 0) return@withContext cached
        val conn = URL("https://api.elevenlabs.io/v1/text-to-speech/${s.asstElevenVoice}?output_format=mp3_44100_128").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 6000
        conn.readTimeout = 20000
        conn.doOutput = true
        conn.setRequestProperty("xi-api-key", key)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "audio/mpeg")
        val body = JSONObject()
            .put("text", text)
            .put("model_id", s.asstElevenModel)
            .put("voice_settings", JSONObject()
                .put("stability", s.asstElevenStability / 100.0)
                .put("similarity_boost", s.asstElevenSimilarity / 100.0)
                .put("style", s.asstElevenStyle / 100.0)
                .put("use_speaker_boost", true))
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        if (conn.responseCode !in 200..299) { conn.disconnect(); return@withContext null }
        val tmp = File(cacheDir, "$id.part")
        conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        conn.disconnect()
        tmp.renameTo(cached)
        addUsage(text.length)
        trimCache()
        cached
    }

    private fun trimCache() {
        val files = cacheDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(150).forEach { it.delete() }
    }

    fun stop() {
        runCatching { tts?.stop() }
        runCatching { player?.stop(); player?.release() }
        player = null
        speaking.value = false
    }

    fun shutdown() { stop(); tts?.shutdown(); tts = null; ttsReady = false }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Strips markdown-ish symbols and keeps speech to a reasonable length. */
    private fun clean(s: String): String = s
        .replace(Regex("[*_`#>•]"), "")
        .replace(Regex("\\[(.+?)]\\(.+?\\)"), "$1")
        .replace(Regex("\\n{2,}"), ". ")
        .replace('\n', ' ')
        .trim()
        .let { if (it.length > 600) it.take(600).substringBeforeLast(". ") + "." else it }
}

/** One-shot speech recognition that prefers the on-device recognizer (works offline). */
class VoiceInput(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onLevel: (Float) -> Unit = {},
    private val onError: (String) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null

    fun start() {
        stop()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { onError("Speech recognition isn't available."); return }
        val onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val r = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) = onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                onError(when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech needs the offline language pack (Settings › System › Languages › Speech)."
                    else -> "Listening stopped (code $error)."
                })
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isBlank()) onError("Didn't catch that.") else onFinal(text)
            }
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
        }
        r.startListening(intent)
    }

    fun stop() {
        runCatching { recognizer?.cancel(); recognizer?.destroy() }
        recognizer = null
    }
}

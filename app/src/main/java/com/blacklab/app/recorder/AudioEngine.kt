package com.blacklab.app.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

data class RecConfig(
    val format: String = "M4A", // M4A (AAC-LC) or WAV (PCM 16-bit)
    val sampleRate: Int = 48_000,
    val channels: Int = 1,
    val bitrate: Int = 192_000,
    val source: String = "MIC",
    val noiseSuppress: Boolean = false,
    val agc: Boolean = false,
    val echoCancel: Boolean = false,
    val gainDb: Int = 0,
    val deviceId: Int? = null,
    val liveTranscript: Boolean = false,
) {
    val ext: String get() = if (format == "WAV") "wav" else "m4a"
    fun summary(): String = buildString {
        append(if (format == "WAV") "WAV · lossless" else "M4A · ${bitrate / 1000} kbps")
        append(" · ${sampleRate / 1000.0}".removeSuffix(".0")).append(" kHz")
        append(if (channels == 2) " · stereo" else " · mono")
        if (gainDb > 0) append(" · +${gainDb} dB")
    }

    companion object {
        val sources = listOf(
            "MIC" to "Standard microphone",
            "CAMCORDER" to "Camcorder (directional / stereo)",
            "VOICE_RECOGNITION" to "Voice (minimal processing)",
            "VOICE_COMMUNICATION" to "Meeting / call (echo & noise cancel)",
            "UNPROCESSED" to "Raw / unprocessed",
            "VOICE_PERFORMANCE" to "Music / performance",
        )

        fun sourceId(s: String) = when (s) {
            "CAMCORDER" -> MediaRecorder.AudioSource.CAMCORDER
            "VOICE_RECOGNITION" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            "VOICE_COMMUNICATION" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            "UNPROCESSED" -> MediaRecorder.AudioSource.UNPROCESSED
            "VOICE_PERFORMANCE" -> MediaRecorder.AudioSource.VOICE_PERFORMANCE
            else -> MediaRecorder.AudioSource.MIC
        }

        fun inputDevices(context: Context): List<Pair<Int, String>> {
            val am = context.getSystemService(AudioManager::class.java)
            return am.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.isSource }.map { d ->
                val type = when (d.type) {
                    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in mic" + (d.address.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
                    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB: ${d.productName}"
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth: ${d.productName}"
                    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
                    AudioDeviceInfo.TYPE_TELEPHONY -> "Telephony"
                    else -> d.productName.toString()
                }
                d.id to type
            }.distinctBy { it.second }
        }

        fun effectsAvailable() = Triple(NoiseSuppressor.isAvailable(), AutomaticGainControl.isAvailable(), AcousticEchoCanceler.isAvailable())
    }
}

interface AudioSink {
    fun write(buf: ShortArray, n: Int)
    fun close()
}

/** PCM 16-bit little-endian WAV writer (header patched on close). */
class WavSink(file: File, private val rate: Int, private val channels: Int) : AudioSink {
    private val raf = RandomAccessFile(file, "rw").apply { setLength(0); write(ByteArray(44)) }
    private var dataBytes = 0L
    private val bb = ByteBuffer.allocate(16384 * 2).order(ByteOrder.LITTLE_ENDIAN)

    override fun write(buf: ShortArray, n: Int) {
        var off = 0
        while (off < n) {
            val count = min(n - off, bb.capacity() / 2)
            bb.clear(); bb.asShortBuffer().put(buf, off, count)
            raf.write(bb.array(), 0, count * 2)
            dataBytes += count * 2
            off += count
        }
    }

    override fun close() {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt((36 + dataBytes).toInt()); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(channels.toShort()); h.putInt(rate)
        h.putInt(rate * channels * 2); h.putShort((channels * 2).toShort()); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(dataBytes.toInt())
        raf.seek(0); raf.write(h.array()); raf.close()
    }
}

/** Real-time AAC-LC encoder → MPEG-4 (.m4a) via MediaCodec + MediaMuxer. */
class AacSink(private val file: File, private val rate: Int, private val channels: Int, bitrate: Int) : AudioSink {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var track = -1
    private var muxing = false
    private var frames = 0L
    private val info = MediaCodec.BufferInfo()

    init {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384 * channels)
        }
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    private fun pts() = frames * 1_000_000L / rate

    override fun write(buf: ShortArray, n: Int) {
        var off = 0
        while (off < n) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx < 0) { drain(false); continue }
            val input = codec.getInputBuffer(idx)!!
            input.clear()
            var count = min(input.remaining() / 2, n - off)
            count -= count % channels
            if (count <= 0) { codec.queueInputBuffer(idx, 0, 0, pts(), 0); continue }
            input.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(buf, off, count)
            codec.queueInputBuffer(idx, 0, count * 2, pts(), 0)
            frames += count / channels
            off += count
            drain(false)
        }
    }

    private fun drain(eos: Boolean) {
        var tries = 0
        while (true) {
            val out = codec.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
            when {
                out == MediaCodec.INFO_TRY_AGAIN_LATER -> { if (!eos || ++tries > 200) return }
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat); muxer.start(); muxing = true
                }
                out >= 0 -> {
                    val ob = codec.getOutputBuffer(out)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxing) {
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        muxer.writeSampleData(track, ob, info)
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    override fun close() {
        runCatching {
            val idx = codec.dequeueInputBuffer(50_000)
            if (idx >= 0) codec.queueInputBuffer(idx, 0, 0, pts(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
        }
        runCatching { codec.stop() }; runCatching { codec.release() }
        if (muxing) runCatching { muxer.stop() }
        runCatching { muxer.release() }
        if (!muxing) file.delete()
    }
}

/**
 * Captures microphone audio with AudioRecord so we get real levels, effects, device routing,
 * pause/resume and a PCM tap for live transcription — then encodes to WAV or AAC.
 */
class AudioEngine(
    private val context: Context,
    val config: RecConfig,
    private val file: File,
    private val onLevel: (level: Float, db: Float) -> Unit,
    private val transcriber: LiveTranscriber?,
) {
    @Volatile var paused = false
    @Volatile private var running = false
    @Volatile var framesWritten = 0L
        private set
    var actualRate = config.sampleRate
        private set
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var sink: AudioSink? = null
    private val effects = ArrayList<android.media.audiofx.AudioEffect>()

    val elapsedMs: Long get() = framesWritten * 1000 / actualRate

    @SuppressLint("MissingPermission")
    fun start(): String? {
        val mask = if (config.channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        var rec: AudioRecord? = null
        for (rate in listOf(config.sampleRate, 48_000, 44_100).distinct()) {
            val min = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val r = runCatching {
                AudioRecord.Builder()
                    .setAudioSource(RecConfig.sourceId(config.source))
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(mask).build())
                    .setBufferSizeInBytes(maxOf(min * 4, rate * config.channels * 2 / 5))
                    .build()
            }.getOrNull()
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) { rec = r; actualRate = rate; break } else r?.release()
        }
        val r = rec ?: return "Microphone unavailable (format not supported or in use)"
        config.deviceId?.let { id ->
            context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == id }?.let { r.setPreferredDevice(it) }
        }
        val session = r.audioSessionId
        if (config.noiseSuppress && NoiseSuppressor.isAvailable()) NoiseSuppressor.create(session)?.let { it.enabled = true; effects += it }
        if (config.agc && AutomaticGainControl.isAvailable()) AutomaticGainControl.create(session)?.let { it.enabled = true; effects += it }
        if (config.echoCancel && AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(session)?.let { it.enabled = true; effects += it }

        sink = try {
            if (config.format == "WAV") WavSink(file, actualRate, config.channels) else AacSink(file, actualRate, config.channels, config.bitrate)
        } catch (e: Exception) {
            r.release(); return "Encoder error: ${e.message}"
        }
        record = r
        running = true
        r.startRecording()
        transcriber?.start()
        thread = Thread({ loop() }, "BlackLab-audio").apply { priority = Thread.MAX_PRIORITY; start() }
        return null
    }

    private fun loop() {
        val r = record ?: return
        val buf = ShortArray(2048 * config.channels)
        val gain = 10.0.pow(config.gainDb / 20.0).toFloat()
        while (running) {
            val n = r.read(buf, 0, buf.size)
            if (n <= 0) { if (n < 0) Thread.sleep(10); continue }
            var peak = 0
            var sumSq = 0.0
            for (i in 0 until n) {
                var v = buf[i].toInt()
                if (gain != 1f) { v = (v * gain).toInt().coerceIn(-32768, 32767); buf[i] = v.toShort() }
                val a = abs(v)
                if (a > peak) peak = a
                sumSq += v.toDouble() * v
            }
            val rms = sqrt(sumSq / n)
            val db = if (rms > 0) (20 * log10(rms / 32768.0)).toFloat() else -90f
            onLevel(peak / 32768f, db)
            if (!paused) {
                runCatching { sink?.write(buf, n) }
                framesWritten += n / config.channels
                transcriber?.feed(buf, n, config.channels, actualRate)
            }
        }
    }

    fun stop() {
        running = false
        thread?.join(2000)
        runCatching { record?.stop() }
        record?.release(); record = null
        effects.forEach { runCatching { it.release() } }
        runCatching { sink?.close() }
        transcriber?.stop()
    }
}

/**
 * On-device speech recognition fed from our own PCM stream (Android 13+ EXTRA_AUDIO_SOURCE),
 * so recording and transcription happen simultaneously. Falls back gracefully if unsupported.
 */
class LiveTranscriber(private val context: Context, private val listener: Listener) {
    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var readEnd: ParcelFileDescriptor? = null
    @Volatile private var out: OutputStream? = null
    private val queue = ArrayBlockingQueue<ByteArray>(400)
    @Volatile var active = false
        private set
    @Volatile private var blocking = false
    private var feeder: Thread? = null
    private var restarts = 0

    fun start(blockingFeed: Boolean = false) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { listener.onError("Speech recognition isn't available on this device."); return }
        blocking = blockingFeed
        active = true
        main.post { openSession() }
        feeder = Thread({
            while (active || queue.isNotEmpty()) {
                val chunk = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                try { out?.write(chunk) } catch (_: Exception) { /* session rotating */ }
            }
        }, "BlackLab-stt").apply { start() }
    }

    private fun openSession() {
        if (!active) return
        runCatching { recognizer?.destroy() }
        val r = if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        val pipe = ParcelFileDescriptor.createPipe()
        runCatching { out?.close() }
        runCatching { readEnd?.close() }
        readEnd = pipe[0]
        out = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(listener::onPartial)
            }
            override fun onSegmentResults(segmentResults: Bundle) {
                segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(listener::onFinal)
            }
            override fun onEndOfSegmentedSession() { if (active) main.postDelayed({ openSession() }, 100) }
            override fun onResults(results: Bundle?) {
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(listener::onFinal)
                if (active) main.postDelayed({ openSession() }, 100)
            }
            override fun onError(error: Int) {
                val recoverable = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                if (active && recoverable && restarts++ < 500) { main.postDelayed({ openSession() }, 250); return }
                if (!active) return
                val msg = when (error) {
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                        "Offline speech pack not installed. Settings › System › Languages › Speech › On-device recognition."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required for transcription."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Transcription needs the on-device speech pack or a connection."
                    else -> "Live transcription stopped (code $error). Recording continues."
                }
                active = false
                listener.onError(msg)
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }
        runCatching { r.startListening(intent) }.onFailure { active = false; listener.onError("Couldn't start transcription: ${it.message}") }
    }

    /** Downmix + resample to 16 kHz mono 16-bit and queue for the recognizer. */
    fun feed(buf: ShortArray, n: Int, channels: Int, rate: Int) {
        if (!active) return
        val frames = n / channels
        val ratio = rate / 16_000.0
        val outFrames = (frames / ratio).toInt()
        if (outFrames <= 0) return
        val bytes = ByteArray(outFrames * 2)
        for (i in 0 until outFrames) {
            val src = (i * ratio).toInt().coerceAtMost(frames - 1)
            var sum = 0
            for (c in 0 until channels) sum += buf[src * channels + c]
            val v = sum / channels
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        if (blocking) queue.put(bytes) else queue.offer(bytes)
    }

    fun stop() {
        active = false
        feeder?.join(1500)
        runCatching { out?.close() }
        main.postDelayed({
            runCatching { recognizer?.stopListening() }
            main.postDelayed({ runCatching { recognizer?.destroy() }; recognizer = null; runCatching { readEnd?.close() } }, 4000)
        }, 300)
    }
}

/** Decodes an audio file (m4a/aac/mp3/ogg/wav) to 16-bit PCM chunks. */
object PcmDecoder {
    fun decode(file: File, onChunk: (ShortArray, Int, Int, Int) -> Unit) {
        if (file.extension.equals("wav", true)) {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(44); raf.readFully(header)
                val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val channels = h.getShort(22).toInt().coerceAtLeast(1)
                val rate = h.getInt(24)
                val bytes = ByteArray(8192 * channels)
                val shorts = ShortArray(bytes.size / 2)
                while (true) {
                    val r = raf.read(bytes); if (r <= 0) break
                    ByteBuffer.wrap(bytes, 0, r).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts, 0, r / 2)
                    onChunk(shorts, r / 2, channels, rate)
                }
            }
            return
        }
        val ex = MediaExtractor()
        ex.setDataSource(file.path)
        val idx = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return
        ex.selectTrack(idx)
        val fmt = ex.getTrackFormat(idx)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0); codec.start()
        val info = MediaCodec.BufferInfo()
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var inputDone = false
        var shorts = ShortArray(0)
        while (true) {
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val size = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (size < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { codec.queueInputBuffer(i, 0, size, ex.sampleTime, 0); ex.advance() }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                codec.outputFormat.let { channels = it.getInteger(MediaFormat.KEY_CHANNEL_COUNT); rate = it.getInteger(MediaFormat.KEY_SAMPLE_RATE) }
            } else if (o >= 0) {
                val ob = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                val count = info.size / 2
                if (shorts.size < count) shorts = ShortArray(count)
                ob.position(info.offset); ob.asShortBuffer().get(shorts, 0, count)
                codec.releaseOutputBuffer(o, false)
                if (count > 0) onChunk(shorts, count, channels, rate)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        codec.stop(); codec.release(); ex.release()
    }
}

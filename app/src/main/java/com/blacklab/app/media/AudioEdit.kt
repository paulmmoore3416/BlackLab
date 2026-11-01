// Media3 transformer, effect and session APIs used here are marked unstable; the version is pinned in build.gradle.kts.
@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.blacklab.app.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaMetadataRetriever
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import com.blacklab.app.recorder.AacSink
import com.blacklab.app.recorder.PcmDecoder
import com.blacklab.app.recorder.WavSink
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Interleaved 16-bit PCM in memory. Immutable: every edit returns a new clip (cheap undo). */
class Clip(val pcm: ShortArray, val rate: Int, val channels: Int) {
    val frames: Int get() = pcm.size / channels
    val durationMs: Long get() = frames * 1000L / rate
    fun msToFrame(ms: Long) = (ms * rate / 1000).toInt().coerceIn(0, frames)
    fun frameToMs(f: Int) = f * 1000L / rate

    /** Max-abs peaks per [PEAK_BLOCK] frames, for fast waveform drawing at any zoom. */
    val peaks: FloatArray by lazy {
        val n = (frames + PEAK_BLOCK - 1) / PEAK_BLOCK
        FloatArray(n) { b ->
            var m = 0
            val s = b * PEAK_BLOCK * channels; val e = min(pcm.size, s + PEAK_BLOCK * channels)
            for (i in s until e) { val v = abs(pcm[i].toInt()); if (v > m) m = v }
            m / 32768f
        }
    }

    companion object { const val PEAK_BLOCK = 256 }
}

object AudioEdit {
    /** ~13 minutes of 48 kHz stereo; longer files are loaded in mono to fit. */
    private const val MAX_SAMPLES = 80_000_000L

    class TooLong(msg: String) : Exception(msg)

    fun load(f: File): Clip {
        val estMs = runCatching { MediaMetadataRetriever().use { r -> r.setDataSource(f.path); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() } }.getOrNull() ?: 0L
        var rate = 0; var srcCh = 0; var outCh = 0
        var buf = ShortArray(1 shl 20); var len = 0
        PcmDecoder.decode(f) { chunk, n, ch, r ->
            if (rate == 0) {
                rate = r; srcCh = ch
                outCh = if (ch > 2 || estMs * r / 1000 * ch > MAX_SAMPLES) 1 else ch
                if (estMs * r / 1000 * outCh > MAX_SAMPLES) throw TooLong("This file is too long to edit on the phone (max about 25 minutes).")
            }
            val frames = n / srcCh
            val need = len + frames * outCh
            if (need > MAX_SAMPLES) throw TooLong("This file is too long to edit on the phone.")
            if (need > buf.size) buf = buf.copyOf(max(need, buf.size * 2).coerceAtMost(MAX_SAMPLES.toInt()))
            if (outCh == srcCh) { System.arraycopy(chunk, 0, buf, len, frames * outCh) }
            else for (i in 0 until frames) { var s = 0; for (c in 0 until srcCh) s += chunk[i * srcCh + c]; buf[len + i] = (s / srcCh).toShort() }
            len = need
        }
        if (rate == 0) error("No audio track found")
        return Clip(buf.copyOf(len), rate, outCh)
    }

    fun save(clip: Clip, out: File, aacBitrate: Int = 192_000, wav: Boolean = out.extension.equals("wav", true)) {
        val sink = if (wav) WavSink(out, clip.rate, clip.channels) else AacSink(out, clip.rate, clip.channels, aacBitrate)
        val block = 4096 * clip.channels
        var off = 0
        val tmp = ShortArray(block)
        while (off < clip.pcm.size) {
            val n = min(block, clip.pcm.size - off)
            System.arraycopy(clip.pcm, off, tmp, 0, n)
            sink.write(tmp, n); off += n
        }
        sink.close()
        require(out.exists() && out.length() > 0) { "Encoding failed" }
    }

    // ------------------------------------------------------------------ helpers

    private fun clamp(v: Float): Short = v.roundToInt().coerceIn(-32768, 32767).toShort()
    private fun range(c: Clip, a: Int, b: Int): IntRange { val s = a.coerceIn(0, c.frames); val e = b.coerceIn(s, c.frames); return s until e }
    private fun mapRange(c: Clip, a: Int, b: Int, f: (frame: Int, ch: Int, v: Short, pos: Float) -> Short): Clip {
        val out = c.pcm.copyOf(); val r = range(c, a, b); val len = max(1, r.last - r.first + 1)
        for (fr in r) for (ch in 0 until c.channels) { val i = fr * c.channels + ch; out[i] = f(fr, ch, c.pcm[i], (fr - r.first).toFloat() / len) }
        return Clip(out, c.rate, c.channels)
    }
    fun slice(c: Clip, a: Int, b: Int): Clip { val r = range(c, a, b); return Clip(c.pcm.copyOfRange(r.first * c.channels, (r.last + 1) * c.channels), c.rate, c.channels) }

    // ------------------------------------------------------------------ edits

    fun trim(c: Clip, a: Int, b: Int) = slice(c, a, b)

    fun delete(c: Clip, a: Int, b: Int): Clip {
        val r = range(c, a, b); if (r.isEmpty()) return c
        val out = ShortArray(c.pcm.size - (r.last - r.first + 1) * c.channels)
        System.arraycopy(c.pcm, 0, out, 0, r.first * c.channels)
        System.arraycopy(c.pcm, (r.last + 1) * c.channels, out, r.first * c.channels, c.pcm.size - (r.last + 1) * c.channels)
        return Clip(out, c.rate, c.channels)
    }

    fun insert(c: Clip, at: Int, other: Clip): Clip {
        val o = conform(other, c.rate, c.channels)
        val p = at.coerceIn(0, c.frames) * c.channels
        val out = ShortArray(c.pcm.size + o.pcm.size)
        System.arraycopy(c.pcm, 0, out, 0, p)
        System.arraycopy(o.pcm, 0, out, p, o.pcm.size)
        System.arraycopy(c.pcm, p, out, p + o.pcm.size, c.pcm.size - p)
        return Clip(out, c.rate, c.channels)
    }

    fun silenceClip(rate: Int, channels: Int, ms: Long) = Clip(ShortArray((ms * rate / 1000).toInt() * channels), rate, channels)
    fun silence(c: Clip, a: Int, b: Int) = mapRange(c, a, b) { _, _, _, _ -> 0 }

    /** Overlays [other] starting at [at] (it is resampled to match). */
    fun mix(c: Clip, at: Int, other: Clip, gainDb: Float = 0f): Clip {
        val o = conform(other, c.rate, c.channels)
        val g = 10f.pow(gainDb / 20f)
        val start = at.coerceIn(0, c.frames)
        val total = max(c.frames, start + o.frames)
        val out = ShortArray(total * c.channels)
        System.arraycopy(c.pcm, 0, out, 0, c.pcm.size)
        for (i in o.pcm.indices) { val j = start * c.channels + i; out[j] = clamp(out[j] + o.pcm[i] * g) }
        return Clip(out, c.rate, c.channels)
    }

    fun fadeIn(c: Clip, a: Int, b: Int) = mapRange(c, a, b) { _, _, v, t -> clamp(v * t * t) }
    fun fadeOut(c: Clip, a: Int, b: Int) = mapRange(c, a, b) { _, _, v, t -> clamp(v * (1 - t) * (1 - t)) }
    fun gain(c: Clip, a: Int, b: Int, db: Float): Clip { val g = 10f.pow(db / 20f); return mapRange(c, a, b) { _, _, v, _ -> clamp(v * g) } }

    fun peakDb(c: Clip, a: Int, b: Int): Float {
        val r = range(c, a, b); var m = 1
        for (i in r.first * c.channels until (r.last + 1) * c.channels) m = max(m, abs(c.pcm[i].toInt()))
        return 20 * log10(m / 32768f)
    }
    fun normalize(c: Clip, a: Int, b: Int, targetDb: Float = -1f) = gain(c, a, b, targetDb - peakDb(c, a, b))

    fun reverse(c: Clip, a: Int, b: Int): Clip {
        val out = c.pcm.copyOf(); val r = range(c, a, b)
        for (k in 0..(r.last - r.first)) { val src = r.last - k; for (ch in 0 until c.channels) out[(r.first + k) * c.channels + ch] = c.pcm[src * c.channels + ch] }
        return Clip(out, c.rate, c.channels)
    }

    fun echo(c: Clip, a: Int, b: Int, delayMs: Int, decay: Float): Clip {
        val d = (delayMs * c.rate / 1000) * c.channels
        val out = FloatArray(c.pcm.size) { c.pcm[it].toFloat() }
        val r = range(c, a, b)
        for (i in r.first * c.channels until (r.last + 1) * c.channels) if (i - d >= r.first * c.channels) out[i] += out[i - d] * decay
        return Clip(ShortArray(out.size) { clamp(out[it]) }, c.rate, c.channels)
    }

    /** Mutes passages quieter than [thresholdDb] (10 ms windows, soft edges) — cleans pauses in voice recordings. */
    fun noiseGate(c: Clip, a: Int, b: Int, thresholdDb: Float): Clip {
        val win = max(1, c.rate / 100)
        val thr = 32768f * 10f.pow(thresholdDb / 20f)
        val out = c.pcm.copyOf(); val r = range(c, a, b)
        var g = 1f
        var w = r.first
        while (w <= r.last) {
            val e = min(r.last + 1, w + win)
            var sum = 0.0
            for (fr in w until e) for (ch in 0 until c.channels) { val v = c.pcm[fr * c.channels + ch].toDouble(); sum += v * v }
            val rms = sqrt(sum / ((e - w) * c.channels)).toFloat()
            val target = if (rms < thr) 0f else 1f
            for (fr in w until e) { g += (target - g) * 0.02f; for (ch in 0 until c.channels) { val i = fr * c.channels + ch; out[i] = clamp(c.pcm[i] * g) } }
            w = e
        }
        return Clip(out, c.rate, c.channels)
    }

    /** RBJ biquad high-pass (rumble/handling noise) or low-pass (hiss). */
    fun filter(c: Clip, a: Int, b: Int, highPass: Boolean, cutoffHz: Float): Clip {
        val w0 = 2 * PI * cutoffHz / c.rate; val alpha = sin(w0) / (2 * 0.7071)
        val cs = cos(w0)
        val (b0, b1, b2) = if (highPass) Triple((1 + cs) / 2, -(1 + cs), (1 + cs) / 2) else Triple((1 - cs) / 2, 1 - cs, (1 - cs) / 2)
        val a0 = 1 + alpha; val a1 = -2 * cs; val a2 = 1 - alpha
        val out = c.pcm.copyOf(); val r = range(c, a, b)
        for (ch in 0 until c.channels) {
            var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
            for (fr in r) {
                val i = fr * c.channels + ch; val x = c.pcm[i].toDouble()
                val y = (b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2) / a0
                x2 = x1; x1 = x; y2 = y1; y1 = y
                out[i] = clamp(y.toFloat())
            }
        }
        return Clip(out, c.rate, c.channels)
    }

    fun toMono(c: Clip): Clip {
        if (c.channels == 1) return c
        return Clip(ShortArray(c.frames) { f -> var s = 0; for (ch in 0 until c.channels) s += c.pcm[f * c.channels + ch]; (s / c.channels).toShort() }, c.rate, 1)
    }

    fun toStereo(c: Clip): Clip = if (c.channels == 2) c else Clip(ShortArray(c.frames * 2) { c.pcm[it / 2 * c.channels] }, c.rate, 2)

    /** Linear-interpolation resample + channel match so any two clips can be combined. */
    fun conform(c: Clip, rate: Int, channels: Int): Clip {
        var x = if (channels == 1) toMono(c) else toStereo(c)
        if (x.rate != rate) {
            val ratio = x.rate.toDouble() / rate
            val frames = (x.frames / ratio).toInt()
            val out = ShortArray(frames * channels)
            for (f in 0 until frames) {
                val pos = f * ratio; val i = pos.toInt(); val t = (pos - i).toFloat()
                for (ch in 0 until channels) {
                    val s0 = x.pcm[min(i, x.frames - 1) * channels + ch]; val s1 = x.pcm[min(i + 1, x.frames - 1) * channels + ch]
                    out[f * channels + ch] = (s0 + (s1 - s0) * t).toInt().toShort()
                }
            }
            x = Clip(out, rate, channels)
        }
        return x
    }

    /** Time-stretch (tempo, pitch kept) and/or pitch shift (tempo kept) using Sonic. */
    fun speedPitch(c: Clip, speed: Float, pitch: Float): Clip {
        val sonic = SonicAudioProcessor()
        sonic.setSpeed(speed); sonic.setPitch(pitch)
        sonic.configure(AudioProcessor.AudioFormat(c.rate, c.channels, C.ENCODING_PCM_16BIT))
        sonic.flush()
        val outBuf = java.io.ByteArrayOutputStream()
        fun drain() { val o = sonic.output; if (o.hasRemaining()) { val arr = ByteArray(o.remaining()); o.get(arr); outBuf.write(arr) } }
        val chunk = 8192 * c.channels
        var off = 0
        while (off < c.pcm.size) {
            val n = min(chunk, c.pcm.size - off)
            val bb = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.nativeOrder())
            bb.asShortBuffer().put(c.pcm, off, n)
            bb.limit(n * 2)
            while (bb.hasRemaining()) { sonic.queueInput(bb); drain() }
            off += n
        }
        sonic.queueEndOfStream()
        while (!sonic.isEnded) drain()
        val bytes = outBuf.toByteArray()
        val out = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asShortBuffer().get(out)
        sonic.reset()
        return Clip(out.copyOf(out.size - out.size % c.channels), c.rate, c.channels)
    }
}

/** Streams a clip (or part of it) to the speaker; [position] is updated as it plays. */
class ClipPlayer {
    @Volatile var playing = false; private set
    @Volatile var position = 0; private set
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    fun play(c: Clip, from: Int, to: Int, onEnd: () -> Unit) {
        stop()
        val cfg = if (c.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val min = AudioTrack.getMinBufferSize(c.rate, cfg, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(c.rate).setChannelMask(cfg).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(max(min, c.rate / 5 * c.channels * 2)).setTransferMode(AudioTrack.MODE_STREAM).build()
        track = t; playing = true; position = from
        val end = to.coerceIn(from, c.frames)
        thread = Thread {
            runCatching {
                t.play()
                var f = from
                val step = 2048
                while (playing && f < end) {
                    val n = min(step, end - f)
                    t.write(c.pcm, f * c.channels, n * c.channels)
                    f += n
                    position = from + (t.playbackHeadPosition).coerceAtMost(end - from)
                }
                if (playing) { while (playing && t.playbackHeadPosition < end - from) { position = from + t.playbackHeadPosition; Thread.sleep(20) } }
            }
            runCatching { t.stop(); t.release() }
            val natural = playing
            playing = false
            if (natural) onEnd()
        }.apply { name = "BlackLab-clip"; start() }
    }

    fun stop() {
        playing = false
        thread?.join(500); thread = null
        runCatching { track?.release() }; track = null
    }
}

package com.blacklab.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** One piece of the timeline: a trimmed video, or a photo shown for [imageMs]. */
data class VClip(
    val uri: Uri, val name: String, val isImage: Boolean, val durationMs: Long,
    val width: Int, val height: Int,
    val startMs: Long = 0, val endMs: Long = durationMs, val imageMs: Long = 3000,
) {
    val lengthMs get() = if (isImage) imageMs else (endMs - startMs).coerceAtLeast(0)
}

data class VideoProject(
    val clips: List<VClip> = emptyList(),
    val speed: Float = 1f,
    val rotation: Int = 0,          // clockwise degrees: 0, 90, 180, 270
    val aspect: String = "orig",    // orig, 16:9, 9:16, 1:1, 4:5, 4:3
    val height: Int = 0,            // 0 = keep, else 2160/1080/720/480
    val filter: String = "none",    // none, bw, sepia, warm, cool, vivid, faded, invert, noir
    val brightness: Float = 0f, val contrast: Float = 0f, val saturation: Float = 0f,
    val title: String = "", val titlePos: String = "bottom", val titleSize: Float = 0.055f, val titleColor: Int = 0xFFFFFFFF.toInt(), val titleBox: Boolean = true,
    val watermark: String = "",
    val mute: Boolean = false, val volume: Float = 1f,
    val music: Uri? = null, val musicName: String = "", val musicVolume: Float = 0.5f,
    val hevc: Boolean = false, val toSdr: Boolean = false, val audioOnly: Boolean = false,
) {
    val totalMs get() = (clips.sumOf { it.lengthMs } / speed).toLong()
}

object VideoExport {

    fun probe(c: Context, uri: Uri, name: String, image: Boolean): VClip? = runCatching {
        if (image) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            val rot = runCatching { c.contentResolver.openInputStream(uri)?.use { androidx.exifinterface.media.ExifInterface(it).rotationDegrees } ?: 0 }.getOrDefault(0)
            val (w, h) = if (rot % 180 == 0) o.outWidth to o.outHeight else o.outHeight to o.outWidth
            VClip(uri, name, true, 3000, w, h)
        } else MediaMetadataRetriever().use { r ->
            r.setDataSource(c, uri)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1920
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val (ow, oh) = if (rot % 180 == 0) w to h else h to w
            VClip(uri, name, false, d, ow, oh)
        }
    }.getOrNull()

    private fun even(v: Int) = max(2, v - v % 2)

    /** Output frame size: first clip's (display) size → rotation → aspect crop → height cap. */
    fun outputSize(p: VideoProject): Pair<Int, Int> {
        val first = p.clips.firstOrNull() ?: return 1920 to 1080
        var w = first.width; var h = first.height
        if (p.rotation % 180 != 0) { val t = w; w = h; h = t }
        aspectRatio(p.aspect)?.let { r -> if (w.toFloat() / h > r) w = (h * r).roundToInt() else h = (w / r).roundToInt() }
        if (p.height > 0 && p.height != minOf(w, h)) {
            val scale = p.height.toFloat() / minOf(w, h); w = (w * scale).roundToInt(); h = (h * scale).roundToInt()
        }
        // encoders top out around 4K
        val cap = 3840f / max(w, h); if (cap < 1f) { w = (w * cap).roundToInt(); h = (h * cap).roundToInt() }
        return even(w) to even(h)
    }

    fun aspectRatio(a: String): Float? = when (a) { "16:9" -> 16f / 9; "9:16" -> 9f / 16; "1:1" -> 1f; "4:5" -> 4f / 5; "4:3" -> 4f / 3; else -> null }

    private fun matrix(vararg rows: Float) = rows // row-major 4x4 → we transpose below

    /** 4x4 column-major color matrices for the looks Media3 doesn't ship. */
    private fun look(name: String): Effect? {
        fun cm(r: FloatArray): RgbMatrix {
            val m = FloatArray(16) { i -> r[(i % 4) * 4 + i / 4] } // transpose to column-major
            return RgbMatrix { _, _ -> m }
        }
        return when (name) {
            "bw" -> RgbFilter.createGrayscaleFilter()
            "invert" -> RgbFilter.createInvertedFilter()
            "sepia" -> cm(matrix(0.393f, 0.769f, 0.189f, 0f, 0.349f, 0.686f, 0.168f, 0f, 0.272f, 0.534f, 0.131f, 0f, 0f, 0f, 0f, 1f))
            "warm" -> cm(matrix(1.10f, 0f, 0f, 0f, 0f, 1.02f, 0f, 0f, 0f, 0f, 0.88f, 0f, 0f, 0f, 0f, 1f))
            "cool" -> cm(matrix(0.90f, 0f, 0f, 0f, 0f, 1.0f, 0f, 0f, 0f, 0f, 1.12f, 0f, 0f, 0f, 0f, 1f))
            "faded" -> cm(matrix(0.80f, 0.10f, 0.10f, 0f, 0.10f, 0.80f, 0.10f, 0f, 0.10f, 0.10f, 0.80f, 0f, 0f, 0f, 0f, 1f))
            "noir" -> cm(matrix(0.40f, 0.70f, 0.15f, 0f, 0.40f, 0.70f, 0.15f, 0f, 0.40f, 0.70f, 0.15f, 0f, 0f, 0f, 0f, 1f))
            "vivid" -> HslAdjustment.Builder().adjustSaturation(35f).build()
            else -> null
        }
    }

    private fun textBitmap(text: String, outW: Int, outH: Int, sizeFrac: Float, color: Int, box: Boolean): Bitmap {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = minOf(outW, outH) * sizeFrac; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
        if (!box) p.setShadowLayer(p.textSize * 0.12f, 0f, p.textSize * 0.05f, 0xAA000000.toInt())
        val lines = text.lines()
        val pad = p.textSize * 0.45f
        val w = (lines.maxOf { p.measureText(it) } + pad * 2).toInt().coerceIn(2, outW)
        val h = (lines.size * p.textSize * 1.25f + pad * 2).toInt().coerceIn(2, outH)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        if (box) c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), pad, pad, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0x99000000.toInt() })
        var y = pad + p.textSize
        lines.forEach { l -> c.drawText(l, (w - p.measureText(l)) / 2, y, p); y += p.textSize * 1.25f }
        return b
    }

    /** The per-clip video effect chain — identical for live preview and export. */
    fun videoEffects(p: VideoProject, forPreview: Boolean = false): List<Effect> {
        val (w, h) = outputSize(p)
        val list = mutableListOf<Effect>()
        if (p.rotation != 0) list += ScaleAndRotateTransformation.Builder().setRotationDegrees((360 - p.rotation).toFloat()).build()
        list += Presentation.createForWidthAndHeight(w, h, if (p.aspect == "orig") Presentation.LAYOUT_SCALE_TO_FIT else Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        look(p.filter)?.let { list += it }
        if (p.brightness != 0f) list += Brightness(p.brightness)
        if (p.contrast != 0f) list += Contrast(p.contrast)
        if (p.saturation != 0f) list += HslAdjustment.Builder().adjustSaturation(p.saturation * 100f).build()
        val overlays = mutableListOf<androidx.media3.effect.TextureOverlay>()
        if (p.title.isNotBlank()) {
            val y = when (p.titlePos) { "top" -> 0.82f; "center" -> 0f; else -> -0.82f }
            val anchorY = when (p.titlePos) { "top" -> 1f; "center" -> 0f; else -> -1f }
            overlays += BitmapOverlay.createStaticBitmapOverlay(textBitmap(p.title, w, h, p.titleSize, p.titleColor, p.titleBox),
                StaticOverlaySettings.Builder().setBackgroundFrameAnchor(0f, y).setOverlayFrameAnchor(0f, anchorY).build())
        }
        if (p.watermark.isNotBlank()) {
            overlays += BitmapOverlay.createStaticBitmapOverlay(textBitmap(p.watermark, w, h, 0.028f, 0xFFFFFFFF.toInt(), false),
                StaticOverlaySettings.Builder().setBackgroundFrameAnchor(0.94f, -0.92f).setOverlayFrameAnchor(1f, -1f).setAlphaScale(0.75f).build())
        }
        if (overlays.isNotEmpty()) list += OverlayEffect(overlays)
        return list
    }

    private fun gain(v: Float): AudioProcessor = ChannelMixingAudioProcessor().apply {
        putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(1, 1).scaleBy(v))
        putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(2, 2).scaleBy(v))
    }

    private class ConstantSpeed(val s: Float) : SpeedProvider {
        override fun getSpeed(timeUs: Long) = s
        override fun getNextSpeedChangeTimeUs(timeUs: Long) = C.TIME_UNSET
    }

    fun mediaItem(c: VClip): MediaItem = if (c.isImage) MediaItem.Builder().setUri(c.uri).setImageDurationMs(c.imageMs).build()
    else MediaItem.Builder().setUri(c.uri).setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(c.startMs).setEndPositionMs(c.endMs).build()).build()

    fun composition(p: VideoProject, toneMap: Boolean): Composition {
        val vfx = if (p.audioOnly) emptyList() else videoEffects(p)
        val afx = if (p.volume != 1f) listOf(gain(p.volume)) else emptyList()
        val items = p.clips.map { c ->
            EditedMediaItem.Builder(mediaItem(c)).apply {
                if (c.isImage) { setDurationUs(c.imageMs * 1000); setFrameRate(30) }
                if (p.mute) setRemoveAudio(true)
                if (p.audioOnly) setRemoveVideo(true)
                if (p.speed != 1f) setSpeed(ConstantSpeed(p.speed))
                setEffects(Effects(if (p.mute) emptyList() else afx, vfx))
            }.build()
        }
        val anyImage = p.clips.any { it.isImage }
        val main = EditedMediaItemSequence.Builder(items).apply { if (anyImage && !p.mute) experimentalSetForceAudioTrack(true) }.build()
        val sequences = mutableListOf(main)
        p.music?.let { m ->
            val music = EditedMediaItem.Builder(MediaItem.fromUri(m)).setRemoveVideo(true).setEffects(Effects(listOf(gain(p.musicVolume)), emptyList())).build()
            sequences += EditedMediaItemSequence.Builder(music).setIsLooping(true).build()
        }
        return Composition.Builder(sequences).apply {
            if (toneMap || p.toSdr) setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
        }.build()
    }

    /**
     * Exports on the main looper (Transformer requirement). [onProgress] gets 0–100; returns a cancel
     * function. Retries once with HDR→SDR tone-mapping if the device can't keep HDR with effects.
     */
    fun export(c: Context, p: VideoProject, out: File, onProgress: (Int) -> Unit, onDone: (Result<File>) -> Unit): () -> Unit {
        val main = Handler(Looper.getMainLooper())
        var transformer: Transformer? = null
        var cancelled = false
        val tick = object : Runnable {
            val holder = ProgressHolder()
            override fun run() {
                val t = transformer ?: return
                if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                main.postDelayed(this, 250)
            }
        }
        fun start(toneMap: Boolean) {
            val t = Transformer.Builder(c.applicationContext)
                .setVideoMimeType(if (p.hevc) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) { main.removeCallbacks(tick); onDone(Result.success(out)) }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        main.removeCallbacks(tick)
                        if (!toneMap && !p.audioOnly && !cancelled) { out.delete(); start(true) } else { out.delete(); onDone(Result.failure(exportException)) }
                    }
                }).build()
            transformer = t
            out.delete()
            t.start(composition(p, toneMap), out.path)
            main.post(tick)
        }
        main.post { runCatching { start(false) }.onFailure { onDone(Result.failure(it)) } }
        return { main.post { cancelled = true; main.removeCallbacks(tick); transformer?.cancel(); out.delete() } }
    }

    /** Grabs the frame at [ms] (full resolution) — "save this moment as a photo". */
    fun frameAt(c: Context, uri: Uri, ms: Long): Bitmap? = runCatching {
        MediaMetadataRetriever().use { r -> r.setDataSource(c, uri); r.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST) }
    }.getOrNull()
}

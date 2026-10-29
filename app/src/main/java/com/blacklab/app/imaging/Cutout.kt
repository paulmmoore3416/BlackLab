package com.blacklab.app.imaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** What goes behind the subject once the background is removed. */
sealed interface CutoutBackground {
    data object Transparent : CutoutBackground
    data class Solid(val color: Int) : CutoutBackground
    data object Blur : CutoutBackground
}

/** Subject mask at a given size: alpha 0…255 per pixel. */
class SubjectMask(val width: Int, val height: Int, val alpha: ByteArray)

/**
 * On-device background removal with ML Kit Subject Segmentation (Google Play services model,
 * downloaded once on first use). Nothing leaves the phone.
 */
object Cutout {
    private val segmenter by lazy {
        SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build())
    }

    /** Ask Play services to fetch the model ahead of time (no-op once installed). */
    fun prefetch(ctx: Context) {
        runCatching { ModuleInstall.getClient(ctx).installModules(ModuleInstallRequest.newBuilder().addApi(segmenter).build()) }
    }

    suspend fun mask(ctx: Context, bmp: Bitmap): Result<SubjectMask> {
        prefetch(ctx)
        return suspendCancellableCoroutine { cont ->
            segmenter.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { r ->
                    val buf = r.foregroundConfidenceMask
                    if (buf == null) { cont.resume(Result.failure(IllegalStateException("No subject found"))); return@addOnSuccessListener }
                    buf.rewind()
                    val n = bmp.width * bmp.height
                    val a = ByteArray(n)
                    var any = false
                    for (i in 0 until n) {
                        // soft-threshold the confidence for crisp but anti-aliased edges
                        val c = buf.get()
                        val t = ((c - 0.35f) / 0.35f).coerceIn(0f, 1f)
                        val s = t * t * (3 - 2 * t)
                        val v = (s * 255).toInt()
                        if (v > 32) any = true
                        a[i] = v.toByte()
                    }
                    if (!any) cont.resume(Result.failure(IllegalStateException("Couldn't find a clear subject in this picture")))
                    else cont.resume(Result.success(SubjectMask(bmp.width, bmp.height, a)))
                }
                .addOnFailureListener { e ->
                    val msg = e.message.orEmpty()
                    cont.resume(Result.failure(IllegalStateException(
                        if (msg.contains("download", true) || msg.contains("module", true)) "Downloading the background-removal model… try again in a moment." else msg.ifBlank { "Background removal failed" },
                    )))
                }
        }
    }

    private fun maskBitmap(m: SubjectMask): Bitmap {
        val px = IntArray(m.width * m.height) { (m.alpha[it].toInt() and 0xFF) shl 24 }
        return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
    }

    /** Composites [src] over [bg] using [mask] (scaled to the source size with bilinear filtering). */
    fun apply(src: Bitmap, mask: SubjectMask, bg: CutoutBackground): Bitmap {
        val w = src.width; val h = src.height
        val subject = src.copy(Bitmap.Config.ARGB_8888, true)
        val mb = maskBitmap(mask)
        Canvas(subject).drawBitmap(mb, Rect(0, 0, mb.width, mb.height), Rect(0, 0, w, h),
            Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        mb.recycle()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        when (bg) {
            CutoutBackground.Transparent -> {}
            is CutoutBackground.Solid -> c.drawColor(bg.color)
            CutoutBackground.Blur -> {
                val small = Bitmap.createScaledBitmap(src, (w / 16).coerceAtLeast(2), (h / 16).coerceAtLeast(2), true)
                val smaller = Bitmap.createScaledBitmap(small, (w / 40).coerceAtLeast(2), (h / 40).coerceAtLeast(2), true)
                val back = Bitmap.createScaledBitmap(smaller, small.width, small.height, true)
                c.drawBitmap(back, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
                small.recycle(); smaller.recycle(); back.recycle()
            }
        }
        c.drawBitmap(subject, 0f, 0f, null)
        subject.recycle()
        return out
    }
}

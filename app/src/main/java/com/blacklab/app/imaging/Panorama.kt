package com.blacklab.app.imaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.math.tan

data class PanoProgress(
    val running: Boolean = false,
    val started: Boolean = false,
    /** Degrees swept so far. */
    val degrees: Float = 0f,
    /** −1…1: how far the sweep has drifted up/down from the start line. */
    val drift: Float = 0f,
    val tilt: Float = 0f,
    val tooFast: Boolean = false,
    val direction: Int = 0,
    val preview: Bitmap? = null,
    val previewVersion: Int = 0,
    val frames: Int = 0,
)

/**
 * Real-time strip panorama, built for quality:
 *  • exposure & white balance are locked by the caller for the whole sweep;
 *  • each frame is placed by the gyroscope, then refined by two-level image registration (≈1 px);
 *  • only the sharp, low-distortion centre of every frame is used, re-projected onto a cylinder;
 *  • neighbouring strips are feather-blended so seams disappear;
 *  • the result is auto-cropped to the area every strip covered.
 * Strips come from the full-resolution YUV stream (up to 4080 px tall in Ultra), so a 180° sweep is ~40 MP.
 */
class PanoramaStitcher(context: Context, private val focalPx: Float) : ImageAnalysis.Analyzer, SensorEventListener {
    val progress = MutableStateFlow(PanoProgress())

    private val sm = context.getSystemService(SensorManager::class.java)
    private val rot = FloatArray(9)
    @Volatile private var yaw = 0.0
    @Volatile private var pitch = 0.0
    @Volatile private var roll = 0.0
    private var lastRawYaw = Double.NaN
    @Volatile private var running = false
    private val lock = Any()

    // frame buffers (reused)
    private var yArr = ByteArray(0); private var uArr = ByteArray(0); private var vArr = ByteArray(0)
    private var yStride = 0; private var uvStride = 0; private var uvPix = 2
    private var wb = 0; private var hb = 0; private var rotation = 90
    private var wu = 0; private var hu = 0

    // registration pyramids (previous + current)
    private var prev2 = ByteArray(0); private var cur2 = ByteArray(0)
    private var prev8 = ByteArray(0); private var cur8 = ByteArray(0)
    private var w2 = 0; private var h2 = 0; private var w8 = 0; private var h8 = 0
    private var hasPrev = false
    private var prevYaw = 0.0; private var prevPitch = 0.0

    // placement (full-res px). X grows to the right, Y grows downward.
    private var x = 0.0; private var y = 0.0
    private var dir = 0
    private var lastP = 0.0; private var lastEnd = 0.0
    private var lead = 0.0
    private var marginV = 0; private var hc = 0
    private var maxWidth = 0
    private var coverTop = Double.NEGATIVE_INFINITY; private var coverBottom = Double.POSITIVE_INFINITY
    private var frames = 0
    private val feather = 48

    // canvas tiles
    private val tileW = 1024
    private val tiles = HashMap<Int, Bitmap>()
    private var strip = IntArray(0)
    private var existing = IntArray(0)

    // tiny live preview
    private var preview: Bitmap? = null
    private var previewScale = 1f

    fun start() {
        synchronized(lock) {
            tiles.values.forEach { it.recycle() }; tiles.clear()
            hasPrev = false; x = 0.0; y = 0.0; dir = 0; frames = 0; lastRawYaw = Double.NaN
            coverTop = Double.NEGATIVE_INFINITY; coverBottom = Double.POSITIVE_INFINITY
            preview = null
            running = true
        }
        sm?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        progress.value = PanoProgress(running = true)
    }

    fun cancel() {
        running = false
        sm?.unregisterListener(this)
        synchronized(lock) { tiles.values.forEach { it.recycle() }; tiles.clear() }
        progress.value = PanoProgress()
    }

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        val raw = atan2(-rot[2].toDouble(), -rot[5].toDouble())
        if (lastRawYaw.isNaN()) { lastRawYaw = raw; yaw = 0.0 } else {
            var d = raw - lastRawYaw
            if (d > PI) d -= 2 * PI; if (d < -PI) d += 2 * PI
            yaw += d; lastRawYaw = raw
        }
        pitch = kotlin.math.asin((-rot[8]).toDouble().coerceIn(-1.0, 1.0))
        roll = kotlin.math.asin(rot[6].toDouble().coerceIn(-1.0, 1.0))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun analyze(image: ImageProxy) {
        if (!running) { image.close(); return }
        try { copyFrame(image) } finally { image.close() }
        synchronized(lock) { if (running) runCatching { processFrame() } }
    }

    private fun copyFrame(img: ImageProxy) {
        rotation = img.imageInfo.rotationDegrees
        wb = img.width; hb = img.height
        val py = img.planes[0]; val pu = img.planes[1]; val pv = img.planes[2]
        yStride = py.rowStride; uvStride = pu.rowStride; uvPix = pu.pixelStride
        fun copy(buf: java.nio.ByteBuffer, arr: ByteArray): ByteArray {
            val n = buf.remaining()
            val a = if (arr.size < n) ByteArray(n) else arr
            buf.get(a, 0, n); return a
        }
        yArr = copy(py.buffer, yArr); uArr = copy(pu.buffer, uArr); vArr = copy(pv.buffer, vArr)
        if (rotation % 180 == 0) { wu = wb; hu = hb } else { wu = hb; hu = wb }
    }

    /** Upright (u,v) → luma at that pixel (nearest). */
    private fun lumaAt(u: Int, v: Int): Int {
        val bx: Int; val by: Int
        when (rotation) {
            90 -> { bx = v; by = hb - 1 - u }
            180 -> { bx = wb - 1 - u; by = hb - 1 - v }
            270 -> { bx = wb - 1 - v; by = u }
            else -> { bx = u; by = v }
        }
        return yArr[by * yStride + bx].toInt() and 0xFF
    }

    private fun buildPyramid() {
        w2 = wu / 2; h2 = hu / 2; w8 = w2 / 4; h8 = h2 / 4
        if (cur2.size != w2 * h2) { cur2 = ByteArray(w2 * h2); prev2 = ByteArray(w2 * h2); cur8 = ByteArray(w8 * h8); prev8 = ByteArray(w8 * h8) }
        for (j in 0 until h2) {
            val row = j * w2
            for (i in 0 until w2) cur2[row + i] = lumaAt(i * 2, j * 2).toByte()
        }
        for (j in 0 until h8) for (i in 0 until w8) {
            var s = 0
            for (dy in 0 until 4) { val r = (j * 4 + dy) * w2 + i * 4; s += (cur2[r].toInt() and 0xFF) + (cur2[r + 1].toInt() and 0xFF) + (cur2[r + 2].toInt() and 0xFF) + (cur2[r + 3].toInt() and 0xFF) }
            cur8[j * w8 + i] = (s / 16).toByte()
        }
    }

    /** Mean |prev(u,v) − cur(u−sx, v−sy)| over the central band; MAX if overlap is too small. */
    private fun sad(prev: ByteArray, cur: ByteArray, w: Int, h: Int, sx: Int, sy: Int, step: Int): Float {
        val u0 = max((w * 0.25).toInt(), sx); val u1 = min((w * 0.75).toInt(), w + sx)
        val v0 = max((h * 0.10).toInt(), sy); val v1 = min((h * 0.90).toInt(), h + sy)
        if (u1 - u0 < w / 10 || v1 - v0 < h / 3) return Float.MAX_VALUE
        var sum = 0L; var n = 0
        var v = v0
        while (v < v1) {
            val pr = v * w; val cr = (v - sy) * w - sx
            var u = u0
            while (u < u1) { sum += abs((prev[pr + u].toInt() and 0xFF) - (cur[cr + u].toInt() and 0xFF)); n++; u += step }
            v += step
        }
        return if (n == 0) Float.MAX_VALUE else sum.toFloat() / n
    }

    private fun texture(img: ByteArray, w: Int, h: Int): Float {
        var s = 0L; var n = 0
        var v = h / 10
        while (v < h * 9 / 10) { var u = w / 4; while (u < w * 3 / 4 - 1) { s += abs((img[v * w + u].toInt() and 0xFF) - (img[v * w + u + 1].toInt() and 0xFF)); n++; u += 3 }; v += 3 }
        return if (n == 0) 0f else s.toFloat() / n
    }

    private fun processFrame() {
        buildPyramid()
        val yawNow = yaw; val pitchNow = pitch
        if (!hasPrev) {
            swap(); hasPrev = true; prevYaw = yawNow; prevPitch = pitchNow
            marginV = (hu * 0.12).toInt(); hc = hu + 2 * marginV
            lead = wu * 0.40
            maxWidth = (focalPx * 2 * PI + lead + wu * 0.1).toInt()
            return
        }
        // gyro prediction (full-res px)
        val pdx = focalPx * (yawNow - prevYaw)
        val pdy = -focalPx * (pitchNow - prevPitch)
        var dx = pdx; var dy = pdy
        if (texture(prev2, w2, h2) > 2.5f) {
            // coarse search at 1/8, then fine at 1/2
            val cx = (pdx / 8).roundToInt(); val cy = (pdy / 8).roundToInt()
            var best = Float.MAX_VALUE; var bx = cx; var by = cy
            for (sy in cy - 6..cy + 6) for (sx in cx - 6..cx + 6) {
                val s = sad(prev8, cur8, w8, h8, sx, sy, 1); if (s < best) { best = s; bx = sx; by = sy }
            }
            if (best < Float.MAX_VALUE) {
                var fb = Float.MAX_VALUE; var fx = bx * 4; var fy = by * 4
                for (sy in by * 4 - 5..by * 4 + 5) for (sx in bx * 4 - 5..bx * 4 + 5) {
                    val s = sad(prev2, cur2, w2, h2, sx, sy, 3); if (s < fb) { fb = s; fx = sx; fy = sy }
                }
                val rx = fx * 2.0; val ry = fy * 2.0
                // trust the image unless it disagrees wildly with the gyro (moving people, repetitive texture)
                if (fb < 28f && abs(rx - pdx) < 0.35 * abs(pdx) + 60 && abs(ry - pdy) < 60) { dx = rx; dy = ry }
            }
        }
        x += dx; y += dy
        prevYaw = yawNow; prevPitch = pitchNow
        swap()

        if (dir == 0) {
            if (abs(x) < 30) { publish(false); return }
            dir = sign(x).toInt()
            lastP = dir * x
            lastEnd = lastP
            paint(lastP - lead, lastP, lastP, firstStrip = true)
            publish(false); return
        }
        val p = dir * x
        val step = p - lastP
        if (step < 3) { publish(false); return } // not moving forward
        val maxStep = wu * 0.42
        val fast = step > maxStep
        val end = p + min(step, maxStep) / 2
        paint(lastEnd - feather, end, p, firstStrip = false)
        lastP = p; lastEnd = end
        frames++
        if (end + lead >= maxWidth - wu * 0.1) running = false // full 360°
        publish(fast)
    }

    private fun swap() { val t = prev2; prev2 = cur2; cur2 = t; val t8 = prev8; prev8 = cur8; cur8 = t8 }

    private fun rowCenter(): Double = (marginV + hu / 2.0 + y).coerceIn(hu / 2.0, hc - hu / 2.0)

    /** Renders progress range [from, to) of the panorama from the current frame centred at progress [pk]. */
    private fun paint(from: Double, to: Double, pk: Double, firstStrip: Boolean) {
        val c0 = floor(from + lead).toInt().coerceAtLeast(0)
        val c1 = floor(to + lead).toInt().coerceAtMost(maxWidth)
        val sw = c1 - c0
        if (sw <= 0) return
        if (strip.size < sw * hc) strip = IntArray(sw * hc)
        val rc = rowCenter()
        val cu = wu / 2.0; val cv = hu / 2.0
        coverTop = max(coverTop, rc - hu / 2.0); coverBottom = min(coverBottom, rc + hu / 2.0)
        for (ci in 0 until sw) {
            val pp = c0 + ci - lead
            val phi = dir * (pp - pk) / focalPx
            val u = cu + focalPx * tan(phi)
            val scale = 1.0 / cos(phi)
            for (r in 0 until hc) {
                val v = cv + (r - rc) * scale
                strip[r * sw + ci] = if (u < 0 || u > wu - 1.001 || v < 0 || v > hu - 1.001) 0 else sample(u.toFloat(), v.toFloat())
            }
        }
        // feather the first columns into what's already there
        if (!firstStrip) {
            val fw = min(feather, sw)
            if (existing.size < fw * hc) existing = IntArray(fw * hc)
            readCanvas(c0, fw, existing)
            for (r in 0 until hc) for (ci in 0 until fw) {
                val old = existing[r * fw + ci]; val idx = r * sw + ci
                val new = strip[idx]
                if (old == 0) continue
                if (new == 0) { strip[idx] = old; continue }
                val a = (ci + 0.5f) / fw
                strip[idx] = blend(old, new, a)
            }
        }
        writeCanvas(c0, sw, strip)
        updatePreview(c0, sw)
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        val it = 1 - t
        val r = ((a shr 16 and 0xFF) * it + (b shr 16 and 0xFF) * t).toInt()
        val g = ((a shr 8 and 0xFF) * it + (b shr 8 and 0xFF) * t).toInt()
        val bl = ((a and 0xFF) * it + (b and 0xFF) * t).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    /** Bilinear luma + nearest chroma at upright (u,v) → opaque ARGB. */
    private fun sample(u: Float, v: Float): Int {
        val bx: Float; val by: Float
        when (rotation) {
            90 -> { bx = v; by = hb - 1 - u }
            180 -> { bx = wb - 1 - u; by = hb - 1 - v }
            270 -> { bx = wb - 1 - v; by = u }
            else -> { bx = u; by = v }
        }
        val x0 = bx.toInt().coerceIn(0, wb - 2); val y0 = by.toInt().coerceIn(0, hb - 2)
        val fx = bx - x0; val fy = by - y0
        val i = y0 * yStride + x0
        val l00 = yArr[i].toInt() and 0xFF; val l10 = yArr[i + 1].toInt() and 0xFF
        val l01 = yArr[i + yStride].toInt() and 0xFF; val l11 = yArr[i + yStride + 1].toInt() and 0xFF
        val yy = (l00 * (1 - fx) + l10 * fx) * (1 - fy) + (l01 * (1 - fx) + l11 * fx) * fy
        val ci = (y0 shr 1) * uvStride + (x0 shr 1) * uvPix
        val uu = (uArr.getOrElse(ci) { -128 }.toInt() and 0xFF) - 128
        val vv = (vArr.getOrElse(ci) { -128 }.toInt() and 0xFF) - 128
        val r = (yy + 1.402f * vv).roundToInt().coerceIn(0, 255)
        val g = (yy - 0.344136f * uu - 0.714136f * vv).roundToInt().coerceIn(0, 255)
        val b = (yy + 1.772f * uu).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun tile(i: Int): Bitmap = tiles.getOrPut(i) { Bitmap.createBitmap(tileW, hc, Bitmap.Config.ARGB_8888).also { it.eraseColor(0) } }

    private fun writeCanvas(c0: Int, w: Int, px: IntArray) {
        var c = c0
        while (c < c0 + w) {
            val t = c / tileW; val tx = c % tileW
            val n = min(tileW - tx, c0 + w - c)
            tile(t).setPixels(px, c - c0, w, tx, 0, n, hc)
            c += n
        }
    }

    private fun readCanvas(c0: Int, w: Int, out: IntArray) {
        var c = c0
        while (c < c0 + w) {
            val t = c / tileW; val tx = c % tileW
            val n = min(tileW - tx, c0 + w - c)
            val b = tiles[t]
            if (b != null) b.getPixels(out, c - c0, w, tx, 0, n, hc) else for (r in 0 until hc) for (k in 0 until n) out[r * w + (c - c0) + k] = 0
            c += n
        }
    }

    private fun updatePreview(c0: Int, w: Int) {
        val ph = 160
        if (preview == null) {
            previewScale = ph.toFloat() / hc
            preview = Bitmap.createBitmap((maxWidth * previewScale).toInt().coerceAtLeast(1), ph, Bitmap.Config.ARGB_8888)
        }
        val pc = Canvas(preview!!)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        var c = c0
        while (c < c0 + w) {
            val t = c / tileW; val tx = c % tileW; val n = min(tileW - tx, c0 + w - c)
            val b = tiles[t] ?: break
            val dl = (c * previewScale).toInt(); val dr = ((c + n) * previewScale).toInt().coerceAtLeast(dl + 1)
            pc.drawBitmap(b, Rect(tx, 0, tx + n, hc), Rect(dl, 0, dr, ph), paint)
            c += n
        }
    }

    private fun publish(fast: Boolean) {
        val swept = if (dir == 0) 0f else Math.toDegrees((lastEnd + lead) / focalPx).toFloat()
        progress.update {
            it.copy(
                running = running, started = dir != 0, degrees = swept.coerceAtMost(360f),
                drift = (y / max(1, marginV)).toFloat().coerceIn(-1.5f, 1.5f), tilt = Math.toDegrees(roll).toFloat(),
                tooFast = fast, direction = dir, frames = frames,
                preview = if (frames % 2 == 0) preview?.let { p -> p.copy(Bitmap.Config.ARGB_8888, false) } ?: it.preview else it.preview,
                previewVersion = it.previewVersion + 1,
            )
        }
    }

    /** Stops capture and assembles the cropped panorama. Returns null with a reason if the sweep was too short. */
    fun finish(): Pair<Bitmap?, String?> {
        running = false
        sm?.unregisterListener(this)
        synchronized(lock) {
            if (dir == 0 || tiles.isEmpty()) return null to "Move the phone slowly in one direction to capture a panorama."
            val totalW = (lastEnd + lead).toInt().coerceAtMost(maxWidth)
            if (totalW < wu * 1.2) { cleanup(); return null to "Sweep a little further for a panorama." }
            val top = (coverTop + 2).toInt().coerceAtLeast(0)
            val bottom = (coverBottom - 2).toInt().coerceAtMost(hc)
            val h = bottom - top
            if (h <= 16) { cleanup(); return null to "The sweep drifted too far up or down — keep the arrow on the line." }
            val out = Bitmap.createBitmap(totalW, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            tiles.keys.sorted().forEach { i ->
                val b = tiles.remove(i)!!
                val left = i * tileW
                if (left < totalW) c.drawBitmap(b, Rect(0, top, min(tileW, totalW - left), bottom), Rect(left, 0, min(left + tileW, totalW), h), null)
                b.recycle()
            }
            val result = if (dir < 0) Bitmap.createBitmap(out, 0, 0, out.width, out.height, Matrix().apply { preScale(-1f, 1f) }, false).also { out.recycle() } else out
            progress.value = PanoProgress()
            return result to null
        }
    }

    private fun cleanup() { tiles.values.forEach { it.recycle() }; tiles.clear(); progress.value = PanoProgress() }
}

object TinyPlanet {
    /**
     * Stereographic "little planet": the bottom of the panorama becomes the ground at the centre and
     * the sky wraps around the edge. [zoom] < 1 shows more sky, [spin] rotates the planet (degrees).
     */
    fun render(source: Bitmap, size: Int = 3072, zoom: Float = 1f, spin: Float = 0f): Bitmap {
        // keep the working copy within ~64 MB of heap
        val s = min(1f, min(8000f / source.width, 2000f / source.height))
        val src = if (s < 1f) Bitmap.createScaledBitmap(source, (source.width * s).toInt(), (source.height * s).toInt(), true) else source
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        val out = IntArray(size * size)
        val half = size / 2f
        val spinRad = Math.toRadians(spin.toDouble()).toFloat()
        // stereographic radius scale: r = k·tan(θ/2), θ ∈ [0, π] from nadir to zenith
        val k = half / tan(PI.toFloat() * 0.5f * 0.92f) * zoom
        for (j in 0 until size) {
            val dy = j - half
            for (i in 0 until size) {
                val dx = i - half
                val r = sqrt(dx * dx + dy * dy)
                val theta = 2f * kotlin.math.atan(r / k) // 0 at centre (ground) → π at infinity (sky)
                val lat = theta / PI.toFloat() // 0…1, bottom→top of panorama
                if (lat >= 1f) { out[j * size + i] = px[(w / 2).coerceAtMost(w - 1)]; continue }
                var a = atan2(dy, dx) + PI.toFloat() / 2 + spinRad
                a = ((a % (2 * PI.toFloat())) + 2 * PI.toFloat()) % (2 * PI.toFloat())
                val sx = a / (2 * PI.toFloat()) * (w - 1)
                val sy = (1f - lat) * (h - 1)
                out[j * size + i] = bilinear(px, w, h, sx, sy)
            }
        }
        if (src !== source) src.recycle()
        return Bitmap.createBitmap(out, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun bilinear(px: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
        val x0 = x.toInt().coerceIn(0, w - 1); val y0 = y.toInt().coerceIn(0, h - 1)
        val x1 = (x0 + 1) % w; val y1 = min(y0 + 1, h - 1)
        val fx = x - x0; val fy = y - y0
        val a = px[y0 * w + x0]; val b = px[y0 * w + x1]; val c = px[y1 * w + x0]; val d = px[y1 * w + x1]
        fun ch(s: Int): Int {
            val top = (a shr s and 0xFF) * (1 - fx) + (b shr s and 0xFF) * fx
            val bot = (c shr s and 0xFF) * (1 - fx) + (d shr s and 0xFF) * fx
            return (top * (1 - fy) + bot * fy).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

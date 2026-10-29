package com.blacklab.app.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.exifinterface.media.ExifInterface
import com.blacklab.app.BuildConfig
import com.blacklab.app.core.Geo
import com.blacklab.app.core.GeoTag
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** What BlackLab stamps into every photo it takes (or edits). */
data class PhotoInfo(
    val geo: GeoTag? = null,
    val headingDeg: Float? = null,
    val author: String = "",
    val copyright: String = "",
    val account: String = "",
    val caption: String = "",
    val filter: PhotoFilter = PhotoFilter.NONE,
    val time: Long = System.currentTimeMillis(),
)

object PhotoMeta {
    private val exifDate = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.US)

    /**
     * Writes geolocation plus four extra fields — street address, compass heading, altitude,
     * and author/copyright — and a BlackLab comment (account, look, caption). Safe on Ultra HDR JPEGs.
     */
    fun write(file: File, info: PhotoInfo, includeExtras: Boolean = true) {
        runCatching {
            val exif = ExifInterface(file)
            info.geo?.let { g ->
                exif.setGpsInfo(g.toLocation())
                if (g.altitude == null) { exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, null); exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, null) }
                g.accuracyM?.let { exif.setAttribute(ExifInterface.TAG_GPS_H_POSITIONING_ERROR, "${(it * 100).roundToInt()}/100") }
                exif.setAttribute(ExifInterface.TAG_GPS_MAP_DATUM, "WGS-84")
            }
            if (includeExtras) {
                info.headingDeg?.let { h ->
                    exif.setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION, "${(h * 100).roundToInt()}/100")
                    exif.setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION_REF, "T")
                }
                info.geo?.address?.let { exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, it) }
                if (info.author.isNotBlank()) exif.setAttribute(ExifInterface.TAG_ARTIST, info.author)
                if (info.copyright.isNotBlank()) exif.setAttribute(ExifInterface.TAG_COPYRIGHT, info.copyright)
                val comment = listOfNotNull(
                    info.account.takeIf { it.isNotBlank() }?.let { "Account: $it" },
                    info.filter.takeIf { !it.isIdentity }?.let { "Look: ${it.label}" },
                    info.caption.takeIf { it.isNotBlank() }?.let { "Caption: $it" },
                ).joinToString(" · ")
                if (comment.isNotBlank()) exif.setAttribute(ExifInterface.TAG_USER_COMMENT, comment)
            }
            if (exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) == null) {
                val s = Instant.ofEpochMilli(info.time).atZone(ZoneId.systemDefault()).format(exifDate)
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, s); exif.setAttribute(ExifInterface.TAG_DATETIME, s)
            }
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "BlackLab ${BuildConfig.VERSION_NAME}")
            exif.saveAttributes()
        }
    }

    /** Copies camera EXIF (exposure, GPS, author…) from [from] to [to], resetting orientation since pixels are upright now. */
    fun copy(from: File, to: File, extra: (ExifInterface) -> Unit = {}) {
        runCatching {
            val src = ExifInterface(from)
            val dst = ExifInterface(to)
            TAGS.forEach { t -> src.getAttribute(t)?.let { dst.setAttribute(t, it) } }
            dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            extra(dst)
            dst.saveAttributes()
        }
    }

    private val TAGS = listOf(
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_FLASH,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_IMG_DIRECTION_REF, ExifInterface.TAG_GPS_H_POSITIONING_ERROR, ExifInterface.TAG_GPS_MAP_DATUM,
        ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_IMAGE_DESCRIPTION, ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_USER_COMMENT,
    )

    /** Human-readable details for the viewer's info sheet. */
    fun read(file: File): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        runCatching {
            val e = ExifInterface(file)
            e.latLong?.let { (lat, lon) ->
                out += "Location" to "${Geo.dms(lat, "N", "S")}  ${Geo.dms(lon, "E", "W")}"
                out += "Coordinates" to String.format(Locale.US, "%.6f, %.6f", lat, lon)
            }
            e.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)?.takeIf { it.isNotBlank() }?.let { out += "Address" to it }
            e.getAttributeDouble(ExifInterface.TAG_GPS_IMG_DIRECTION, Double.NaN).takeIf { !it.isNaN() }?.let { out += "Facing" to "${it.roundToInt()}° ${Geo.compass(it.toFloat())}" }
            e.getAltitude(Double.NaN).takeIf { !it.isNaN() && e.getAttribute(ExifInterface.TAG_GPS_ALTITUDE) != null }?.let { out += "Altitude" to String.format(Locale.US, "%.0f m (%.0f ft)", it, it * 3.28084) }
            e.getAttribute(ExifInterface.TAG_GPS_H_POSITIONING_ERROR)?.let { e.getAttributeDouble(ExifInterface.TAG_GPS_H_POSITIONING_ERROR, 0.0) }?.takeIf { it > 0 }?.let { out += "GPS accuracy" to "±${it.roundToInt()} m" }
            e.getAttribute(ExifInterface.TAG_ARTIST)?.takeIf { it.isNotBlank() }?.let { out += "Author" to it }
            e.getAttribute(ExifInterface.TAG_COPYRIGHT)?.takeIf { it.isNotBlank() }?.let { out += "Copyright" to it }
            e.getAttribute(ExifInterface.TAG_USER_COMMENT)?.takeIf { it.isNotBlank() }?.let { out += "Notes" to it }
            e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let { out += "Taken" to it.replaceFirst(":", "-").replaceFirst(":", "-") }
        }
        return out
    }

    fun latLong(file: File): DoubleArray? = runCatching { ExifInterface(file).latLong }.getOrNull()

    /** Burns a translucent date/place/GPS caption into the bottom of a picture (inspection-style). */
    fun drawStamp(c: Canvas, w: Float, h: Float, info: PhotoInfo) {
        val lines = buildList {
            add(Instant.ofEpochMilli(info.time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy · h:mm:ss a", Locale.US)))
            info.geo?.address?.let { add(it) }
            info.geo?.let { g ->
                add(buildString {
                    append(g.coords)
                    g.altitude?.let { append(String.format(Locale.US, " · %.0f m", it)) }
                    info.headingDeg?.let { append(" · facing ${it.roundToInt()}° ${Geo.compass(it)}") }
                })
            }
            listOf(info.author, info.account).filter { it.isNotBlank() }.joinToString(" · ").takeIf { it.isNotBlank() }?.let { add(it) }
        }
        val size = minOf(w, h) * 0.026f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = size; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        val pad = size * 0.8f
        val lh = size * 1.32f
        val boxH = lines.size * lh + pad * 1.4f
        val boxW = (lines.maxOfOrNull { p.measureText(it) } ?: 0f) + pad * 2
        val r = RectF(pad, h - pad - boxH, pad + boxW.coerceAtMost(w - 2 * pad), h - pad)
        c.drawRoundRect(r, size * 0.6f, size * 0.6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() })
        lines.forEachIndexed { i, l -> c.drawText(l, r.left + pad, r.top + pad * 0.7f + lh * (i + 1) - size * 0.3f, p) }
    }
}

object BitmapIO {
    fun orientationDegrees(file: File): Int = runCatching { ExifInterface(file).rotationDegrees }.getOrDefault(0)

    fun bounds(file: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        val rot = orientationDegrees(file)
        return if (rot == 90 || rot == 270) o.outHeight to o.outWidth else o.outWidth to o.outHeight
    }

    /** Decodes upright (EXIF rotation/mirroring applied), optionally subsampled so the long side is ≤ [maxDim]. */
    fun decode(file: File, maxDim: Int = Int.MAX_VALUE, mutable: Boolean = false): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        if (o.outWidth <= 0) return null
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inMutable = mutable; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        var bmp = BitmapFactory.decodeFile(file.path, opts) ?: return null
        val exif = runCatching { ExifInterface(file) }.getOrNull()
        val m = Matrix()
        when (exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        val scale = maxDim.toFloat() / maxOf(bmp.width, bmp.height)
        if (scale < 1f) m.postScale(scale, scale)
        if (!m.isIdentity) {
            val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (r !== bmp) bmp.recycle()
            bmp = r
        }
        if (mutable && !bmp.isMutable) bmp = bmp.copy(Bitmap.Config.ARGB_8888, true)
        return bmp
    }

    fun saveJpeg(bmp: Bitmap, file: File, quality: Int = 95) {
        file.parentFile?.mkdirs()
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    }

    fun savePng(bmp: Bitmap, file: File) {
        file.parentFile?.mkdirs()
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * Applies a look and/or burns an info stamp into a just-captured photo, in place.
     * Keeps the Ultra HDR gain map when only a stamp is added.
     */
    fun bake(file: File, filter: PhotoFilter, vintageDate: Boolean, stamp: PhotoInfo?) {
        if (filter.isIdentity && stamp == null) return
        val src = decode(file) ?: return
        val gain = src.gainmap
        val out = if (filter.isIdentity) src.copy(Bitmap.Config.ARGB_8888, true) else FilterRenderer.apply(src, filter, filter.retroDate && vintageDate)
        if (stamp != null) PhotoMeta.drawStamp(Canvas(out), out.width.toFloat(), out.height.toFloat(), stamp)
        if (filter.isIdentity && gain != null) out.gainmap = gain
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        saveJpeg(out, tmp, 95)
        PhotoMeta.copy(file, tmp)
        src.recycle(); out.recycle()
        if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
    }
}

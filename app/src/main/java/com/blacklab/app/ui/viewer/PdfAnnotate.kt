package com.blacklab.app.ui.viewer

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.blacklab.app.files.Stamp
import java.io.File

private enum class PdfTool(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    PEN("Pen", Icons.Default.Edit), HIGHLIGHT("Highlight", Icons.Default.BorderColor), TEXT("Text", Icons.Default.TextFields),
    SIGN("Sign", Icons.Default.Draw), WHITEOUT("White-out", Icons.Default.Rectangle), BOX("Box", Icons.Default.CropSquare),
    CHECK("✓ / ✗", Icons.Default.CheckBox), ERASE("Erase", Icons.Default.AutoFixNormal),
}

private val Inks = listOf(0xFF111111, 0xFF1E4FD8, 0xFFD32F2F, 0xFF2E7D32, 0xFFF9A825, 0xFF6A1B9A, 0xFFFFFFFF).map { it.toInt() }

/** Draw, type, sign, highlight, white-out and tick boxes on PDF pages; saved as real PDF content. */
@Composable
fun PdfAnnotator(d: PdfDoc, startPage: Int, onCancel: () -> Unit, onSave: (List<Stamp>) -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    var page by remember { mutableIntStateOf(startPage.coerceIn(0, d.pageCount - 1)) }
    val stamps = remember { mutableStateListOf<Stamp>() }
    var tool by remember { mutableStateOf(PdfTool.PEN) }
    var ink by remember { mutableIntStateOf(Inks[1]) }
    var width by remember { mutableFloatStateOf(0.004f) }
    var live by remember { mutableStateOf<Stamp?>(null) }
    var textAt by remember { mutableStateOf<PointF?>(null) }
    var signAt by remember { mutableStateOf<PointF?>(null) }
    var signature by remember { mutableStateOf(loadSignature(ctx.filesDir)) }
    var signPad by remember { mutableStateOf(false) }
    var dragging by remember { mutableIntStateOf(-1) }

    Scaffold(topBar = {
        com.blacklab.app.ui.components.BlTopBar("Mark up & sign", subtitle = "Page ${page + 1} of ${d.pageCount} · ${stamps.size} mark(s)", onBack = onCancel, actions = {
            IconButton(enabled = stamps.isNotEmpty(), onClick = { stamps.removeAt(stamps.lastIndex) }) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
            TextButton(enabled = stamps.isNotEmpty(), onClick = { onSave(stamps.toList()) }) { Text("Save", fontWeight = FontWeight.Bold) }
        })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(10.dp), contentAlignment = Alignment.Center) {
                val (pw, ph) = d.sizes[page]
                val cw = with(density) { maxWidth.toPx() }; val ch = with(density) { maxHeight.toPx() }
                val scale = minOf(cw / pw, ch / ph)
                val dw = pw * scale; val dh = ph * scale
                val bmp by produceState<Bitmap?>(null, page) { value = d.render(page, (dw * 1.5f).toInt().coerceAtMost(2600)) }
                fun norm(o: Offset) = PointF((o.x / dw).coerceIn(0f, 1f), (o.y / dh).coerceIn(0f, 1f))
                fun hit(o: Offset): Int = stamps.indexOfLast { s ->
                    s.page == page && when (s) {
                        is Stamp.Text -> RectF(s.x * dw, s.y * dh, s.x * dw + s.text.lines().maxOf { it.length } * s.size * dh * 0.55f, s.y * dh + s.text.lines().size * s.size * dh * 1.25f).contains(o.x, o.y)
                        is Stamp.Image -> RectF(s.rect.left * dw, s.rect.top * dh, s.rect.right * dw, s.rect.bottom * dh).contains(o.x, o.y)
                        is Stamp.Box -> RectF(s.rect.left * dw, s.rect.top * dh, s.rect.right * dw, s.rect.bottom * dh).contains(o.x, o.y)
                        is Stamp.Ink -> s.points.any { p -> kotlin.math.hypot(p.x * dw - o.x, p.y * dh - o.y) < 30f }
                    }
                }
                Box(Modifier.size(with(density) { dw.toDp() }, with(density) { dh.toDp() }).background(Color.White)) {
                    bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
                    Canvas(Modifier.fillMaxSize()
                        .pointerInput(page, tool, ink, width) {
                            detectTapGestures { o ->
                                when (tool) {
                                    PdfTool.TEXT -> { val i = hit(o); if (i < 0 || stamps[i] !is Stamp.Text) textAt = norm(o) }
                                    PdfTool.SIGN -> if (signature == null) { signAt = norm(o); signPad = true } else placeSignature(stamps, page, signature!!, norm(o), dw / dh)
                                    PdfTool.CHECK -> stamps += Stamp.Text(page, norm(o).x - 0.012f, norm(o).y - 0.018f, "X", 0.028f, ink, bold = true)
                                    PdfTool.ERASE -> hit(o).takeIf { it >= 0 }?.let { stamps.removeAt(it) }
                                    PdfTool.PEN -> stamps += Stamp.Ink(page, listOf(norm(o)), ink, width)
                                    else -> {}
                                }
                            }
                        }
                        .pointerInput(page, tool, ink, width) {
                            var start = Offset.Zero
                            var origin: Stamp? = null
                            detectDragGestures(
                                onDragStart = { o ->
                                    start = o
                                    val i = if (tool == PdfTool.TEXT || tool == PdfTool.SIGN) hit(o) else -1
                                    if (i >= 0 && (stamps[i] is Stamp.Text || stamps[i] is Stamp.Image)) { dragging = i; origin = stamps[i] }
                                    else live = when (tool) {
                                        PdfTool.PEN -> Stamp.Ink(page, listOf(norm(o)), ink, width)
                                        PdfTool.HIGHLIGHT -> Stamp.Ink(page, listOf(norm(o)), 0xFFFFEB3B.toInt().takeIf { ink == Inks[0] || ink == Inks[6] } ?: ink, 0.022f, 0.38f)
                                        PdfTool.WHITEOUT -> Stamp.Box(page, RectF(norm(o).x, norm(o).y, norm(o).x, norm(o).y), 0xFFFFFFFF.toInt(), true, 1f)
                                        PdfTool.BOX -> Stamp.Box(page, RectF(norm(o).x, norm(o).y, norm(o).x, norm(o).y), ink, false, 1f)
                                        else -> null
                                    }
                                },
                                onDrag = { ch, _ ->
                                    val o = ch.position
                                    if (dragging >= 0) {
                                        val dx = (o.x - start.x) / dw; val dy = (o.y - start.y) / dh
                                        stamps[dragging] = when (val s = origin) {
                                            is Stamp.Text -> s.copy(x = s.x + dx, y = s.y + dy)
                                            is Stamp.Image -> s.copy(rect = RectF(s.rect).apply { offset(dx, dy) })
                                            else -> stamps[dragging]
                                        }
                                    } else live = when (val l = live) {
                                        is Stamp.Ink -> l.copy(points = l.points + norm(o))
                                        is Stamp.Box -> { val a = norm(start); val b = norm(o); l.copy(rect = RectF(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))) }
                                        else -> l
                                    }
                                },
                                onDragEnd = { live?.let { stamps += it }; live = null; dragging = -1 },
                                onDragCancel = { live = null; dragging = -1 },
                            )
                        }) {
                        drawIntoCanvas { c -> drawStamps(c.nativeCanvas, stamps.filter { it.page == page } + listOfNotNull(live), dw, dh) }
                    }
                }
            }
            // page switcher
            if (d.pageCount > 1) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = page > 0, onClick = { page-- }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Previous page") }
                Text("Page ${page + 1} / ${d.pageCount}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                IconButton(enabled = page < d.pageCount - 1, onClick = { page++ }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Next page") }
            }
            // tools
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PdfTool.entries.forEach { t -> FilterChip(t == tool, { tool = t }, label = { Text(t.label) }, leadingIcon = { Icon(t.icon, null, Modifier.size(18.dp)) }) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Inks.forEach { c ->
                    Box(Modifier.padding(end = 8.dp).size(28.dp).clip(CircleShape).background(Color(c))
                        .border(if (c == ink) 3.dp else 1.dp, if (c == ink) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape).clickable { ink = c })
                }
                if (tool == PdfTool.PEN) Slider(width, { width = it }, valueRange = 0.0015f..0.012f, modifier = Modifier.weight(1f))
                if (tool == PdfTool.SIGN) TextButton(onClick = { signPad = true; signAt = null }) { Text(if (signature == null) "Create signature" else "New signature") }
            }
            Text(when (tool) {
                PdfTool.PEN -> "Draw with your finger."
                PdfTool.HIGHLIGHT -> "Drag across text to highlight it."
                PdfTool.TEXT -> "Tap where the text should start. Drag existing text to move it."
                PdfTool.SIGN -> if (signature == null) "Tap where you want to sign." else "Tap to place your signature; drag it to adjust."
                PdfTool.WHITEOUT -> "Drag a box to cover (white-out) content."
                PdfTool.BOX -> "Drag to draw a rectangle."
                PdfTool.CHECK -> "Tap a box on a form to mark it."
                PdfTool.ERASE -> "Tap any mark to remove it."
            }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 14.dp, bottom = 8.dp))
        }
    }

    textAt?.let { at ->
        var text by remember { mutableStateOf("") }
        var size by remember { mutableFloatStateOf(0.018f) }
        var bold by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { textAt = null },
            title = { Text("Add text") },
            text = {
                Column {
                    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 2)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0.013f to "Small", 0.018f to "Medium", 0.028f to "Large", 0.045f to "Title").forEach { (v, l) -> FilterChip(size == v, { size = v }, label = { Text(l) }) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(bold, { bold = it }); Text("Bold") }
                    Row { TextButton(onClick = { text += java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("MM/dd/yyyy")) }) { Text("Insert today's date") } }
                }
            },
            confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { stamps += Stamp.Text(page, at.x, at.y, text, size, if (ink == Inks[6]) Inks[0] else ink, bold); textAt = null }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { textAt = null }) { Text("Cancel") } },
        )
    }
    if (signPad) SignaturePad(onDismiss = { signPad = false }) { bmp ->
        signature = bmp; saveSignature(ctx.filesDir, bmp); signPad = false
        val (pw, ph) = d.sizes[page]
        placeSignature(stamps, page, bmp, signAt ?: PointF(0.6f, 0.82f), pw.toFloat() / ph)
        signAt = null
    }
}

private fun placeSignature(stamps: MutableList<Stamp>, page: Int, sig: Bitmap, at: PointF, pageAspect: Float) {
    val w = 0.32f
    val h = w * pageAspect * sig.height / sig.width
    stamps += Stamp.Image(page, RectF(at.x - w / 2, at.y - h / 2, at.x + w / 2, at.y + h / 2), sig)
}

private fun drawStamps(c: android.graphics.Canvas, list: List<Stamp>, dw: Float, dh: Float) {
    list.forEach { s ->
        when (s) {
            is Stamp.Ink -> {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = s.color; alpha = (s.alpha * 255).toInt(); style = Paint.Style.STROKE; strokeWidth = s.width * dw; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
                val path = android.graphics.Path()
                s.points.forEachIndexed { i, pt -> if (i == 0) path.moveTo(pt.x * dw, pt.y * dh) else path.lineTo(pt.x * dw, pt.y * dh) }
                if (s.points.size == 1) path.lineTo(s.points[0].x * dw + 0.5f, s.points[0].y * dh)
                c.drawPath(path, p)
            }
            is Stamp.Text -> {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = s.color; textSize = s.size * dh; typeface = Typeface.create(Typeface.SANS_SERIF, if (s.bold) Typeface.BOLD else Typeface.NORMAL) }
                var y = s.y * dh + p.textSize
                s.text.lines().forEach { l -> c.drawText(l, s.x * dw, y, p); y += p.textSize * 1.25f }
            }
            is Stamp.Image -> c.drawBitmap(s.bitmap, null, RectF(s.rect.left * dw, s.rect.top * dh, s.rect.right * dw, s.rect.bottom * dh), Paint(Paint.FILTER_BITMAP_FLAG))
            is Stamp.Box -> c.drawRect(s.rect.left * dw, s.rect.top * dh, s.rect.right * dw, s.rect.bottom * dh, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = s.color; style = if (s.filled) Paint.Style.FILL else Paint.Style.STROKE; strokeWidth = 0.004f * dw
                if (s.filled && s.color == 0xFFFFFFFF.toInt()) { setShadowLayer(0f, 0f, 0f, 0) }
            })
        }
    }
}

private fun signatureFile(dir: File) = File(dir, "signature.png")
private fun loadSignature(dir: File): Bitmap? = signatureFile(dir).takeIf { it.exists() }?.let { android.graphics.BitmapFactory.decodeFile(it.path) }
private fun saveSignature(dir: File, b: Bitmap) = runCatching { signatureFile(dir).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }

/** Finger signature → transparent PNG trimmed to the ink. */
@Composable
fun SignaturePad(onDismiss: () -> Unit, onDone: (Bitmap) -> Unit) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var size by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    var color by remember { mutableIntStateOf(0xFF111111.toInt()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sign here") },
        text = {
            Column {
                Box(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).border(1.dp, Color.Gray, RoundedCornerShape(12.dp))) {
                    Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
                        detectDragGestures(onDragStart = { current = listOf(it) }, onDrag = { ch, _ -> current = current + ch.position },
                            onDragEnd = { strokes += current; current = emptyList() })
                    }) {
                        size = this.size
                        (strokes + listOf(current)).forEach { s ->
                            for (i in 1 until s.size) drawLine(Color(color), s[i - 1], s[i], strokeWidth = 5f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                        }
                        drawLine(Color.LightGray, Offset(20f, this.size.height * 0.78f), Offset(this.size.width - 20f, this.size.height * 0.78f), strokeWidth = 2f)
                    }
                }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(0xFF111111, 0xFF1E4FD8).map { it.toInt() }.forEach { c ->
                        Box(Modifier.padding(end = 8.dp).size(26.dp).clip(CircleShape).background(Color(c)).border(if (c == color) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape).clickable { color = c })
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { strokes.clear() }) { Text("Clear") }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = strokes.isNotEmpty(), onClick = {
                val all = strokes.flatten()
                val minX = all.minOf { it.x } - 8; val minY = all.minOf { it.y } - 8
                val maxX = all.maxOf { it.x } + 8; val maxY = all.maxOf { it.y } + 8
                val scale = 2f
                val bmp = Bitmap.createBitmap(((maxX - minX) * scale).toInt().coerceAtLeast(1), ((maxY - minY) * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(bmp)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = 5f * scale; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
                strokes.forEach { s ->
                    val path = android.graphics.Path()
                    s.forEachIndexed { i, o -> val x = (o.x - minX) * scale; val y = (o.y - minY) * scale; if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
                    c.drawPath(path, p)
                }
                onDone(bmp)
            }) { Text("Use signature") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

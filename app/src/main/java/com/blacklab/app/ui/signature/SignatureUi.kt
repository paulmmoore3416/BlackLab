package com.blacklab.app.ui.signature

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.blacklab.app.MainActivity
import com.blacklab.app.app
import com.blacklab.app.core.Signatures
import com.blacklab.app.ui.components.findActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Asks for the fingerprint / screen lock before a saved signature is used, once every few minutes. */
object SignatureGate {
    private var unlockedUntil = 0L
    private const val GRACE_MS = 3 * 60_000L

    fun require(context: Context, onOk: () -> Unit) {
        if (!context.app.settings.current.sigConfirm || System.currentTimeMillis() < unlockedUntil) { onOk(); return }
        val act = context.findActivity() as? MainActivity
        if (act == null || !act.canAuthenticate()) { onOk(); return } // no screen lock set up: nothing to confirm with
        act.authenticate("Use your signature", "Confirm it's you before signing") { ok ->
            if (ok) { unlockedUntil = System.currentTimeMillis() + GRACE_MS; onOk() }
        }
    }
}

/** The saved signatures, refreshed whenever the store changes. */
@Composable
fun rememberSignatures(): List<Signatures.Sig> {
    val ctx = LocalContext.current
    val v by Signatures.version.collectAsState()
    return produceState(emptyList(), v) { value = withContext(Dispatchers.IO) { Signatures.list(ctx) } }.value
}

@Composable
fun rememberSignatureBitmap(id: String?): Bitmap? {
    val ctx = LocalContext.current
    val v by Signatures.version.collectAsState()
    return produceState<Bitmap?>(null, id, v) { value = id?.let { withContext(Dispatchers.IO) { Signatures.load(ctx, it) } } }.value
}

/** A signature thumbnail on white (signatures are dark ink, so they always render on paper). */
@Composable
fun SignatureThumb(sig: Signatures.Sig, selected: Boolean = false, height: Int = 52, onClick: (() -> Unit)? = null) {
    val bmp = rememberSignatureBitmap(sig.id)
    Box(
        Modifier.height(height.dp).width((height * 2.6f).dp).clip(RoundedCornerShape(10.dp)).background(Color.White)
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(6.dp),
        contentAlignment = Alignment.Center,
    ) { bmp?.let { Image(it.asImageBitmap(), sig.label, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) } }
}

/**
 * The picker shown wherever you sign: your saved signatures and initials, plus "New".
 * [onDate] is optional (e.g. the PDF tool can add today's date next to a signature).
 */
@Composable
fun SignaturePickerRow(selectedId: String?, onSelect: (Signatures.Sig) -> Unit, onNew: (String) -> Unit, onDate: (() -> Unit)? = null) {
    val sigs = rememberSignatures()
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        sigs.forEach { s -> SignatureThumb(s, selected = s.id == selectedId, height = 44) { onSelect(s) } }
        FilterChip(false, { onNew(Signatures.SIGNATURE) }, label = { Text(if (sigs.none { it.kind == Signatures.SIGNATURE }) "Create signature" else "New signature") },
            leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(18.dp)) })
        if (sigs.none { it.kind == Signatures.INITIALS }) FilterChip(false, { onNew(Signatures.INITIALS) }, label = { Text("Initials") }, leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(18.dp)) })
        if (onDate != null) FilterChip(false, onDate, label = { Text("Date") }, leadingIcon = { Icon(Icons.Default.Event, null, Modifier.size(18.dp)) })
    }
}

// ====================================================================== capture

private val SigInks = listOf(0xFF111111, 0xFF1E3FAF, 0xFF0B2A6F).map { it.toInt() }
private val SigWidths = listOf(3.5f to "Fine", 5.5f to "Medium", 8f to "Bold")
private data class SigFont(val label: String, val family: String, val italic: Boolean)
private val SigFonts = listOf(SigFont("Script", "cursive", false), SigFont("Classic", "serif", true), SigFont("Casual", "casual", false), SigFont("Clean", "sans-serif-light", true))

/**
 * Full-screen signature capture: draw with a finger or stylus, or type your name in a script font.
 * Saves a trimmed, transparent PNG into the encrypted store and returns it.
 */
@Composable
fun SignatureCaptureDialog(kind: String, onDismiss: () -> Unit, onSaved: (Signatures.Sig, Bitmap) -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val s = ctx.app.settings.current
    var mode by remember { mutableStateOf("draw") }
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var ink by remember { mutableStateOf(SigInks[0]) }
    var width by remember { mutableStateOf(SigWidths[1].first) }
    val defaultText = if (kind == Signatures.INITIALS) s.userName.split(' ').filter { it.isNotBlank() }.joinToString("") { it.take(1).uppercase() } else s.userName
    var typed by remember { mutableStateOf(defaultText) }
    var font by remember { mutableStateOf(SigFonts[0]) }
    val title = if (kind == Signatures.INITIALS) "Your initials" else "Your signature"

    fun save() {
        val bmp = if (mode == "draw") renderInk(strokes.toList(), ink, width * density.density, 2f)
        else renderTyped(typed.trim(), ink, font)
        if (bmp == null) return
        val sig = Signatures.save(ctx, kind, bmp)
        onSaved(sig, bmp)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Cancel") }
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (mode == "draw") {
                        IconButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }, enabled = strokes.isNotEmpty()) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                        IconButton(onClick = { strokes.clear() }, enabled = strokes.isNotEmpty()) { Icon(Icons.Default.DeleteSweep, "Clear") }
                    }
                    TextButton(onClick = ::save, enabled = if (mode == "draw") strokes.isNotEmpty() else typed.isNotBlank()) { Text("Save") }
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    listOf("draw" to "Draw", "type" to "Type").forEachIndexed { i, (k, l) ->
                        SegmentedButton(mode == k, { mode = k }, SegmentedButtonDefaults.itemShape(i, 2)) { Text(l) }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(16.dp)).background(Color.White)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))) {
                    if (mode == "draw") {
                        Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
                            // Raw pointer tracking: the stroke starts the moment your finger lands (no drag threshold)
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                var pts = listOf(down.position)
                                current = pts
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    ch.historical.forEach { h -> pts = pts + h.position }
                                    pts = pts + ch.position
                                    current = pts
                                    ch.consume()
                                    if (!ch.pressed) break
                                }
                                strokes += pts
                                current = emptyList()
                            }
                        }) {
                            val base = size.height * 0.68f
                            drawLine(Color(0xFFB0B4BA), Offset(28f, base), Offset(size.width - 28f, base), strokeWidth = 2f)
                            drawContext.canvas.nativeCanvas.drawText("×", 30f, base - 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9AA0A6.toInt(); textSize = 30.sp.toPx() })
                            (strokes + listOf(current)).filter { it.isNotEmpty() }.forEach { st ->
                                if (st.size == 1) drawCircle(Color(ink), width * density.density / 2, st[0])
                                else drawPath(smoothPath(st), Color(ink), style = Stroke(width * density.density, cap = StrokeCap.Round, join = StrokeJoin.Round))
                            }
                        }
                        if (strokes.isEmpty() && current.isEmpty()) Text(if (kind == Signatures.INITIALS) "Write your initials above the line" else "Sign above the line",
                            Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp), color = Color(0xFF80868B), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(typed.ifBlank { " " }, color = Color(ink), fontSize = 48.sp, maxLines = 1,
                                fontFamily = when (font.family) { "cursive" -> FontFamily.Cursive; "serif" -> FontFamily.Serif; "sans-serif-light" -> FontFamily.SansSerif; else -> FontFamily.Default },
                                fontStyle = if (font.italic) FontStyle.Italic else FontStyle.Normal)
                            Spacer(Modifier.height(4.dp))
                            Box(Modifier.fillMaxWidth(0.85f).height(2.dp).background(Color(0xFFB0B4BA)))
                        }
                    }
                }
                if (mode == "type") {
                    OutlinedTextField(typed, { typed = it.take(if (kind == Signatures.INITIALS) 6 else 60) }, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        label = { Text(if (kind == Signatures.INITIALS) "Initials" else "Full name") }, singleLine = true)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SigFonts.forEach { f -> FilterChip(font == f, { font = f }, label = { Text(f.label) }) }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SigInks.forEach { c ->
                        Box(Modifier.padding(end = 8.dp).size(30.dp).clip(CircleShape).background(Color(c))
                            .border(if (c == ink) 3.dp else 1.dp, if (c == ink) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape).clickable { ink = c })
                    }
                    Spacer(Modifier.weight(1f))
                    if (mode == "draw") SigWidths.forEach { (w, l) -> FilterChip(width == w, { width = w }, label = { Text(l) }, modifier = Modifier.padding(start = 4.dp)) }
                }
                Text("Saved encrypted on this phone only. Never synced, backed up or shared unless you place it on a document.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
            }
        }
    }
}

private fun smoothPath(pts: List<Offset>): Path = Path().apply {
    moveTo(pts[0].x, pts[0].y)
    for (i in 1 until pts.size - 1) {
        val mid = Offset((pts[i].x + pts[i + 1].x) / 2, (pts[i].y + pts[i + 1].y) / 2)
        quadraticTo(pts[i].x, pts[i].y, mid.x, mid.y)
    }
    lineTo(pts.last().x, pts.last().y)
}

/** Strokes (screen pixels) → transparent PNG trimmed to the ink, upscaled by [scale] for crisp printing. */
internal fun renderInk(strokes: List<List<Offset>>, color: Int, widthPx: Float, scale: Float): Bitmap? {
    val all = strokes.flatten()
    if (all.isEmpty()) return null
    val pad = widthPx + 6
    val minX = all.minOf { it.x } - pad; val minY = all.minOf { it.y } - pad
    val maxX = all.maxOf { it.x } + pad; val maxY = all.maxOf { it.y } + pad
    val bmp = Bitmap.createBitmap(((maxX - minX) * scale).toInt().coerceIn(1, 4000), ((maxY - minY) * scale).toInt().coerceIn(1, 4000), Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = widthPx * scale; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    strokes.filter { it.isNotEmpty() }.forEach { s ->
        val path = android.graphics.Path()
        fun x(o: Offset) = (o.x - minX) * scale
        fun y(o: Offset) = (o.y - minY) * scale
        path.moveTo(x(s[0]), y(s[0]))
        if (s.size == 1) path.lineTo(x(s[0]) + 0.5f, y(s[0]))
        for (i in 1 until s.size - 1) {
            val mx = (x(s[i]) + x(s[i + 1])) / 2; val my = (y(s[i]) + y(s[i + 1])) / 2
            path.quadTo(x(s[i]), y(s[i]), mx, my)
        }
        if (s.size > 1) path.lineTo(x(s.last()), y(s.last()))
        c.drawPath(path, p)
    }
    return bmp
}

private fun renderTyped(text: String, color: Int, font: SigFont): Bitmap? {
    if (text.isBlank()) return null
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; textSize = 160f
        typeface = Typeface.create(Typeface.create(font.family, Typeface.NORMAL), if (font.italic) Typeface.ITALIC else Typeface.NORMAL)
    }
    val w = p.measureText(text); val fm = p.fontMetrics
    val bmp = Bitmap.createBitmap((w + 40).toInt().coerceAtLeast(1), (fm.bottom - fm.top + 30).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(bmp).drawText(text, 20f, -fm.top + 15f, p)
    return bmp
}

/**
 * "Insert signature" for documents and notebook pages: pick a saved signature or initials (or make
 * one), confirm it's you, and get the image back.
 */
@Composable
fun SignatureChooserDialog(onDismiss: () -> Unit, onChosen: (Signatures.Sig, Bitmap) -> Unit) {
    val ctx = LocalContext.current
    val sigs = rememberSignatures()
    var capture by remember { mutableStateOf<String?>(null) }
    if (capture != null) {
        SignatureCaptureDialog(capture!!, onDismiss = { capture = null }) { sig, bmp -> capture = null; onChosen(sig, bmp) }
        return
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Insert signature") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (sigs.isEmpty()) Text("You haven't saved a signature yet. Draw or type one; it's kept encrypted on this phone.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                sigs.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                        SignatureGate.require(ctx) { Signatures.load(ctx, s.id)?.let { onChosen(s, it) } }
                    }.padding(4.dp)) {
                        SignatureThumb(s, height = 48)
                        Spacer(Modifier.width(10.dp))
                        Column { Text(s.label, style = MaterialTheme.typography.bodyMedium); Text(if (s.kind == Signatures.INITIALS) "Initials" else "Signature", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { capture = Signatures.SIGNATURE }) { Text("New signature") }
                    TextButton(onClick = { capture = Signatures.INITIALS }) { Text("New initials") }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** PNG data URI for HTML documents. */
fun Bitmap.toPngDataUri(): String {
    val out = java.io.ByteArrayOutputStream(); compress(Bitmap.CompressFormat.PNG, 100, out)
    return "data:image/png;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
}

package com.blacklab.app.ui.editor

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blacklab.app.core.Files
import com.blacklab.app.imaging.BitmapIO
import com.blacklab.app.imaging.Cutout
import com.blacklab.app.imaging.CutoutBackground
import com.blacklab.app.imaging.DrawTool
import com.blacklab.app.imaging.FilterRenderer
import com.blacklab.app.imaging.Fonts
import com.blacklab.app.imaging.Mark
import com.blacklab.app.imaging.MarkupRenderer
import com.blacklab.app.imaging.PhotoFilter
import com.blacklab.app.imaging.PhotoMeta
import com.blacklab.app.imaging.StrokeMark
import com.blacklab.app.imaging.SubjectMask
import com.blacklab.app.imaging.TextMark
import com.blacklab.app.imaging.Watermark
import com.blacklab.app.ui.components.ConfirmDialog
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.components.settingsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

private val InkColors = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFEF5350, 0xFFFF7A59, 0xFFFFB74D, 0xFFFFEB3B, 0xFF4CD787, 0xFF2BD9A8,
    0xFF26C6DA, 0xFF4F8CFF, 0xFFAB7CFF, 0xFFF06292, 0xFF8D6E63, 0xFF9E9E9E,
).map { it.toInt() }

private enum class EditTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    DRAW("Draw", Icons.Default.Brush), TEXT("Text", Icons.Default.TextFields), LOOKS("Looks", Icons.Default.AutoAwesome),
    WATERMARK("Watermark", Icons.Default.BrandingWatermark), CUTOUT("Remove BG", Icons.Default.ContentCut),
}

/**
 * Photo editor: finger drawing & shapes, styled text, film looks, watermarks and background removal.
 * Edits are previewed on a working copy and re-rendered at full resolution on save.
 */
@Composable
fun ImageEditorScreen(relPath: String, initialTab: String) {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val s = settingsState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val file = remember(relPath) { app.storage.resolve(relPath) }

    var original by remember { mutableStateOf<Bitmap?>(null) }
    var working by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file) { original = withContext(Dispatchers.IO) { BitmapIO.decode(file, 2048) } }

    var tab by remember { mutableStateOf(EditTab.entries.firstOrNull { it.name.equals(initialTab, true) } ?: EditTab.DRAW) }
    val marks = remember { mutableStateListOf<Mark>() }
    val undo = remember { mutableStateListOf<List<Mark>>() }
    val redo = remember { mutableStateListOf<List<Mark>>() }
    fun commit(change: () -> Unit) { undo += marks.toList(); redo.clear(); change() }

    // draw state
    var tool by remember { mutableStateOf(DrawTool.PEN) }
    var ink by remember { mutableStateOf(0xFFEF5350.toInt()) }
    var thickness by remember { mutableFloatStateOf(0.008f) }
    var opacity by remember { mutableFloatStateOf(1f) }
    var dashed by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf<StrokeMark?>(null) }
    // text state
    var selectedText by remember { mutableStateOf<Long?>(null) }
    var editText by remember { mutableStateOf<TextMark?>(null) }
    var newText by remember { mutableStateOf(false) }
    // looks / watermark / cutout
    var filter by remember { mutableStateOf(PhotoFilter.NONE) }
    var watermarkOn by remember { mutableStateOf(initialTab.equals("watermark", true)) }
    val who = s.userName.ifBlank { s.organization }
    var watermark by remember { mutableStateOf(Watermark(text = if (who.isNotBlank()) "© ${LocalDate.now().year} $who" else "© BlackLab")) }
    var mask by remember { mutableStateOf<SubjectMask?>(null) }
    var cutoutBg by remember { mutableStateOf<CutoutBackground>(CutoutBackground.Transparent) }
    var cutoutBusy by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveMenu by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    val dirty = marks.isNotEmpty() || !filter.isIdentity || mask != null || watermarkOn

    LaunchedEffect(Unit) { Cutout.prefetch(ctx) }
    // rebuild the working copy when the look or cutout changes (same order as the full-res export)
    LaunchedEffect(original, filter, mask, cutoutBg) {
        val o = original ?: return@LaunchedEffect
        working = withContext(Dispatchers.Default) { render(o, filter, s.camVintageDate, mask, cutoutBg) }
    }
    val logoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                val tmp = File(ctx.cacheDir, "wm_logo"); Files.copyFromUri(ctx, uri, tmp); BitmapIO.decode(tmp, 1024)
            }
            if (bmp != null) { watermark = watermark.copy(logo = bmp); watermarkOn = true }
        }
    }

    BackHandler(enabled = !saving) { if (dirty) confirmExit = true else shell.back() }

    fun save(asCopy: Boolean) {
        saving = true
        scope.launch {
            val out = withContext(Dispatchers.Default) {
                runCatching {
                    val full = BitmapIO.decode(file) ?: error("Couldn't open the photo")
                    val base = render(full, filter, s.camVintageDate, mask, cutoutBg)
                    val canvasBmp = if (base.isMutable) base else base.copy(Bitmap.Config.ARGB_8888, true)
                    MarkupRenderer.draw(android.graphics.Canvas(canvasBmp), canvasBmp.width.toFloat(), canvasBmp.height.toFloat(), marks.toList(), if (watermarkOn) watermark else null)
                    val transparent = mask != null && cutoutBg == CutoutBackground.Transparent
                    val ext = if (transparent) "png" else "jpg"
                    val dir = file.parentFile!!
                    val target = if (!asCopy && ext.equals(file.extension, true)) file else Files.unique(dir, file.nameWithoutExtension.removeSuffix("_edit") + "_edit", ext)
                    val tmp = File(dir, ".edit_${System.currentTimeMillis()}.$ext")
                    if (transparent) BitmapIO.savePng(canvasBmp, tmp) else { BitmapIO.saveJpeg(canvasBmp, tmp, 95); PhotoMeta.copy(file, tmp) }
                    if (target.exists()) target.delete()
                    if (!tmp.renameTo(target)) { tmp.copyTo(target, true); tmp.delete() }
                    target
                }
            }
            saving = false
            out.onSuccess { f ->
                app.storage.scan(f)
                shell.toast("Saved ${f.name}")
                shell.back()
                if (f.path != file.path) shell.go("media?path=" + Uri.encode(app.storage.relative(f)))
            }.onFailure { shell.toast("Couldn't save: ${it.message}") }
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0B0C0F))) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // ---------------- top bar
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (dirty) confirmExit = true else shell.back() }) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
                Text("Edit", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(enabled = undo.isNotEmpty(), onClick = { redo += marks.toList(); val p = undo.removeAt(undo.lastIndex); marks.clear(); marks += p }) {
                    Icon(Icons.AutoMirrored.Filled.Undo, "Undo", tint = if (undo.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.3f))
                }
                IconButton(enabled = redo.isNotEmpty(), onClick = { undo += marks.toList(); val n = redo.removeAt(redo.lastIndex); marks.clear(); marks += n }) {
                    Icon(Icons.AutoMirrored.Filled.Redo, "Redo", tint = if (redo.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.3f))
                }
                Box {
                    TextButton(onClick = { saveMenu = true }, enabled = !saving && dirty) { Text("Save", color = if (dirty) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.4f), fontWeight = FontWeight.Bold) }
                    DropdownMenu(saveMenu, { saveMenu = false }) {
                        DropdownMenuItem(text = { Text("Save as copy") }, leadingIcon = { Icon(Icons.Default.FileCopy, null) }, onClick = { saveMenu = false; save(true) })
                        DropdownMenuItem(text = { Text("Replace original") }, leadingIcon = { Icon(Icons.Default.Save, null) }, onClick = { saveMenu = false; save(false) })
                    }
                }
            }

            // ---------------- canvas
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                val bmp = working ?: original
                if (bmp == null) { CircularProgressIndicator(); return@BoxWithConstraints }
                val cw = with(density) { maxWidth.toPx() }; val ch = with(density) { maxHeight.toPx() }
                val scale = minOf(cw / bmp.width, ch / bmp.height)
                val dw = bmp.width * scale; val dh = bmp.height * scale
                val left = (cw - dw) / 2; val top = (ch - dh) / 2
                fun norm(o: Offset) = PointF(((o.x - left) / dw).coerceIn(0f, 1f), ((o.y - top) / dh).coerceIn(0f, 1f))
                fun hitText(o: Offset): TextMark? = marks.filterIsInstance<TextMark>().lastOrNull { m ->
                    val r = MarkupRenderer.textBounds(dw, dh, m); RectF(r.left + left, r.top + top, r.right + left, r.bottom + top).contains(o.x, o.y)
                }
                val checker = remember { checkerboard() }
                Canvas(
                    Modifier.fillMaxSize()
                        .pointerInput(tab, tool, ink, thickness, opacity, dashed, bmp) {
                            var dragging: TextMark? = null
                            var dragStart = Offset.Zero
                            var origin = PointF()
                            detectDragGestures(
                                onDragStart = { o ->
                                    when (tab) {
                                        EditTab.DRAW -> {
                                            if (tool == DrawTool.ERASER) {
                                                val hit = marks.filterIsInstance<StrokeMark>().lastOrNull { MarkupRenderer.distance(dw, dh, it, o.x - left, o.y - top) < 28f }
                                                if (hit != null) commit { marks.remove(hit) }
                                            } else live = StrokeMark(System.nanoTime(), tool, listOf(norm(o)), ink,
                                                if (tool == DrawTool.HIGHLIGHTER) thickness * 3.2f else thickness, if (tool == DrawTool.HIGHLIGHTER) opacity * 0.45f else opacity, dashed)
                                        }
                                        EditTab.TEXT -> hitText(o)?.let { t -> dragging = t; selectedText = t.id; dragStart = o; origin = PointF(t.x, t.y); undo += marks.toList(); redo.clear() }
                                        else -> {}
                                    }
                                },
                                onDrag = { change, _ ->
                                    val o = change.position
                                    when (tab) {
                                        EditTab.DRAW -> {
                                            if (tool == DrawTool.ERASER) {
                                                val hit = marks.filterIsInstance<StrokeMark>().lastOrNull { MarkupRenderer.distance(dw, dh, it, o.x - left, o.y - top) < 28f }
                                                if (hit != null) commit { marks.remove(hit) }
                                            } else live = live?.let { l -> if (tool.isShape) l.copy(points = listOf(l.points.first(), norm(o))) else l.copy(points = l.points + norm(o)) }
                                        }
                                        EditTab.TEXT -> dragging?.let { t ->
                                            val nx = (origin.x + (o.x - dragStart.x) / dw).coerceIn(0f, 1f); val ny = (origin.y + (o.y - dragStart.y) / dh).coerceIn(0f, 1f)
                                            val idx = marks.indexOfFirst { it.id == t.id }
                                            if (idx >= 0) marks[idx] = (marks[idx] as TextMark).copy(x = nx, y = ny)
                                        }
                                        else -> {}
                                    }
                                },
                                onDragEnd = {
                                    live?.let { l -> if (l.points.size > 1 || !l.tool.isShape) commit { marks += l } }
                                    live = null; dragging = null
                                },
                                onDragCancel = { live = null; dragging = null },
                            )
                        }
                        .pointerInput(tab, bmp) {
                            detectTapGestures(
                                onTap = { o ->
                                    if (tab == EditTab.TEXT) selectedText = hitText(o)?.id
                                    else if (tab == EditTab.DRAW && tool == DrawTool.PEN) commit { marks += StrokeMark(System.nanoTime(), tool, listOf(norm(o)), ink, thickness, opacity) }
                                },
                                onDoubleTap = { o -> if (tab == EditTab.TEXT) hitText(o)?.let { editText = it } },
                            )
                        },
                ) {
                    drawIntoCanvas { c ->
                        val nc = c.nativeCanvas
                        nc.save(); nc.translate(left, top)
                        if (mask != null && cutoutBg == CutoutBackground.Transparent) nc.drawRect(0f, 0f, dw, dh, android.graphics.Paint().apply {
                            shader = android.graphics.BitmapShader(checker, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
                        })
                        nc.drawBitmap(bmp, null, RectF(0f, 0f, dw, dh), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                        nc.clipRect(0f, 0f, dw, dh)
                        MarkupRenderer.draw(nc, dw, dh, marks.toList(), if (watermarkOn) watermark else null, live, if (tab == EditTab.TEXT) selectedText else null)
                        nc.restore()
                    }
                }
                if (cutoutBusy) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.7f)).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(); Spacer(Modifier.height(10.dp)); Text("Finding the subject…", color = Color.White)
                    }
                }
            }

            // ---------------- tool panel
            Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                when (tab) {
                    EditTab.DRAW -> {
                        Chips(DrawTool.entries.map { it to it.label }, tool, icons = { toolIcon(it) }) { tool = it }
                        Swatches(ink) { ink = it }
                        LabeledSlider("Thickness", thickness, 0.002f..0.04f) { thickness = it }
                        LabeledSlider("Opacity", opacity, 0.1f..1f) { opacity = it }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Dashed line", color = Color.White, modifier = Modifier.weight(1f)); Switch(dashed, { dashed = it })
                        }
                        Row {
                            TextButton(onClick = { if (marks.any { it is StrokeMark }) commit { marks.removeAll { it is StrokeMark } } }) { Text("Clear drawing", color = Color.White) }
                        }
                    }
                    EditTab.TEXT -> {
                        val sel = marks.firstOrNull { it.id == selectedText } as? TextMark
                        fun update(f: (TextMark) -> TextMark) {
                            val i = marks.indexOfFirst { it.id == selectedText }; if (i >= 0) commit { marks[i] = f(marks[i] as TextMark) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PanelButton(Icons.Default.Add, "Add text") { newText = true }
                            if (sel != null) {
                                PanelButton(Icons.Default.Edit, "Edit") { editText = sel }
                                PanelButton(Icons.Default.Delete, "Delete") { commit { marks.removeAll { it.id == sel.id } }; selectedText = null }
                            }
                        }
                        if (sel == null) Text("Tap “Add text”, then drag text to move it. Tap text to select it, double-tap to edit.", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
                        else {
                            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                StyleToggle("B", sel.bold, weight = FontWeight.Bold) { update { it.copy(bold = !it.bold) } }
                                StyleToggle("I", sel.italic, style = FontStyle.Italic) { update { it.copy(italic = !it.italic) } }
                                StyleToggle("U", sel.underline, decoration = TextDecoration.Underline) { update { it.copy(underline = !it.underline) } }
                                StyleToggle("Cursive", sel.cursive, family = FontFamily.Cursive) { update { it.copy(cursive = !it.cursive) } }
                                StyleToggle("Box", sel.background) { update { it.copy(background = !it.background) } }
                                StyleToggle("Outline", sel.outline) { update { it.copy(outline = !it.outline) } }
                            }
                            Chips(Fonts.families, if (sel.cursive) "" else sel.font) { f -> update { it.copy(font = f, cursive = false) } }
                            Swatches(sel.color) { c -> update { it.copy(color = c) } }
                            LabeledSlider("Size", sel.size, 0.02f..0.2f) { v -> val i = marks.indexOfFirst { it.id == sel.id }; if (i >= 0) marks[i] = sel.copy(size = v) }
                        }
                    }
                    EditTab.LOOKS -> {
                        Chips(PhotoFilter.entries.map { it to it.label }, filter) { filter = it }
                        Text("Looks are applied at full resolution when you save. The 1970s look adds grain, a light leak and the date imprint.",
                            color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
                    }
                    EditTab.WATERMARK -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Watermark", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Switch(watermarkOn, { watermarkOn = it })
                        }
                        OutlinedTextField(watermark.text, { watermark = watermark.copy(text = it); watermarkOn = true }, label = { Text("Text") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                        Chips(listOf("TL" to "Top left", "TR" to "Top right", "C" to "Center", "BL" to "Bottom left", "BR" to "Bottom right", "TILE" to "Tiled"), watermark.position) { watermark = watermark.copy(position = it); watermarkOn = true }
                        Chips(Fonts.families + ("cursive" to "Cursive"), watermark.font) { watermark = watermark.copy(font = it) }
                        Swatches(watermark.color) { watermark = watermark.copy(color = it) }
                        LabeledSlider("Size", watermark.size, 0.015f..0.12f) { watermark = watermark.copy(size = it) }
                        LabeledSlider("Opacity", watermark.opacity, 0.1f..1f) { watermark = watermark.copy(opacity = it) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PanelButton(Icons.Default.Image, if (watermark.logo == null) "Add logo" else "Change logo") { logoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                            if (watermark.logo != null) PanelButton(Icons.Default.HideImage, "Remove logo") { watermark = watermark.copy(logo = null) }
                        }
                        if (watermark.logo != null) LabeledSlider("Logo size", watermark.logoScale, 0.05f..0.5f) { watermark = watermark.copy(logoScale = it) }
                    }
                    EditTab.CUTOUT -> {
                        if (mask == null) {
                            PanelButton(Icons.Default.AutoFixHigh, "Remove background") {
                                val o = original ?: return@PanelButton
                                cutoutBusy = true
                                scope.launch {
                                    val r = Cutout.mask(ctx, o)
                                    cutoutBusy = false
                                    r.onSuccess { mask = it }.onFailure { shell.toast(it.message ?: "Background removal failed") }
                                }
                            }
                            Text("On-device subject detection — people, pets, products and objects. Nothing is uploaded.", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
                        } else {
                            Text("Background", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                StyleToggle("Transparent", cutoutBg == CutoutBackground.Transparent) { cutoutBg = CutoutBackground.Transparent }
                                StyleToggle("Blur", cutoutBg == CutoutBackground.Blur) { cutoutBg = CutoutBackground.Blur }
                                StyleToggle("White", (cutoutBg as? CutoutBackground.Solid)?.color == 0xFFFFFFFF.toInt()) { cutoutBg = CutoutBackground.Solid(0xFFFFFFFF.toInt()) }
                                StyleToggle("Black", (cutoutBg as? CutoutBackground.Solid)?.color == 0xFF000000.toInt()) { cutoutBg = CutoutBackground.Solid(0xFF000000.toInt()) }
                            }
                            Swatches((cutoutBg as? CutoutBackground.Solid)?.color ?: 0) { cutoutBg = CutoutBackground.Solid(it) }
                            TextButton(onClick = { mask = null }) { Text("Restore original background", color = Color.White) }
                            Text(if (cutoutBg == CutoutBackground.Transparent) "Saved as a transparent PNG." else "Saved as JPEG.", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                        }
                    }
                }
            }
            // ---------------- tabs
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                EditTab.entries.forEach { t ->
                    val sel = t == tab
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { tab = t }
                        .background(if (sel) Color.White.copy(alpha = 0.12f) else Color.Transparent).padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Icon(t.icon, t.label, tint = if (sel) Color(0xFFFFD54F) else Color.White)
                        Text(t.label, color = if (sel) Color(0xFFFFD54F) else Color.White, fontSize = 11.sp)
                    }
                }
            }
        }
        if (saving) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Spacer(Modifier.height(12.dp)); Text("Saving at full resolution…", color = Color.White) }
        }
    }

    if (newText || editText != null) {
        var value by remember(editText) { mutableStateOf(editText?.text ?: "") }
        AlertDialog(
            onDismissRequest = { newText = false; editText = null },
            title = { Text(if (editText != null) "Edit text" else "Add text") },
            text = {
                OutlinedTextField(value, { value = it }, minLines = 2, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            },
            confirmButton = {
                TextButton(enabled = value.isNotBlank(), onClick = {
                    val e = editText
                    if (e != null) { val i = marks.indexOfFirst { it.id == e.id }; if (i >= 0) commit { marks[i] = e.copy(text = value) } }
                    else {
                        val m = TextMark(System.nanoTime(), value, 0.5f, 0.5f, color = ink.takeIf { tab != EditTab.TEXT } ?: 0xFFFFFFFF.toInt())
                        commit { marks += m }; selectedText = m.id
                    }
                    newText = false; editText = null
                }) { Text("Done") }
            },
            dismissButton = { TextButton(onClick = { newText = false; editText = null }) { Text("Cancel") } },
        )
    }
    if (confirmExit) ConfirmDialog("Discard edits?", "Your changes to this photo haven't been saved.", confirmLabel = "Discard", onDismiss = { confirmExit = false }) { shell.back() }
}

/** Same pipeline for preview and export: look → background removal. */
private fun render(src: Bitmap, filter: PhotoFilter, vintageDate: Boolean, mask: SubjectMask?, bg: CutoutBackground): Bitmap {
    val looked = if (filter.isIdentity) src else FilterRenderer.apply(src, filter, filter.retroDate && vintageDate)
    return if (mask != null) Cutout.apply(looked, mask, bg) else looked.let { if (it.isMutable) it else it.copy(Bitmap.Config.ARGB_8888, true) }
}

private fun checkerboard(): Bitmap {
    val n = 24
    val px = IntArray(n * n) { i -> val x = (i % n) / 12; val y = (i / n) / 12; if ((x + y) % 2 == 0) 0xFF3A3D44.toInt() else 0xFF2A2C31.toInt() }
    return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
}

private fun toolIcon(t: DrawTool) = when (t) {
    DrawTool.PEN -> Icons.Default.Edit
    DrawTool.HIGHLIGHTER -> Icons.Default.BorderColor
    DrawTool.LINE -> Icons.Default.HorizontalRule
    DrawTool.ARROW -> Icons.Default.NorthEast
    DrawTool.RECT -> Icons.Default.CropSquare
    DrawTool.CIRCLE -> Icons.Default.RadioButtonUnchecked
    DrawTool.ERASER -> Icons.Default.AutoFixNormal
}

@Composable
private fun <T> Chips(items: List<Pair<T, String>>, selected: T, icons: ((T) -> androidx.compose.ui.graphics.vector.ImageVector)? = null, onPick: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (v, label) ->
            val sel = v == selected
            Row(Modifier.clip(RoundedCornerShape(50)).background(if (sel) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.12f)).clickable { onPick(v) }.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically) {
                icons?.let { Icon(it(v), null, tint = if (sel) Color.Black else Color.White, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
                Text(label, color = if (sel) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun Swatches(selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        InkColors.forEach { c ->
            Box(Modifier.size(30.dp).clip(CircleShape).background(Color(c)).border(if (c == selected) 3.dp else 1.dp, if (c == selected) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.3f), CircleShape).clickable { onPick(c) })
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.width(84.dp))
        Slider(value, onChange, valueRange = range, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StyleToggle(label: String, on: Boolean, weight: FontWeight = FontWeight.SemiBold, style: FontStyle = FontStyle.Normal, decoration: TextDecoration? = null, family: FontFamily? = null, onClick: () -> Unit) {
    Text(label, color = if (on) Color.Black else Color.White, fontSize = 15.sp, fontWeight = weight, fontStyle = style, textDecoration = decoration, fontFamily = family,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.12f)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp))
}

@Composable
private fun PanelButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.padding(end = 8.dp, top = 4.dp, bottom = 4.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.14f)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

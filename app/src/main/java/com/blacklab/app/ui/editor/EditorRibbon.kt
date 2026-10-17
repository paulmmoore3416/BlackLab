package com.blacklab.app.ui.editor

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blacklab.app.core.Time
import com.blacklab.app.docs.PageSetup
import com.blacklab.app.ui.components.DropdownField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class RibbonTab(val label: String) { HOME("Home"), INSERT("Insert"), LAYOUT("Layout"), REVIEW("Review"), TABLE("Table"), IMAGE("Picture") }

val EditorFonts = listOf(
    "Calibri", "Arial", "Helvetica", "Verdana", "Roboto", "Times New Roman", "Georgia", "Garamond",
    "Cambria", "Courier New", "Consolas", "Comic Sans MS", "Brush Script MT",
)
private val FontSizes = listOf(8f, 9f, 10f, 10.5f, 11f, 12f, 14f, 16f, 18f, 20f, 24f, 28f, 32f, 36f, 48f, 60f, 72f)
private val Styles = listOf("P" to "Normal", "TITLE" to "Title", "H1" to "Heading 1", "H2" to "Heading 2", "H3" to "Heading 3", "H4" to "Heading 4", "BLOCKQUOTE" to "Quote", "PRE" to "Code")
private val WordColors = listOf(
    "#000000", "#434343", "#666666", "#999999", "#B7B7B7", "#FFFFFF",
    "#C00000", "#FF0000", "#FFC000", "#FFFF00", "#92D050", "#00B050",
    "#00B0F0", "#0070C0", "#002060", "#7030A0", "#1F3864", "#2F5496",
    "#C55A11", "#BF8F00", "#538135", "#2E75B6", "#7F6000", "#833C0B",
)
private val Highlights = listOf("#FFFF00", "#00FF00", "#00FFFF", "#FF00FF", "#FFC000", "#FF9999", "#B4C7E7", "#C5E0B4", "#D9D9D9")
private val Symbols = listOf("©", "®", "™", "§", "¶", "†", "•", "°", "±", "×", "÷", "≈", "≠", "≤", "≥", "∞", "√", "µ", "Ω", "π", "∑", "€", "£", "¥", "¢", "←", "→", "↑", "↓", "↔", "✓", "✗", "★", "☆", "♥", "☐", "☑", "½", "¼", "¾", "…", "—", "–", "“", "”", "‘", "’")

@Composable
fun EditorRibbon(
    controller: EditorController,
    page: PageSetup,
    onPageChange: (PageSetup) -> Unit,
    modifier: Modifier = Modifier,
) {
    val st by controller.state.collectAsState()
    val imageSelected by controller.imageSelected.collectAsState()
    var tab by remember { mutableStateOf(RibbonTab.HOME) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var findOpen by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(st.table) { if (!st.table && tab == RibbonTab.TABLE) tab = RibbonTab.HOME }
    LaunchedEffect(imageSelected) { if (imageSelected) tab = RibbonTab.IMAGE else if (tab == RibbonTab.IMAGE) tab = RibbonTab.HOME }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val data = withContext(Dispatchers.IO) { EditorController.imageDataUri(ctx, uri) }
            if (data != null) controller.image(data)
        }
    }
    val dictate = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { controller.insertText("$it ") }
        }
    }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var speaking by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { tts?.stop(); tts?.shutdown() } }

    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Column {
            if (findOpen) FindReplaceBar(controller) { findOpen = false; controller.clearFind() }
            // tab strip
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 2.dp)) {
                val tabs = buildList {
                    addAll(listOf(RibbonTab.HOME, RibbonTab.INSERT, RibbonTab.LAYOUT, RibbonTab.REVIEW))
                    if (st.table) add(RibbonTab.TABLE)
                    if (imageSelected) add(RibbonTab.IMAGE)
                }
                tabs.forEach { t ->
                    val sel = t == tab
                    val contextual = t == RibbonTab.TABLE || t == RibbonTab.IMAGE
                    Text(
                        t.label,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { tab = t }
                            .background(if (sel) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        color = if (sel || contextual) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (tab) {
                    RibbonTab.HOME -> {
                        RB(Icons.AutoMirrored.Filled.Undo, "Undo") { controller.exec("undo") }
                        RB(Icons.AutoMirrored.Filled.Redo, "Redo") { controller.exec("redo") }
                        Sep()
                        MenuChip(Styles.firstOrNull { it.first == st.block }?.second ?: "Normal", 96) { close ->
                            Styles.forEach { (tag, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { controller.block(tag); close() }) }
                        }
                        MenuChip(st.family.ifBlank { "Calibri" }, 110) { close ->
                            EditorFonts.forEach { f -> DropdownMenuItem(text = { Text(f, fontFamily = previewFamily(f)) }, onClick = { controller.fontName(f); close() }) }
                        }
                        MenuChip(fmtSize(st.size), 52) { close ->
                            FontSizes.forEach { s -> DropdownMenuItem(text = { Text(fmtSize(s)) }, onClick = { controller.fontSize(s); close() }) }
                        }
                        RB(Icons.Default.TextIncrease, "Grow font") { controller.fontSize(FontSizes.firstOrNull { it > st.size } ?: 72f) }
                        RB(Icons.Default.TextDecrease, "Shrink font") { controller.fontSize(FontSizes.lastOrNull { it < st.size } ?: 8f) }
                        Sep()
                        RB(Icons.Default.FormatBold, "Bold", st.bold) { controller.exec("bold") }
                        RB(Icons.Default.FormatItalic, "Italic", st.italic) { controller.exec("italic") }
                        RB(Icons.Default.FormatUnderlined, "Underline", st.underline) { controller.exec("underline") }
                        RB(Icons.Default.FormatStrikethrough, "Strikethrough", st.strike) { controller.exec("strikeThrough") }
                        RB(Icons.Default.Superscript, "Superscript", st.sup) { controller.exec("superscript") }
                        RB(Icons.Default.Subscript, "Subscript", st.sub) { controller.exec("subscript") }
                        RB(Icons.Default.FormatColorText, "Text color") { dialog = "color" }
                        RB(Icons.Default.BorderColor, "Highlight") { dialog = "highlight" }
                        RB(Icons.Default.FormatClear, "Clear formatting") { controller.exec("removeFormat") }
                        Sep()
                        RB(Icons.AutoMirrored.Filled.FormatListBulleted, "Bullets", st.ul && !st.check) { controller.exec("insertUnorderedList") }
                        RB(Icons.Default.FormatListNumbered, "Numbering", st.ol) { controller.exec("insertOrderedList") }
                        RB(Icons.Default.Checklist, "Checklist", st.check) { controller.checklist() }
                        RB(Icons.AutoMirrored.Filled.FormatIndentDecrease, "Decrease indent") { controller.indent(-1) }
                        RB(Icons.AutoMirrored.Filled.FormatIndentIncrease, "Increase indent") { controller.indent(1) }
                        Sep()
                        RB(Icons.AutoMirrored.Filled.FormatAlignLeft, "Align left", st.align == "left") { controller.exec("justifyLeft") }
                        RB(Icons.Default.FormatAlignCenter, "Center", st.align == "center") { controller.exec("justifyCenter") }
                        RB(Icons.AutoMirrored.Filled.FormatAlignRight, "Align right", st.align == "right") { controller.exec("justifyRight") }
                        RB(Icons.Default.FormatAlignJustify, "Justify", st.align == "justify") { controller.exec("justifyFull") }
                        MenuChip("↕ " + (st.lineHeight.ifBlank { "1.15" }), 64) { close ->
                            listOf("1.0", "1.15", "1.5", "2.0", "2.5", "3.0").forEach { v -> DropdownMenuItem(text = { Text("Line spacing $v") }, onClick = { controller.lineHeight(v); close() }) }
                        }
                    }
                    RibbonTab.INSERT -> {
                        RL(Icons.Default.Image, "Picture") { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                        RL(Icons.Default.TableChart, "Table") { dialog = "table" }
                        RL(Icons.Default.Link, "Link") { dialog = "link" }
                        RL(Icons.Default.HorizontalRule, "Line") { controller.exec("insertHorizontalRule") }
                        RL(Icons.Default.InsertPageBreak, "Page break") { controller.pageBreak() }
                        RL(Icons.Default.Today, "Date") { controller.insertText(LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US))) }
                        RL(Icons.Default.Schedule, "Time") { controller.insertText(Time.time(LocalDateTime.now())) }
                        RL(Icons.Default.EmojiSymbols, "Symbol") { dialog = "symbol" }
                        RL(Icons.Default.FormatQuote, "Quote") { controller.block("BLOCKQUOTE") }
                        RL(Icons.Default.Code, "Code") { controller.block("PRE") }
                        RL(Icons.Default.CheckBox, "Checkbox") { controller.checklist() }
                    }
                    RibbonTab.LAYOUT -> {
                        MenuChip(PageSetup.sizes.first { it.first == page.size }.second.substringBefore(" ("), 80) { close ->
                            PageSetup.sizes.forEach { (k, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { onPageChange(page.copy(size = k)); close() }) }
                        }
                        RL(if (page.landscape) Icons.Default.StayCurrentLandscape else Icons.Default.StayCurrentPortrait, if (page.landscape) "Landscape" else "Portrait") {
                            onPageChange(page.copy(landscape = !page.landscape))
                        }
                        MenuChip("Margins ${page.marginMm}mm", 110) { close ->
                            listOf(13 to "Narrow (0.5 in)", 19 to "Moderate (0.75 in)", 25 to "Normal (1 in)", 32 to "Wide (1.25 in)", 51 to "Extra wide (2 in)").forEach { (mm, l) ->
                                DropdownMenuItem(text = { Text(l) }, onClick = { onPageChange(page.copy(marginMm = mm)); close() })
                            }
                        }
                        Sep()
                        RL(Icons.Default.ZoomOut, "Smaller") { zoom(controller, -10) }
                        RL(Icons.Default.ZoomIn, "Larger") { zoom(controller, 10) }
                        RL(Icons.Default.InsertPageBreak, "Page break") { controller.pageBreak() }
                    }
                    RibbonTab.REVIEW -> {
                        RL(Icons.Default.FindReplace, "Find") { findOpen = true }
                        RL(Icons.Default.Numbers, "Word count") { dialog = "count" }
                        RL(Icons.Default.KeyboardVoice, "Dictate") {
                            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Dictate into your document")
                            runCatching { dictate.launch(i) }
                        }
                        RL(if (speaking) Icons.Default.StopCircle else Icons.Default.RecordVoiceOver, if (speaking) "Stop" else "Read aloud") {
                            if (speaking) { tts?.stop(); speaking = false }
                            else scope.launch {
                                val text = controller.plainText()
                                if (text.isBlank()) return@launch
                                val engine = tts ?: TextToSpeech(ctx) { }.also { tts = it }
                                kotlinx.coroutines.delay(if (tts == engine) 400 else 0)
                                speaking = true
                                val max = TextToSpeech.getMaxSpeechInputLength() - 10
                                text.split(Regex("(?<=[.!?])\\s+")).fold(mutableListOf(StringBuilder())) { acc, s ->
                                    if (acc.last().length + s.length > max) acc.add(StringBuilder()); acc.last().append(s).append(' '); acc
                                }.forEachIndexed { i, chunk ->
                                    engine.speak(chunk.toString(), if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "bl$i")
                                }
                            }
                        }
                        RL(Icons.Default.Link, if (st.link.isNotBlank()) "Edit link" else "Link") { dialog = "link" }
                        if (st.link.isNotBlank()) RL(Icons.Default.LinkOff, "Remove link") { controller.unlink() }
                    }
                    RibbonTab.TABLE -> {
                        RL(Icons.Default.VerticalAlignTop, "Row above") { controller.tableOp("rowAbove") }
                        RL(Icons.Default.VerticalAlignBottom, "Row below") { controller.tableOp("rowBelow") }
                        RL(Icons.Default.KeyboardDoubleArrowLeft, "Col left") { controller.tableOp("colLeft") }
                        RL(Icons.Default.KeyboardDoubleArrowRight, "Col right") { controller.tableOp("colRight") }
                        RL(Icons.Default.Title, "Header row") { controller.tableOp("header") }
                        RL(Icons.Default.Remove, "Delete row") { controller.tableOp("delRow") }
                        RL(Icons.Default.ViewColumn, "Delete col") { controller.tableOp("delCol") }
                        RL(Icons.Default.Delete, "Delete table") { controller.tableOp("delTable") }
                    }
                    RibbonTab.IMAGE -> {
                        listOf("25%", "50%", "75%", "100%").forEach { w -> RL(Icons.Default.PhotoSizeSelectLarge, w) { controller.imageOp("width", w) } }
                        Sep()
                        RL(Icons.AutoMirrored.Filled.FormatAlignLeft, "Left") { controller.imageOp("align", "left") }
                        RL(Icons.Default.FormatAlignCenter, "Center") { controller.imageOp("align", "center") }
                        RL(Icons.AutoMirrored.Filled.FormatAlignRight, "Right") { controller.imageOp("align", "right") }
                        RL(Icons.Default.Delete, "Delete") { controller.imageOp("delete") }
                    }
                }
            }
        }
    }

    when (dialog) {
        "color" -> ColorDialog("Font color", WordColors, auto = "Automatic", onDismiss = { dialog = null }) { c -> controller.exec("foreColor", c ?: "#1b1b1f") }
        "highlight" -> ColorDialog("Highlight", Highlights, auto = "No color", onDismiss = { dialog = null }) { c -> controller.exec("hiliteColor", c ?: "transparent") }
        "table" -> TableDialog(onDismiss = { dialog = null }) { r, c, h -> controller.table(r, c, h) }
        "link" -> LinkDialog(st.link, onDismiss = { dialog = null }) { url, text -> controller.link(url, text) }
        "symbol" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Insert symbol") },
            text = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Symbols.forEach { s ->
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { controller.insertText(s); dialog = null }, contentAlignment = Alignment.Center) { Text(s, fontSize = 18.sp) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Close") } },
        )
        "count" -> {
            val words by controller.words.collectAsState()
            val chars by controller.chars.collectAsState()
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Word count") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatLine("Words", "%,d".format(words))
                        StatLine("Characters (no spaces)", "%,d".format(chars))
                        StatLine("Reading time", "${(words / 230).coerceAtLeast(if (words > 0) 1 else 0)} min")
                        StatLine("Speaking time", "${(words / 130).coerceAtLeast(if (words > 0) 1 else 0)} min")
                        StatLine("Approx. pages", String.format(Locale.US, "%.1f", words / 500.0))
                    }
                },
                confirmButton = { TextButton(onClick = { dialog = null }) { Text("Done") } },
            )
        }
    }
}

private fun zoom(c: EditorController, delta: Int) {
    val z = (c.textZoom.value + delta).coerceIn(60, 220)
    c.textZoom.value = z
    c.webView?.let { wv -> wv.post { wv.settings.textZoom = z } }
}

private fun fmtSize(s: Float) = if (s % 1f == 0f) s.toInt().toString() else s.toString()

private fun previewFamily(f: String): FontFamily = when {
    f.contains("Times") || f == "Georgia" || f == "Garamond" || f == "Cambria" -> FontFamily.Serif
    f.contains("Courier") || f == "Consolas" -> FontFamily.Monospace
    f.contains("Script") || f.contains("Comic") -> FontFamily.Cursive
    else -> FontFamily.SansSerif
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RB(icon: ImageVector, desc: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.padding(horizontal = 1.dp).size(40.dp).clip(RoundedCornerShape(10.dp))
            .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun RL(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.padding(horizontal = 2.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun Sep() {
    Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(26.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

@Composable
private fun MenuChip(label: String, minWidth: Int, items: @Composable (close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(horizontal = 2.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                .clickable { open = true }.padding(horizontal = 8.dp, vertical = 7.dp).width(minWidth.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(open, { open = false }) { items { open = false } }
    }
}

@Composable
private fun ColorDialog(title: String, colors: List<String>, auto: String, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    colors.forEach { hex ->
                        val c = Color(android.graphics.Color.parseColor(hex))
                        Box(Modifier.size(36.dp).clip(CircleShape).background(c).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            .clickable { onPick(hex); onDismiss() })
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { onPick(null); onDismiss() }) { Text(auto) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TableDialog(onDismiss: () -> Unit, onInsert: (Int, Int, Boolean) -> Unit) {
    var rows by remember { mutableIntStateOf(3) }
    var cols by remember { mutableIntStateOf(3) }
    var header by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Insert table") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Stepper("Rows", rows, 1..30) { rows = it }
                Stepper("Columns", cols, 1..10) { cols = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(header, { header = it }); Text("Header row")
                }
                // live grid preview
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    repeat(rows.coerceAtMost(8)) { r ->
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            repeat(cols) {
                                Box(Modifier.size(18.dp).background(if (header && r == 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainerHighest))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { FilledTonalButton(onClick = { onInsert(rows, cols, header); onDismiss() }) { Text("Insert") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun Stepper(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        IconButton(onClick = { onChange((value - 1).coerceIn(range)) }) { Icon(Icons.Default.Remove, "Less") }
        Text("$value", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(32.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onClick = { onChange((value + 1).coerceIn(range)) }) { Icon(Icons.Default.Add, "More") }
    }
}

@Composable
private fun LinkDialog(existing: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var url by remember { mutableStateOf(existing) }
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing.isBlank()) "Insert link" else "Edit link") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(url, { url = it }, label = { Text("Address (URL, email, phone)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(text, { text = it }, label = { Text("Text to display (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = url.isNotBlank(), onClick = {
                val u = url.trim().let { if (it.contains("@") && !it.contains("://") && !it.startsWith("mailto:")) "mailto:$it" else if (!it.contains(":")) "https://$it" else it }
                onSave(u, text.trim()); onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FindReplaceBar(controller: EditorController, onClose: () -> Unit) {
    var term by remember { mutableStateOf("") }
    var rep by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }
    var showReplace by remember { mutableStateOf(false) }
    val result by controller.findResult.collectAsState()
    LaunchedEffect(term, matchCase) { kotlinx.coroutines.delay(200); controller.find(term, matchCase) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(term, { term = it }, placeholder = { Text("Find") }, singleLine = true, modifier = Modifier.weight(1f),
                trailingIcon = { Text(if (result.first == 0) (if (term.isBlank()) "" else "0") else "${result.second + 1}/${result.first}", style = MaterialTheme.typography.labelMedium) })
            IconButton(onClick = { controller.findStep(-1) }) { Icon(Icons.Default.KeyboardArrowUp, "Previous") }
            IconButton(onClick = { controller.findStep(1) }) { Icon(Icons.Default.KeyboardArrowDown, "Next") }
            IconButton(onClick = { showReplace = !showReplace }) { Icon(Icons.Default.FindReplace, "Replace") }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
        }
        if (showReplace) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(rep, { rep = it }, placeholder = { Text("Replace with") }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = { controller.replaceCurrent(rep, term, matchCase) }) { Text("Replace") }
                TextButton(onClick = { controller.replaceAll(term, rep, matchCase) }) { Text("All") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(matchCase, { matchCase = it }); Text("Match case", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

package com.blacklab.app.ui.brainstorm

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.blacklab.app.AppContainer
import com.blacklab.app.brainstorm.Brainstorm
import com.blacklab.app.brainstorm.LockedException
import com.blacklab.app.brainstorm.PageTemplates
import com.blacklab.app.brainstorm.SectionKeys
import com.blacklab.app.brainstorm.Tags
import com.blacklab.app.core.Files
import com.blacklab.app.core.Profile
import com.blacklab.app.core.Time
import com.blacklab.app.data.PageEntity
import com.blacklab.app.data.SectionEntity
import com.blacklab.app.docs.ExportFormat
import com.blacklab.app.ui.components.ActionSheet
import com.blacklab.app.ui.components.ConfirmDialog
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.Ocr
import com.blacklab.app.ui.components.SheetAction
import com.blacklab.app.ui.components.TextInputDialog
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.components.rememberDocumentScanner
import com.blacklab.app.ui.editor.ColorDialog
import com.blacklab.app.ui.editor.Highlights
import com.blacklab.app.ui.editor.LinkDialog
import com.blacklab.app.ui.editor.MenuChip
import com.blacklab.app.ui.editor.RB
import com.blacklab.app.ui.editor.RL
import com.blacklab.app.ui.editor.Sep
import com.blacklab.app.ui.editor.TableDialog
import com.blacklab.app.ui.editor.WordColors
import com.blacklab.app.ui.theme.LocalBlColors
import com.blacklab.app.ui.viewer.BusyOverlay
import com.blacklab.app.ui.viewer.openInApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.File
import javax.crypto.SecretKey
import kotlin.coroutines.resume

// ====================================================================== bridge

data class PageUiState(
    val focus: String = "none", val item: String = "", val bold: Boolean = false, val italic: Boolean = false, val underline: Boolean = false,
    val strike: Boolean = false, val ul: Boolean = false, val ol: Boolean = false, val check: Boolean = false, val table: Boolean = false,
    val block: String = "P", val tag: String = "", val link: String = "", val align: String = "left", val sel: Int = 0,
    val undo: Boolean = false, val redo: Boolean = false, val zoom: Int = 100, val mode: String = "type",
)

/** Kotlin side of the canvas engine in assets/notebook/page.html. */
class PageController {
    var webView: WebView? = null
    val state = MutableStateFlow(PageUiState())
    val ready = MutableStateFlow(false)
    val title = MutableStateFlow("")
    val find = MutableStateFlow(0 to -1)
    @Volatile var initial: String = ""
    @Volatile var latest: String? = null
    @Volatile var lastSaved: String = ""
    val saves = Channel<Unit>(Channel.CONFLATED)
    var onItem: (String, String) -> Unit = { _, _ -> }
    var onLink: (String) -> Unit = {}
    var onPaste: (String) -> Unit = {}

    fun js(script: String) { val wv = webView ?: return; wv.post { webView?.evaluateJavascript(script, null) } }
    fun call(fn: String, vararg args: Any?) = js("bl.$fn(" + args.joinToString(",") { a -> when (a) { null -> "undefined"; is String -> q(a); else -> a.toString() } } + ")")

    suspend fun eval(script: String): String? = suspendCancellableCoroutine { cont ->
        val wv = webView
        if (wv == null || !ready.value) { cont.resume(null); return@suspendCancellableCoroutine }
        wv.post {
            val live = webView ?: run { if (cont.isActive) cont.resume(null); return@post }
            live.evaluateJavascript(script) { r -> if (cont.isActive) cont.resume(runCatching { JSONTokener(r).nextValue() as? String }.getOrNull()) }
        }
    }

    companion object { fun q(s: String): String = JSONObject.quote(s) }
}

class PageBridge(private val c: PageController) {
    @JavascriptInterface fun getInitial(): String = c.initial
    @JavascriptInterface fun assetBase(): String = "https://bl.local/asset/"
    @JavascriptInterface fun onReady() { c.ready.value = true }
    @JavascriptInterface fun onChange() {}
    @JavascriptInterface fun onSave(json: String) { if (json != c.lastSaved) { c.latest = json; c.saves.trySend(Unit) } }
    @JavascriptInterface fun onTitle(t: String) { c.title.value = t }
    @JavascriptInterface fun onItem(kind: String, id: String) { c.webView?.post { c.onItem(kind, id) } }
    @JavascriptInterface fun onOpenLink(href: String) { c.webView?.post { c.onLink(href) } }
    @JavascriptInterface fun onFind(count: Int, index: Int) { c.find.value = count to index }
    @JavascriptInterface fun onPasteImage(dataUri: String) { c.webView?.post { c.onPaste(dataUri) } }
    @JavascriptInterface fun onState(json: String) {
        runCatching {
            val o = JSONObject(json)
            c.state.value = PageUiState(
                o.optString("focus"), o.optString("item"), o.optBoolean("bold"), o.optBoolean("italic"), o.optBoolean("underline"), o.optBoolean("strike"),
                o.optBoolean("ul"), o.optBoolean("ol"), o.optBoolean("check"), o.optBoolean("table"), o.optString("block", "P"), o.optString("tag"),
                o.optString("link"), o.optString("align", "left"), o.optInt("sel"), o.optBoolean("undo"), o.optBoolean("redo"), o.optInt("zoom", 100), o.optString("mode", "type"),
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PageCanvas(controller: PageController, asset: (String) -> ByteArray?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    AndroidView(
        modifier = modifier,
        factory = { c ->
            WebView(c).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false
                setBackgroundColor(0xFF202227.toInt())
                overScrollMode = WebView.OVER_SCROLL_NEVER
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                        val u = request.url
                        if (u.host == "bl.local") {
                            val path = u.path.orEmpty()
                            if (path == "/page.html") return WebResourceResponse("text/html", "utf-8", ctx.assets.open("notebook/page.html"))
                            if (path.startsWith("/asset/")) {
                                val name = Uri.decode(path.removePrefix("/asset/"))
                                val bytes = asset(name)
                                if (bytes != null) return WebResourceResponse(Files.mime(File(name)), null, ByteArrayInputStream(bytes))
                            }
                        }
                        // Pages never reach the network (privacy): pasted remote images simply don't load.
                        return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    }
                }
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                        if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR || m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.WARNING || com.blacklab.app.BuildConfig.DEBUG)
                            android.util.Log.w("BlackLabPage", "${m.message()} (${m.lineNumber()})")
                        return true
                    }
                }
                addJavascriptInterface(PageBridge(controller), "Android")
                controller.webView = this
                loadUrl("https://bl.local/page.html")
            }
        },
        onRelease = { wv ->
            if (controller.webView === wv) controller.webView = null
            controller.ready.value = false
            wv.removeJavascriptInterface("Android")
            wv.destroy()
        },
    )
}

// ====================================================================== screen

private enum class PTab(val label: String) { HOME("Home"), INSERT("Insert"), DRAW("Draw"), VIEW("View"), REVIEW("Review") }

private val PenColors = listOf("#1b1b1f", "#1e5bd8", "#d93025", "#188038", "#7b1fa2", "#e8710a", "#00897b", "#ffffff")
private val HlColors = listOf("#fff176", "#a5f3a1", "#ffb3d1", "#9fd3ff", "#ffcc80")
private val PaperColors = listOf("#ffffff" to "White", "#fbf6e9" to "Cream", "#e8f0fe" to "Blue", "#e6f4ea" to "Green", "#fce8ef" to "Pink", "#f1f3f4" to "Gray", "#1e1f24" to "Dark")
private val Rules = listOf("none" to "No lines", "narrow" to "Narrow ruled", "college" to "College ruled", "wide" to "Wide ruled", "smallgrid" to "Small grid", "grid" to "Grid", "largegrid" to "Large grid", "dots" to "Dot grid")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageEditorScreen(pageId: Long) {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = app.db.brainstorm()
    val page by dao.observePage(pageId).collectAsState(initial = null)
    var section by remember { mutableStateOf<SectionEntity?>(null) }
    var notebookName by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var needUnlock by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf<SecretKey?>(null) }
    val controller = remember(pageId) { PageController() }
    val st by controller.state.collectAsState()
    val title by controller.title.collectAsState()
    val ready by controller.ready.collectAsState()

    var tab by remember { mutableStateOf(PTab.HOME) }
    var busy by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var findOpen by remember { mutableStateOf(false) }
    var aiText by remember { mutableStateOf<Pair<String, String>?>(null) }
    var actions by remember { mutableStateOf<List<String>?>(null) }
    var ideas by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    var drawTool by remember { mutableStateOf("pen") }
    var penColor by remember { mutableStateOf("#1b1b1f") }
    var hlColor by remember { mutableStateOf("#fff176") }
    var penWidth by remember { mutableFloatStateOf(2.5f) }
    var penOnly by remember { mutableStateOf(false) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var speaking by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { tts?.stop(); tts?.shutdown() } }

    // ---- load
    LaunchedEffect(pageId) {
        val p = dao.page(pageId) ?: run { shell.toast("That page no longer exists"); shell.back(); return@LaunchedEffect }
        val s = dao.section(p.sectionId)
        section = s
        notebookName = s?.let { dao.notebook(it.notebookId)?.name }.orEmpty()
        if (s?.locked == true && SectionKeys.get(s.id) == null) { needUnlock = true; return@LaunchedEffect }
        key = s?.let { SectionKeys.get(it.id) }
        controller.initial = runCatching { Brainstorm.read(app, p) }.getOrElse { if (it is LockedException) { needUnlock = true; return@LaunchedEffect } else PageTemplates.build("blank", p.title) }
        controller.lastSaved = controller.initial
        loaded = true
    }

    // ---- save loop (latest canvas wins; serialized on Brainstorm's writer)
    suspend fun persist(json: String) {
        val s = section
        if (s?.locked == true) key?.let { SectionKeys.put(s.id, it) }
        runCatching { Brainstorm.save(app, pageId, json); controller.lastSaved = json }.onFailure { shell.toast("Couldn't save: ${it.message}") }
    }
    LaunchedEffect(controller) {
        for (u in controller.saves) { val j = controller.latest ?: continue; controller.latest = null; persist(j) }
    }
    suspend fun flush() {
        val j = controller.eval("bl.getJson()") ?: controller.latest
        controller.latest = null
        if (j != null && j != controller.lastSaved) withContext(kotlinx.coroutines.NonCancellable) { persist(j) }
    }
    fun leave() { scope.launch { flush(); shell.back() } }
    BackHandler { if (findOpen) { findOpen = false; controller.call("clearFind") } else leave() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) app.appScope.launch(Dispatchers.Main) { flush() } }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); controller.latest?.let { j -> app.appScope.launch { persist(j) } } }
    }

    // ---- insert helpers
    suspend fun insertImage(uri: Uri) {
        val p = page ?: return
        busy = "Adding picture…"
        val res = withContext(Dispatchers.IO) {
            runCatching {
                val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val s = 2000f / maxOf(info.size.width, info.size.height)
                    if (s < 1f) d.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
                }
                val png = bmp.hasAlpha()
                val ref = Brainstorm.addAsset(app, p, if (png) "png" else "jpg") { f -> f.outputStream().use { bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 88, it) } }
                Triple(ref, bmp.width, bmp.height)
            }
        }
        busy = null
        res.onFailure { shell.toast("Couldn't add that picture") }.onSuccess { (ref, w, h) ->
            val id = controller.eval("bl.addImage(${PageController.q(ref)}, $w, $h, '')")
            // text inside pictures becomes searchable (OneNote-style), done in the background
            scope.launch {
                val text = runCatching { Ocr.recognize(ctx, uri) }.getOrDefault("")
                if (text.isNotBlank() && id != null) controller.call("updateItem", id, JSONObject().put("ocr", text).toString())
            }
        }
    }

    suspend fun insertFile(uri: Uri) {
        val p = page ?: return
        busy = "Attaching file…"
        val name = Files.displayName(ctx, uri)
        val res = runCatching {
            val ref = Brainstorm.addAssetFromUri(app, p, uri, name.substringAfterLast('.', "bin"))
            val size = withContext(Dispatchers.IO) { ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L }
            JSONObject().put("t", "file").put("name", name).put("path", ref).put("size", size).put("w", 260)
        }
        busy = null
        res.onSuccess { controller.call("addFile", it.toString()) }.onFailure { shell.toast("Couldn't attach: ${it.message}") }
    }

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris -> scope.launch { uris.forEach { insertImage(it) } } }
    val camFile = remember { File(ctx.cacheDir, "page_cam.jpg") }
    val camUri = remember { FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", camFile) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) scope.launch { insertImage(camUri) } }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) scope.launch { insertFile(u) } }
    val dictate = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { controller.call("insertText", "$it ") }
    }
    val profile = page?.let { Profile.from(it.profile) } ?: app.settings.current.activeProfile
    val scan = rememberDocumentScanner(app, profile) { r -> scope.launch { r.pages.forEach { insertImage(Uri.fromFile(it)) } } }

    controller.onPaste = { data ->
        scope.launch {
            val p = page ?: return@launch
            val bytes = android.util.Base64.decode(data.substringAfter(","), android.util.Base64.DEFAULT)
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@launch
            val ref = Brainstorm.addAsset(app, p, "png") { f -> f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            controller.call("addImage", ref, bmp.width, bmp.height, "")
        }
    }
    controller.onLink = { href -> openLink(shell, ctx, href) }
    var selectedItem by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(st.item) { selectedItem = if (st.item.isNotEmpty()) controller.eval("bl.item(${PageController.q(st.item)})")?.let { runCatching { JSONObject(it) }.getOrNull() } else null }
    suspend fun openItem(o: JSONObject) {
        val p = page ?: return
        val f = Brainstorm.assetFile(app, p, o.optString("path").ifBlank { o.optString("src") }, o.optString("name").ifBlank { null }) ?: run { shell.toast("File missing"); return }
        openInApp(shell, app, f)
    }
    controller.onItem = { kind, id -> if (kind == "open") scope.launch { controller.eval("bl.item(${PageController.q(id)})")?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { openItem(it) } } }

    if (needUnlock) {
        val s = section
        PasswordDialog("Unlock “${s?.name ?: "section"}”", "This section is password protected.", hint = s?.lockHint.orEmpty(),
            onDismiss = { shell.back() }) { pw, _ ->
            scope.launch {
                if (s != null && Brainstorm.unlockSection(s, pw)) {
                    needUnlock = false; key = SectionKeys.get(s.id)
                    val p = dao.page(pageId) ?: return@launch
                    controller.initial = Brainstorm.read(app, p); controller.lastSaved = controller.initial; loaded = true
                } else shell.toast("Wrong password")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title.ifBlank { "Untitled page" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(listOf(notebookName, section?.name.orEmpty()).filter { it.isNotBlank() }.joinToString(" › "), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                },
                navigationIcon = { IconButton(onClick = { leave() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { controller.call("undo") }, enabled = st.undo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                    IconButton(onClick = { controller.call("redo") }, enabled = st.redo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
                    IconButton(onClick = {
                        if (st.mode == "draw") { controller.call("setMode", "type"); if (tab == PTab.DRAW) tab = PTab.HOME }
                        else { tab = PTab.DRAW; controller.call("setTool", toolJson(drawTool, penColor, hlColor, penWidth, penOnly)) }
                    }) { Icon(if (st.mode == "draw") Icons.Default.Keyboard else Icons.Default.Draw, if (st.mode == "draw") "Type" else "Draw", tint = if (st.mode == "draw") MaterialTheme.colorScheme.primary else LocalContentColor.current) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Share / export") }, leadingIcon = { Icon(Icons.Default.IosShare, null) }, onClick = { menu = false; dialog = "export" })
                            DropdownMenuItem(text = { Text("Live Share") }, leadingIcon = { Icon(Icons.Default.Wifi, null) }, onClick = { menu = false; scope.launch { flush() }; dialog = "live" })
                            DropdownMenuItem(text = { Text("Comments") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Comment, null) }, onClick = { menu = false; dialog = "comments" })
                            DropdownMenuItem(text = { Text("Page history") }, leadingIcon = { Icon(Icons.Default.History, null) }, onClick = { menu = false; scope.launch { flush(); dialog = "history" } })
                            DropdownMenuItem(text = { Text(if (page?.reminderAt != null) "Reminder · " + Time.dateTime(page!!.reminderAt!!) else "Remind me") }, leadingIcon = { Icon(Icons.Default.NotificationsActive, null) }, onClick = { menu = false; dialog = "reminder" })
                            DropdownMenuItem(text = { Text("Find on page") }, leadingIcon = { Icon(Icons.Default.Search, null) }, onClick = { menu = false; findOpen = true })
                            DropdownMenuItem(text = { Text("Move page…") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, null) }, onClick = { menu = false; dialog = "move" })
                            DropdownMenuItem(text = { Text("Copy page to…") }, leadingIcon = { Icon(Icons.Default.FileCopy, null) }, onClick = { menu = false; dialog = "copy" })
                            DropdownMenuItem(text = { Text("Page info") }, leadingIcon = { Icon(Icons.Default.Info, null) }, onClick = { menu = false; dialog = "info" })
                            DropdownMenuItem(text = { Text("Delete page", color = LocalBlColors.current.danger) }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = LocalBlColors.current.danger) }, onClick = { menu = false; dialog = "delete" })
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).consumeWindowInsets(pad).fillMaxSize()) {
            // ---------------- ribbon
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    if (findOpen) PageFindBar(controller) { findOpen = false; controller.call("clearFind") }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 2.dp)) {
                        PTab.entries.forEach { t ->
                            val sel = t == tab
                            Text(t.label, Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                                tab = t
                                if (t == PTab.DRAW) controller.call("setTool", toolJson(drawTool, penColor, hlColor, penWidth, penOnly)) else if (st.mode == "draw") controller.call("setMode", "type")
                            }.background(if (sel) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent).padding(horizontal = 12.dp, vertical = 6.dp),
                                color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        when (tab) {
                            PTab.HOME -> {
                                MenuChip(listOf("P" to "Normal", "H1" to "Heading 1", "H2" to "Heading 2", "H3" to "Heading 3", "BLOCKQUOTE" to "Quote", "PRE" to "Code").firstOrNull { it.first == st.block }?.second ?: "Normal", 96) { close ->
                                    listOf("P" to "Normal", "H1" to "Heading 1", "H2" to "Heading 2", "H3" to "Heading 3", "BLOCKQUOTE" to "Quote", "PRE" to "Code").forEach { (t, l) ->
                                        DropdownMenuItem(text = { Text(l) }, onClick = { controller.call("block", t); close() })
                                    }
                                }
                                MenuChip("Size", 52) { close -> listOf(9f, 10f, 11f, 12f, 14f, 16f, 18f, 24f, 32f).forEach { s -> DropdownMenuItem(text = { Text(s.toInt().toString()) }, onClick = { controller.call("fontSize", s); close() }) } }
                                RB(Icons.Default.FormatBold, "Bold", st.bold) { controller.call("exec", "bold") }
                                RB(Icons.Default.FormatItalic, "Italic", st.italic) { controller.call("exec", "italic") }
                                RB(Icons.Default.FormatUnderlined, "Underline", st.underline) { controller.call("exec", "underline") }
                                RB(Icons.Default.FormatStrikethrough, "Strikethrough", st.strike) { controller.call("exec", "strikeThrough") }
                                RB(Icons.Default.FormatColorText, "Font color") { dialog = "color" }
                                RB(Icons.Default.BorderColor, "Highlight") { dialog = "highlight" }
                                RB(Icons.Default.FormatClear, "Clear formatting") { controller.call("exec", "removeFormat") }
                                Sep()
                                RB(Icons.Default.CheckBox, "To Do tag", st.tag == "todo") { controller.call("tag", "todo") }
                                MenuChip(if (st.tag.isNotEmpty() && st.tag != "todo") Tags.of(st.tag).symbol + " " + Tags.of(st.tag).label else "Tag", 92) { close ->
                                    Tags.all.forEach { t -> DropdownMenuItem(text = { Text(t.symbol + "  " + t.label) }, onClick = { controller.call("tag", t.id); close() }) }
                                    HorizontalDivider()
                                    DropdownMenuItem(text = { Text("Remove tag") }, onClick = { controller.call("tag", ""); close() })
                                }
                                Sep()
                                RB(Icons.AutoMirrored.Filled.FormatListBulleted, "Bullets", st.ul) { controller.call("exec", "insertUnorderedList") }
                                RB(Icons.Default.FormatListNumbered, "Numbering", st.ol) { controller.call("exec", "insertOrderedList") }
                                RB(Icons.Default.Checklist, "Checklist", st.check) { controller.call("checklist") }
                                RB(Icons.AutoMirrored.Filled.FormatIndentDecrease, "Outdent") { controller.call("indent", -1) }
                                RB(Icons.AutoMirrored.Filled.FormatIndentIncrease, "Indent") { controller.call("indent", 1) }
                                Sep()
                                RB(Icons.AutoMirrored.Filled.FormatAlignLeft, "Align left", st.align == "left") { controller.call("exec", "justifyLeft") }
                                RB(Icons.Default.FormatAlignCenter, "Center", st.align == "center") { controller.call("exec", "justifyCenter") }
                                RB(Icons.AutoMirrored.Filled.FormatAlignRight, "Align right", st.align == "right") { controller.call("exec", "justifyRight") }
                            }
                            PTab.INSERT -> {
                                RL(Icons.Default.TextFields, "Text box") { controller.call("addText") }
                                RL(Icons.Default.TableChart, "Table") { dialog = "table" }
                                RL(Icons.Default.Image, "Pictures") { pickImages.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                                RL(Icons.Default.PhotoCamera, "Camera") { takePhoto.launch(camUri) }
                                RL(Icons.Default.DocumentScanner, "Scan") { scan() }
                                RL(Icons.Default.AttachFile, "File") { pickFile.launch(arrayOf("*/*")) }
                                RL(Icons.Default.Mic, "Recording") { dialog = "voice" }
                                RL(Icons.Default.RecordVoiceOver, "Dictate") {
                                    runCatching { dictate.launch(android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)) }
                                }
                                Sep()
                                RL(Icons.Default.Link, "Link") { dialog = "link" }
                                RL(Icons.Default.Description, "Page link") { dialog = "pagelink" }
                                RL(Icons.Default.Today, "Date") { controller.call("insertText", Time.longDate(Time.today()) + " ") }
                                RL(Icons.Default.AccessTime, "Time") { controller.call("insertText", Time.time(Time.now()) + " ") }
                                RL(Icons.Default.HorizontalRule, "Line") { controller.call("insertHtml", "<hr><p><br></p>") }
                                RL(Icons.Default.Calculate, "Math tip") { shell.toast("Type an expression ending in “=” then a space, e.g. 1250*1.08= ") }
                            }
                            PTab.DRAW -> {
                                fun apply() = controller.call("setTool", toolJson(drawTool, penColor, hlColor, penWidth, penOnly))
                                RB(Icons.Default.Keyboard, "Type", st.mode == "type") { controller.call("setMode", "type") }
                                RB(Icons.Default.Gesture, "Lasso select", st.mode == "draw" && drawTool == "lasso") { drawTool = "lasso"; apply() }
                                RB(Icons.Default.Create, "Pen", st.mode == "draw" && drawTool == "pen") { drawTool = "pen"; apply() }
                                RB(Icons.Default.BorderColor, "Highlighter", st.mode == "draw" && drawTool == "highlighter") { drawTool = "highlighter"; apply() }
                                RB(Icons.Default.AutoFixOff, "Eraser", st.mode == "draw" && drawTool == "eraser") { drawTool = "eraser"; apply() }
                                RB(Icons.Default.HorizontalRule, "Line", drawTool == "line" && st.mode == "draw") { drawTool = "line"; apply() }
                                RB(Icons.AutoMirrored.Filled.TrendingFlat, "Arrow", drawTool == "arrow" && st.mode == "draw") { drawTool = "arrow"; apply() }
                                RB(Icons.Default.CropSquare, "Rectangle", drawTool == "rect" && st.mode == "draw") { drawTool = "rect"; apply() }
                                RB(Icons.Default.RadioButtonUnchecked, "Ellipse", drawTool == "ellipse" && st.mode == "draw") { drawTool = "ellipse"; apply() }
                                Sep()
                                (if (drawTool == "highlighter") HlColors else PenColors).forEach { hex ->
                                    val c = Color(android.graphics.Color.parseColor(hex))
                                    val sel = (if (drawTool == "highlighter") hlColor else penColor) == hex
                                    Box(Modifier.padding(3.dp).size(28.dp).clip(CircleShape).background(c).border(if (sel) 3.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                                        .clickable {
                                            if (drawTool == "highlighter") hlColor = hex else penColor = hex
                                            if (st.sel > 0) controller.call("recolorSelection", hex)
                                            if (drawTool in listOf("eraser", "lasso")) drawTool = "pen"
                                            apply()
                                        })
                                }
                                Sep()
                                listOf(1.5f to "Fine", 2.5f to "Medium", 4f to "Bold", 7f to "Marker").forEach { (w, l) ->
                                    FilterChip(penWidth == w, { penWidth = w; apply() }, label = { Text(l) }, modifier = Modifier.padding(horizontal = 2.dp))
                                }
                                FilterChip(penOnly, { penOnly = !penOnly; apply() }, label = { Text("Stylus only") }, modifier = Modifier.padding(horizontal = 2.dp))
                            }
                            PTab.VIEW -> {
                                RL(Icons.Default.FormatColorFill, "Page color") { dialog = "paper" }
                                RL(Icons.Default.Reorder, "Rule lines") { dialog = "rules" }
                                Sep()
                                RB(Icons.Default.ZoomOut, "Zoom out") { controller.call("zoomBy", 0.8) }
                                Text("${st.zoom}%", style = MaterialTheme.typography.labelLarge, modifier = Modifier.clickable { controller.call("setZoom", 1) }.padding(horizontal = 6.dp))
                                RB(Icons.Default.ZoomIn, "Zoom in") { controller.call("zoomBy", 1.25) }
                                RL(Icons.Default.FitScreen, "Fit width") { controller.call("fitWidth") }
                                Sep()
                                RL(Icons.Default.Search, "Find") { findOpen = true }
                                RL(if (speaking) Icons.Default.StopCircle else Icons.AutoMirrored.Filled.VolumeUp, if (speaking) "Stop" else "Read aloud") {
                                    if (speaking) { tts?.stop(); speaking = false }
                                    else scope.launch {
                                        val text = controller.eval("bl.plainText()").orEmpty()
                                        if (text.isBlank()) return@launch
                                        val speak = { e: TextToSpeech -> speaking = true; text.chunked(3500).forEachIndexed { i, part -> e.speak(part, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "p$i") } }
                                        tts?.let(speak) ?: run { var e: TextToSpeech? = null; e = TextToSpeech(ctx) { s -> if (s == TextToSpeech.SUCCESS) e?.let(speak) }; tts = e }
                                    }
                                }
                            }
                            PTab.REVIEW -> {
                                RL(Icons.Default.Summarize, "Summarize") { scope.launch { busy = "Reading the page…"; aiText = Brainstorm.summarize(app, controller.eval("bl.plainText()").orEmpty()); busy = null } }
                                RL(Icons.Default.AddTask, "Action items") { scope.launch { busy = "Finding action items…"; actions = Brainstorm.actionItems(app, controller.eval("bl.plainText()").orEmpty()); busy = null } }
                                RL(Icons.Default.Lightbulb, "Ideas") { dialog = "ideas" }
                                RL(Icons.Default.Hub, "Mind map") { dialog = "mindmap" }
                                Sep()
                                RL(Icons.AutoMirrored.Filled.Comment, "Comments") { dialog = "comments" }
                                RL(Icons.Default.History, "History") { scope.launch { flush(); dialog = "history" } }
                                RL(Icons.Default.Wifi, "Live Share") { scope.launch { flush() }; dialog = "live" }
                                RL(Icons.Default.IosShare, "Export") { dialog = "export" }
                            }
                        }
                    }
                    // contextual row: selected picture / file / recording, ink selection, link under the caret
                    val item = selectedItem
                    if (st.sel > 0 || item != null || st.link.isNotEmpty() || st.table || st.focus == "text") {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (st.sel > 0) {
                                Text("${st.sel} selected", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 6.dp))
                                AssistChip({ controller.call("duplicateSelection") }, { Text("Duplicate") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) })
                                Spacer(Modifier.width(6.dp))
                                AssistChip({ controller.call("deleteSelection") }, { Text("Delete") }, leadingIcon = { Icon(Icons.Default.Delete, null) })
                            } else if (item != null) {
                                val kind = item.optString("t")
                                if (kind == "audio" || kind == "file") AssistChip({ scope.launch { openItem(item) } }, { Text(if (kind == "audio") "Play" else "Open") }, leadingIcon = { Icon(if (kind == "audio") Icons.Default.PlayArrow else Icons.AutoMirrored.Filled.OpenInNew, null) })
                                if (kind == "image") AssistChip({ scope.launch { openItem(item) } }, { Text("View") }, leadingIcon = { Icon(Icons.Default.Fullscreen, null) })
                                val text = item.optString("ocr").ifBlank { item.optString("transcript") }
                                if (text.isNotBlank()) { Spacer(Modifier.width(6.dp)); AssistChip({ copy(ctx, text) }, { Text(if (kind == "image") "Copy text from picture" else "Copy transcript") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.TextSnippet, null) }) }
                                if (text.isNotBlank()) { Spacer(Modifier.width(6.dp)); AssistChip({ controller.call("insertText", text) ; controller.call("addText") }, { Text("Paste as text") }, leadingIcon = { Icon(Icons.Default.ContentPaste, null) }) }
                                Spacer(Modifier.width(6.dp))
                                AssistChip({ controller.call("toFront") }, { Text("Bring to front") }, leadingIcon = { Icon(Icons.Default.FlipToFront, null) })
                                Spacer(Modifier.width(6.dp))
                                AssistChip({ controller.call("removeSelected") }, { Text("Delete") }, leadingIcon = { Icon(Icons.Default.Delete, null) })
                            } else if (st.focus == "text" && !st.table) {
                                Text("Text box", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 6.dp))
                                AssistChip({ controller.call("toFront") }, { Text("Bring to front") }, leadingIcon = { Icon(Icons.Default.FlipToFront, null) })
                                Spacer(Modifier.width(6.dp))
                                AssistChip({ controller.call("removeSelected") }, { Text("Delete box") }, leadingIcon = { Icon(Icons.Default.Delete, null) })
                            } else if (st.table) {
                                listOf("rowAbove" to "Row above", "rowBelow" to "Row below", "colLeft" to "Column left", "colRight" to "Column right", "delRow" to "Delete row", "delCol" to "Delete column", "delTable" to "Delete table").forEach { (op, l) ->
                                    AssistChip({ controller.call("tableOp", op) }, { Text(l) }, modifier = Modifier.padding(end = 6.dp))
                                }
                            }
                            if (st.link.isNotEmpty()) {
                                Spacer(Modifier.width(6.dp))
                                AssistChip({ openLink(shell, ctx, st.link) }, { Text(if (st.link.startsWith("bl-page:")) "Go to page" else "Open link", maxLines = 1) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) })
                                Spacer(Modifier.width(6.dp))
                                AssistChip({ controller.call("unlink") }, { Text("Remove link") }, leadingIcon = { Icon(Icons.Default.LinkOff, null) })
                            }
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().imePadding()) {
                val p = page
                if (loaded && p != null) {
                    PageCanvas(controller, asset = { name -> Brainstorm.assetBytes(app, p, name, key) }, modifier = Modifier.fillMaxSize())
                    if (!ready) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                } else if (!needUnlock) CircularProgressIndicator(Modifier.align(Alignment.Center))
                busy?.let { BusyOverlay(it) }
            }
        }
    }

    // ---------------- dialogs
    val p = page
    when (dialog) {
        "color" -> ColorDialog("Font color", WordColors, "Automatic", { dialog = null }) { controller.call("color", it ?: "#1b1b1f") }
        "highlight" -> ColorDialog("Highlight", Highlights, "No highlight", { dialog = null }) { controller.call("highlight", it ?: "transparent") }
        "table" -> TableDialog({ dialog = null }) { r, c, h -> controller.call("table", r, c, h) }
        "link" -> LinkDialog(st.link, { dialog = null }) { url, text -> controller.call("link", url, text) }
        "voice" -> VoiceRecorderSheet(onDismiss = { dialog = null }) { file, transcript, ms ->
            dialog = null
            scope.launch {
                val pg = page ?: return@launch
                val ref = Brainstorm.addAsset(app, pg, "m4a") { out -> file.copyTo(out, overwrite = true) }
                file.delete()
                controller.call("addFile", JSONObject().put("t", "audio").put("name", "Recording · " + Time.time(Time.now())).put("path", ref).put("dur", ms).put("transcript", transcript).put("w", 300).toString())
            }
        }
        "pagelink" -> PageLinkDialog(p, { dialog = null }) { id, t -> controller.call("link", "bl-page:$id", t) }
        "paper" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Page color") }, text = {
            Column { PaperColors.forEach { (hex, name) -> ListItem(headlineContent = { Text(name) },
                leadingContent = { Box(Modifier.size(28.dp).clip(CircleShape).background(Color(android.graphics.Color.parseColor(hex))).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)) },
                modifier = Modifier.clickable { controller.call("setBg", JSONObject().put("color", hex).put("dark", hex == "#1e1f24").toString()); dialog = null }, colors = ListItemDefaults.colors(containerColor = Color.Transparent)) } }
        }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("Close") } })
        "rules" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Rule lines") }, text = {
            Column { Rules.forEach { (id, name) -> ListItem(headlineContent = { Text(name) }, leadingContent = { Icon(if (id.contains("grid")) Icons.Default.GridOn else if (id == "dots") Icons.Default.Apps else if (id == "none") Icons.Default.CheckBoxOutlineBlank else Icons.AutoMirrored.Filled.Notes, null) },
                modifier = Modifier.clickable { controller.call("setBg", JSONObject().put("rule", id).toString()); dialog = null }, colors = ListItemDefaults.colors(containerColor = Color.Transparent)) } }
        }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("Close") } })
        "comments" -> CommentsSheet(pageId, null) { dialog = null }
        "live" -> LiveShareSheet("PAGE", pageId, title.ifBlank { "Untitled page" }, locked = section?.locked == true) { dialog = null }
        "history" -> if (p != null) HistorySheet(app, p, onDismiss = { dialog = null }) { json ->
            dialog = null
            scope.launch { controller.initial = json; controller.call("load", json); persist(json); shell.toast("Restored that version") }
        }
        "reminder" -> ReminderDialog(p?.reminderAt, null, null, allowPlace = false, onDismiss = { dialog = null }) { r ->
            p?.let { pg -> app.io { dao.updatePage(pg.copy(reminderAt = r.at)); app.requestReminderSync() } }
            shell.toast(if (r.at == null) "Reminder removed" else "I'll remind you " + Time.dateTime(r.at))
        }
        "move", "copy" -> if (p != null) SectionPickerDialog(if (dialog == "move") "Move page to" else "Copy page to", Profile.from(p.profile), excludeSection = if (dialog == "move") p.sectionId else null, onDismiss = { dialog = null }) { target ->
            val moving = dialog == "move"
            scope.launch {
                flush()
                runCatching {
                    if (moving) { Brainstorm.movePage(app, dao.page(pageId)!!, target.id); section = target; notebookName = dao.notebook(target.notebookId)?.name.orEmpty() }
                    else Brainstorm.copyPageTo(app, dao.page(pageId)!!, target.id)
                }.onSuccess { shell.toast(if (moving) "Moved to ${target.name}" else "Copied to ${target.name}") }
                    .onFailure { shell.toast(if (it is LockedException) "Unlock “${target.name}” first" else "Couldn't do that: ${it.message}") }
            }
        }
        "info" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Page info") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Created ${p?.createdAt?.let { Time.dateTime(it) }}"); Text("Edited ${p?.updatedAt?.let { Time.dateTime(it) }}")
                Text("${p?.wordCount ?: 0} words"); Text(if (section?.locked == true) "🔒 Encrypted (password-protected section)" else "Stored in BlackLab › Brainstorm")
            }
        }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("OK") } })
        "delete" -> ConfirmDialog("Delete page?", "It goes to the Brainstorm recycle bin for ${Brainstorm.TRASH_DAYS} days.", onDismiss = { dialog = null }) {
            p?.let { pg -> scope.launch { Brainstorm.trashPage(app, pg); shell.back() } }
        }
        "export" -> if (p != null) ExportSheet(app, p, onDismiss = { dialog = null }, before = { flush() })
        "ideas", "mindmap" -> {
            val mind = dialog == "mindmap"
            TextInputDialog(if (mind) "Mind map topic" else "Brainstorm ideas about", title, "Topic", if (mind) "Create" else "Go", onDismiss = { dialog = null }) { topic ->
                scope.launch {
                    busy = "Thinking…"
                    val (list, _) = Brainstorm.ideas(app, topic, controller.eval("bl.plainText()").orEmpty().take(800))
                    busy = null
                    if (mind && p != null) {
                        val id = Brainstorm.createPage(app, Profile.from(p.profile), p.sectionId, "blank", topic, after = p, json = PageTemplates.mindMap(topic, list))
                        flush(); shell.nav.navigate("page/$id") { popUpTo("page/{id}") { inclusive = true } }
                    } else ideas = topic to list
                }
            }
        }
    }
    aiText?.let { (text, source) ->
        AlertDialog(onDismissRequest = { aiText = null }, title = { Text("Summary") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) { Text(text); Spacer(Modifier.height(8.dp)); Text(source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }, confirmButton = {
            TextButton(onClick = { controller.call("insertHtml", "<p><b>Summary</b></p><ul>" + text.lines().filter { it.isNotBlank() }.joinToString("") { "<li>" + com.blacklab.app.data.ContentStore.escape(it.trimStart('-', '•', ' ')) + "</li>" } + "</ul>"); aiText = null }) { Text("Insert") }
        }, dismissButton = { TextButton(onClick = { copy(ctx, text) }) { Text("Copy") } })
    }
    actions?.let { list ->
        val chosen = remember(list) { mutableStateListOf<Boolean>().apply { repeat(list.size) { add(true) } } }
        AlertDialog(onDismissRequest = { actions = null }, title = { Text("Action items") }, text = {
            if (list.isEmpty()) Text("No action items found. Tip: phrases like “I'll…”, “we need to…”, or To Do tags are picked up.")
            else LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(list.indices.toList()) { i -> Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(chosen[i], { chosen[i] = it }); Text(list[i]) } }
            }
        }, confirmButton = {
            if (list.isNotEmpty()) TextButton(onClick = {
                val pick = list.filterIndexed { i, _ -> chosen[i] }
                scope.launch { val n = Brainstorm.createTasks(app, profile, pick, title.ifBlank { "page" }); shell.toast("$n task(s) added to To-Do", "Open") { shell.go("tasks") } }
                actions = null
            }) { Text("Create tasks") }
        }, dismissButton = {
            if (list.isNotEmpty()) TextButton(onClick = {
                controller.call("insertHtml", list.filterIndexed { i, _ -> chosen[i] }.joinToString("") { "<p data-tag=\"todo\" data-done=\"0\">" + com.blacklab.app.data.ContentStore.escape(it) + "</p>" }); actions = null
            }) { Text("Insert as To Do") } else TextButton(onClick = { actions = null }) { Text("Close") }
        })
    }
    ideas?.let { (topic, list) ->
        AlertDialog(onDismissRequest = { ideas = null }, title = { Text("Ideas: $topic") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) { list.forEach { Text("💡 $it", modifier = Modifier.padding(vertical = 3.dp)) } }
        }, confirmButton = {
            TextButton(onClick = { controller.call("insertHtml", list.joinToString("") { "<p data-tag=\"idea\">" + com.blacklab.app.data.ContentStore.escape(it) + "</p>" }); ideas = null }) { Text("Insert") }
        }, dismissButton = { TextButton(onClick = { ideas = null }) { Text("Close") } })
    }
}

private fun toolJson(tool: String, pen: String, hl: String, width: Float, penOnly: Boolean): String =
    JSONObject().put("kind", tool).put("color", if (tool == "highlighter") hl else pen).put("width", width.toDouble()).put("penOnly", penOnly).toString()

fun openLink(shell: com.blacklab.app.ui.components.Shell, ctx: Context, href: String) {
    when {
        href.startsWith("bl-page:") -> href.removePrefix("bl-page:").toLongOrNull()?.let { shell.go("page/$it") }
        href.isNotBlank() -> Files.openUrl(ctx, href)
    }
}

@Composable
private fun PageFindBar(controller: PageController, onClose: () -> Unit) {
    var term by remember { mutableStateOf("") }
    val (count, idx) = controller.find.collectAsState().value
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(term, { term = it; controller.call("find", it, false) }, placeholder = { Text("Find on page") }, singleLine = true, modifier = Modifier.weight(1f))
        Text(if (count > 0) "${idx + 1}/$count" else "0", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 6.dp))
        IconButton(onClick = { controller.call("findStep", -1) }) { Icon(Icons.Default.KeyboardArrowUp, "Previous") }
        IconButton(onClick = { controller.call("findStep", 1) }) { Icon(Icons.Default.KeyboardArrowDown, "Next") }
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
    }
}

@Composable
private fun PageLinkDialog(page: PageEntity?, onDismiss: () -> Unit, onPick: (Long, String) -> Unit) {
    val app = rememberApp()
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PageEntity>>(emptyList()) }
    val profile = page?.profile ?: app.settings.current.activeProfile.name
    LaunchedEffect(q) {
        results = withContext(Dispatchers.IO) { app.db.brainstorm().search(profile, "%${q.trim()}%").filter { it.id != page?.id }.take(40) }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Link to a page") }, text = {
        Column {
            OutlinedTextField(q, { q = it }, placeholder = { Text("Search pages") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(results, key = { it.id }) { r ->
                    ListItem(headlineContent = { Text(r.title.ifBlank { "Untitled page" }) }, supportingContent = { Text(r.preview.take(60), maxLines = 1) },
                        modifier = Modifier.clickable { onPick(r.id, r.title.ifBlank { "Untitled page" }); onDismiss() }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(app: AppContainer, page: PageEntity, onDismiss: () -> Unit, onRestore: (String) -> Unit) {
    val versions = remember(page.id) { Brainstorm.versions(app, page) }
    var preview by remember { mutableStateOf<Pair<Brainstorm.Version, String>?>(null) }
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text("Page history", style = MaterialTheme.typography.titleLarge)
            Text("A snapshot is kept every 10 minutes while you edit (last ${versions.size}).", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            if (preview == null) LazyColumn(Modifier.heightIn(max = 460.dp)) {
                if (versions.isEmpty()) item { Text("No earlier versions yet.", modifier = Modifier.padding(vertical = 20.dp)) }
                items(versions) { v ->
                    ListItem(headlineContent = { Text(Time.dateTime(v.time)) }, supportingContent = { Text(Time.ago(v.time) + " · " + Files.formatSize(v.file.length())) },
                        leadingContent = { Icon(Icons.Default.History, null) },
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { scope.launch { runCatching { preview = v to Brainstorm.staticHtml(app, page, Brainstorm.readVersion(app, page, v)) } } },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                }
            } else {
                val (v, html) = preview!!
                Text("Version from ${Time.dateTime(v.time)}", style = MaterialTheme.typography.titleSmall)
                StaticHtmlView(html, Modifier.fillMaxWidth().height(420.dp).padding(vertical = 8.dp).clip(RoundedCornerShape(12.dp)))
                Row {
                    TextButton(onClick = { preview = null }) { Text("Back") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { scope.launch { onRestore(Brainstorm.readVersion(app, page, v)) } }) { Text("Restore this version") }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
fun StaticHtmlView(html: String, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { c -> WebView(c).apply {
            settings.javaScriptEnabled = false; settings.allowFileAccess = false; settings.allowContentAccess = false; settings.blockNetworkLoads = true
            settings.builtInZoomControls = true; settings.displayZoomControls = false; settings.loadWithOverviewMode = true; settings.useWideViewPort = true
            webViewClient = object : WebViewClient() { override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true }
        } },
        update = { w -> if (w.tag != html) { w.tag = html; w.loadDataWithBaseURL(null, html, "text/html", "utf-8", null) } },
        modifier = modifier,
    )
}

@Composable
private fun ExportSheet(app: AppContainer, page: PageEntity, onDismiss: () -> Unit, before: suspend () -> Unit) {
    val ctx = LocalContext.current
    val shell = LocalShell.current
    // the sheet closes on tap, so work runs in the app shell's scope (the sheet's own scope is cancelled)
    val scope = shell.scope
    fun run(format: ExportFormat) {
        scope.launch {
            before()
            runCatching { Brainstorm.export(app, dao(app).page(page.id) ?: page, format) }
                .onSuccess { f -> Files.share(ctx, listOf(f), page.title); shell.toast("Saved to Exports › ${f.name}") }
                .onFailure { shell.toast("Export failed: ${it.message}") }
        }
    }
    ActionSheet(
        title = "Share “${page.title.ifBlank { "Untitled page" }}”", subtitle = "Exports are also saved to BlackLab › Exports", onDismiss = onDismiss,
        quick = listOf(
            SheetAction("PDF", Icons.Default.PictureAsPdf) { run(ExportFormat.PDF) },
            SheetAction("Word", Icons.Default.Description) { run(ExportFormat.DOCX) },
            SheetAction("Print", Icons.Default.Print) { scope.launch { before(); printHtml(ctx, page.title, Brainstorm.staticHtml(app, dao(app).page(page.id) ?: page)) } },
        ),
        actions = listOf(
            SheetAction("Web page (exact layout)", Icons.Default.Language, hint = "Keeps boxes, ink and pictures where you put them") { run(ExportFormat.HTML) },
            SheetAction("Markdown", Icons.Default.Code) { run(ExportFormat.MD) },
            SheetAction("Plain text", Icons.AutoMirrored.Filled.Notes) { run(ExportFormat.TXT) },
            SheetAction("Send as text", Icons.AutoMirrored.Filled.Send, hint = "Paste into email or chat") {
                scope.launch { before(); val txt = dao(app).page(page.id)?.searchText.orEmpty(); Files.shareText(ctx, (page.title + "\n\n" + txt).trim(), page.title) }
            },
        ),
    )
}

private fun dao(app: AppContainer) = app.db.brainstorm()

private var printing: WebView? = null

/** System print dialog (also "Save as PDF") with the exact page layout. */
fun printHtml(ctx: Context, title: String, html: String) {
    val wv = WebView(ctx)
    printing = wv
    wv.settings.javaScriptEnabled = false
    wv.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String?) {
            val pm = ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager
            pm.print(title.ifBlank { "BlackLab page" }, view.createPrintDocumentAdapter(title.ifBlank { "BlackLab page" }), PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.NA_LETTER).build())
            view.postDelayed({ if (printing === view) printing = null }, 60_000)
        }
    }
    wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
}

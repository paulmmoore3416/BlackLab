package com.blacklab.app.ui.ssh

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Base64
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TextDecrease
import androidx.compose.material.icons.rounded.TextIncrease
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.blacklab.app.assistant.DesktopAi
import com.blacklab.app.ssh.SshClient
import com.blacklab.app.ssh.SshHosts
import com.blacklab.app.ui.components.BlCard
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.ConfirmDialog
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.Pill
import com.blacklab.app.ui.components.TextInputDialog
import com.jcraft.jsch.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

// ====================================================================== live sessions (outlive the screen)

/** Open terminals by host id. Leaving the screen keeps the session (and its last 512 KB of output) until Disconnect. */
object Terminals {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val live = ConcurrentHashMap<String, Live>()
    private const val SCROLLBACK = 512 * 1024

    sealed interface Phase {
        data object Idle : Phase
        data class Connecting(val note: String = "", val link: String? = null) : Phase
        data object Connected : Phase
        data class Failed(val message: String, val keyChanged: Boolean = false) : Phase
        data class Closed(val message: String) : Phase
    }

    /** A question from the SSH sign-in that the screen answers (trust a key, type a password). */
    class Ask(val title: String, val text: String, val secret: Boolean, val echo: Boolean, val answer: CompletableDeferred<String?> = CompletableDeferred())

    class Live(val host: SshHosts.Host) {
        val phase = MutableStateFlow<Phase>(Phase.Idle)
        val ask = MutableStateFlow<Ask?>(null)
        @Volatile var session: Session? = null
        @Volatile var shell: SshClient.Shell? = null
        @Volatile var starting = false
        @Volatile var sink: ((ByteArray) -> Unit)? = null
        private val scroll = ByteArrayOutputStream()

        fun output(b: ByteArray) {
            synchronized(scroll) {
                scroll.write(b)
                if (scroll.size() > SCROLLBACK * 2) {
                    val keep = scroll.toByteArray().let { it.copyOfRange(it.size - SCROLLBACK, it.size) }
                    scroll.reset(); scroll.write(keep)
                }
            }
            sink?.invoke(b)
        }

        fun replay(): ByteArray = synchronized(scroll) { scroll.toByteArray() }

        fun note(text: String) = output(("\r\n\u001b[38;5;79m" + text.replace("\n", "\r\n") + "\u001b[0m\r\n").toByteArray())

        fun close(message: String = "Disconnected.") {
            shell?.close(); shell = null
            runCatching { session?.disconnect() }; session = null
            phase.value = Phase.Closed(message)
        }

        /** Blocks the SSH thread until the screen answers (or 3 minutes pass). */
        fun prompt(a: Ask): String? {
            ask.value = a
            return try { runBlocking { kotlinx.coroutines.withTimeoutOrNull(180_000) { a.answer.await() } } } finally { ask.value = null }
        }
    }

    fun get(h: SshHosts.Host): Live = live.getOrPut(h.id) { Live(h) }

    fun connect(ctx: Context, l: Live) {
        if (l.phase.value is Phase.Connecting || l.phase.value is Phase.Connected) return
        l.phase.value = Phase.Connecting("Connecting to ${l.host.label}…")
        scope.launch {
            val client = SshClient(knownHosts(ctx))
            val prompts = object : SshClient.Prompts {
                override fun trust(host: String, keyType: String, fingerprint: String): Boolean =
                    l.prompt(Ask("Trust ${l.host.name}?", "First connection to $host.\n\n$keyType key fingerprint:\n$fingerprint\n\nTrust it and remember it? " +
                        "(If the key ever changes, BlackLab will stop and warn you.)", secret = false, echo = false)) == "yes"

                override fun secret(prompt: String, echo: Boolean): String? = l.prompt(Ask(l.host.label, prompt, secret = true, echo = echo))

                override fun banner(text: String) {
                    l.note(text.trim())
                    l.phase.value = Phase.Connecting(text.trim(), SshClient.linkIn(text))
                }
            }
            try {
                l.session = client.open(l.host, prompts)
                l.phase.value = Phase.Connected
            } catch (e: SshClient.HostKeyChanged) {
                l.phase.value = Phase.Failed(e.message + "\n\nSaved fingerprint: " + e.fingerprint, keyChanged = true)
            } catch (e: Exception) {
                l.phase.value = Phase.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Opens the shell once both the session and the terminal view are ready. */
    fun startShell(l: Live, cols: Int, rows: Int) {
        val s = l.session ?: return
        synchronized(l) {
            if (l.shell?.open == true) { l.shell?.resize(cols, rows); return }
            if (l.starting) return  // the other caller is already opening it
            l.starting = true
        }
        scope.launch {
            try {
                l.shell = SshClient.Shell(s, cols, rows, onOutput = { l.output(it) }, onClosed = { why ->
                    l.shell = null
                    runCatching { l.session?.disconnect() }; l.session = null
                    l.phase.value = Phase.Closed(why ?: "Session ended.")
                })
            } catch (e: Exception) {
                l.phase.value = Phase.Failed("Couldn't open a shell: ${e.message}")
            } finally {
                l.starting = false
            }
        }
    }

    fun knownHosts(ctx: Context) = File(ctx.filesDir, "ssh/known_hosts")
    fun storeFile(ctx: Context) = File(ctx.filesDir, "ssh/hosts.json")
}

// ====================================================================== host list

@Composable
fun SshHostsScreen() {
    val ctx = LocalContext.current
    val shell = LocalShell.current
    val scope = rememberCoroutineScope()
    val store = SshHosts.store
    val state by store.state.collectAsState()
    var checking by remember { mutableStateOf(true) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SshHosts.Host?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { store.load(Terminals.storeFile(ctx)) }
        val info = DesktopAi.sshInfo()
        val desktop = info?.let { SshHosts.desktopHost(it.tailnetUrl, it.user, it.name) }
        if (desktop != null) withContext(Dispatchers.IO) { store.update(Terminals.storeFile(ctx)) { SshHosts.withDesktop(it, desktop) } }
        checking = false
    }

    Scaffold(topBar = { BlTopBar("Terminal", subtitle = "SSH over Tailscale") }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                BlCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(10.dp))
                        Text("Private by design", style = MaterialTheme.typography.titleSmall)
                    }
                    Text("Connections go over your Tailscale network only. Your computer signs the phone in with Tailscale SSH — no passwords or keys " +
                        "are stored. First time on the computer: sudo tailscale set --ssh", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
            }
            if (state.hosts.none { it.id == SshHosts.DESKTOP_ID }) item {
                BlCard(Modifier.fillMaxWidth()) {
                    Text(if (checking) "Looking for your BlackLab computer…" else "Your BlackLab computer isn't linked right now",
                        style = MaterialTheme.typography.titleSmall)
                    if (!checking) Text("It's added here automatically once the desktop link answers (Assistant › Desktop AI). You can also add it by hand: paul@homebox",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(state.hosts, key = { it.id }) { h ->
                val live = Terminals.live[h.id]
                val phase = live?.phase?.collectAsState()?.value
                BlCard(Modifier.fillMaxWidth(), onClick = { shell.go("ssh/${Uri.encode(h.id)}") }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (h.id == SshHosts.DESKTOP_ID) Icons.Rounded.Computer else Icons.Rounded.Dns, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(h.name, style = MaterialTheme.typography.titleMedium)
                            Text(h.label, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (phase is Terminals.Phase.Connected) Pill("open", Color(0xFF3DDC84))
                        if (!h.auto) IconButton(onClick = { deleting = h }) { Icon(Icons.Rounded.Delete, "Remove") }
                    }
                }
            }
            item {
                OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                    Icon(Icons.Rounded.Add, null); Spacer(Modifier.size(8.dp)); Text("Add a server")
                }
            }
        }
    }
    if (adding) TextInputDialog(title = "Add a server", label = "user@host or user@host:port", confirmLabel = "Add", onDismiss = { adding = false }, onConfirm = { text ->
        SshHosts.parse(text).fold(
            onSuccess = { h -> scope.launch(Dispatchers.IO) { store.update(Terminals.storeFile(ctx)) { s -> s.copy(hosts = s.hosts.filter { it.id != h.id } + h) } } },
            onFailure = { shell.toast(it.message ?: "That address isn't valid") },
        )
    })
    deleting?.let { h ->
        ConfirmDialog("Remove ${h.name}?", "${h.label} leaves the list. Its saved host key is forgotten too.", "Remove", onDismiss = { deleting = null }) {
            Terminals.live.remove(h.id)?.close()
            scope.launch(Dispatchers.IO) {
                runCatching { SshClient(Terminals.knownHosts(ctx)).forget(h.host, h.port) }
                store.update(Terminals.storeFile(ctx)) { s -> s.copy(hosts = s.hosts.filter { it.id != h.id }) }
            }
        }
    }
}

// ====================================================================== one terminal

private class TermView(private val live: Terminals.Live) {
    var webView: WebView? = null
    @Volatile var ready = false
    @Volatile var cols = 80
    @Volatile var rows = 24
    private val main = Handler(Looper.getMainLooper())
    private val pending = ByteArrayOutputStream()
    private var scheduled = false

    fun feed(b: ByteArray) {
        synchronized(pending) {
            pending.write(b)
            if (scheduled || !ready) return
            scheduled = true
        }
        main.postDelayed(::flush, 16)
    }

    private fun flush() {
        val bytes = synchronized(pending) { scheduled = false; pending.toByteArray().also { pending.reset() } }
        val wv = webView ?: return
        if (!ready || bytes.isEmpty()) return  // before the page is ready, the scrollback replay covers it
        var i = 0
        while (i < bytes.size) {
            val n = minOf(48 * 1024, bytes.size - i)
            wv.evaluateJavascript("bl.out('" + Base64.encodeToString(bytes, i, n, Base64.NO_WRAP) + "')", null)
            i += n
        }
    }

    fun js(s: String) = main.post { webView?.evaluateJavascript(s, null) }

    /** The page is up: show everything so far (the session's scrollback), then live output. */
    fun onReady() {
        synchronized(pending) { pending.reset(); pending.write(live.replay()) }
        ready = true
        feed(ByteArray(0))
    }
}

private class TermBridge(private val live: Terminals.Live, private val view: TermView, private val onKeys: (ctrl: Boolean?, alt: Boolean?) -> Unit,
                         private val onFont: (Int) -> Unit) {
    @JavascriptInterface fun onReady(cols: Int, rows: Int) {
        view.cols = cols; view.rows = rows
        view.onReady()
        Terminals.startShell(live, cols, rows)  // no-op until the session is up; the screen calls it again when it is
    }
    @JavascriptInterface fun onData(text: String) { live.shell?.write(text.toByteArray(Charsets.UTF_8)) }
    @JavascriptInterface fun onResize(cols: Int, rows: Int) {
        view.cols = cols; view.rows = rows
        live.shell?.resize(cols, rows) ?: Terminals.startShell(live, cols, rows)
    }
    @JavascriptInterface fun onCtrl(on: Boolean) = onKeys(on, null)
    @JavascriptInterface fun onAlt(on: Boolean) = onKeys(null, on)
    /** A pinch changed the text size. */
    @JavascriptInterface fun onFont(px: Int) = onFont.invoke(px)
}

/**
 * The terminal's WebView, asking for a plain keyboard: no suggestions, autocorrect or composing (the same trick
 * Termux uses — Android keyboards treat it like a visible password field). With composing on, Gboard keeps the whole
 * line "in progress" and rewrites it as you type, which a terminal can only see as the line arriving again and again.
 * Full-keyboard typing (suggestions, voice) lives in the compose box under the terminal instead.
 */
private class TermWebView(c: Context) : WebView(c) {
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs)
        outAttrs.inputType = PLAIN_KEYBOARD
        outAttrs.imeOptions = (outAttrs.imeOptions and EditorInfo.IME_MASK_ACTION.inv()) or EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_ENTER_ACTION or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        return ic
    }

    companion object {
        /** Text, visible-password variation (no suggestions/composing), no caps, keeps Return as a key. */
        const val PLAIN_KEYBOARD =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TerminalView(live: Terminals.Live, view: TermView, font: Int, awake: Boolean, onKeys: (Boolean?, Boolean?) -> Unit, onFont: (Int) -> Unit,
                         modifier: Modifier) {
    val ctx = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }
    AndroidView(modifier = modifier, factory = { c ->
        TermWebView(c).apply {
            if (com.blacklab.app.BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
            // AndroidView hosts views as WRAP_CONTENT, and a WRAP_CONTENT WebView collapses 100%-height pages to 0 px
            // (xterm then measures one row). Fill the space Compose gives it.
            layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            isFocusable = true
            isFocusableInTouchMode = true
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportZoom(false)
            setBackgroundColor(0xFF0B0E13.toInt())
            overScrollMode = WebView.OVER_SCROLL_NEVER
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // Links printed in the terminal open in the browser (Tailscale's sign-in link, docs…)
                    val u = request.url
                    if (u.scheme == "https" && u.host != "bl.local") runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, u)) }
                    return true
                }
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val u = request.url
                    if (u.host == "bl.local") {
                        val name = u.path.orEmpty().removePrefix("/")
                        val mime = when { name.endsWith(".html") -> "text/html"; name.endsWith(".js") -> "application/javascript"; name.endsWith(".css") -> "text/css"; else -> null }
                        if (mime != null && name in setOf("terminal.html", "terminal.js", "xterm.js", "xterm.css", "addon-fit.js"))
                            return WebResourceResponse(mime, "utf-8", ctx.assets.open("terminal/$name"))
                    }
                    return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
            }
            addJavascriptInterface(TermBridge(live, view, onKeys) { px -> main.post { onFont(px) } }, "Android")
            view.webView = this
            live.sink = { view.feed(it) }
            loadUrl("https://bl.local/terminal.html?font=$font")
        }
    }, update = { wv ->
        // Reading a long reply shouldn't lock the phone mid-way; a dropped session lets the screen sleep as usual.
        wv.keepScreenOn = awake
    }, onRelease = { wv ->
        if (view.webView === wv) { view.webView = null; live.sink = null }
        wv.removeJavascriptInterface("Android")
        wv.destroy()
    })
}

@Composable
fun SshTerminalScreen(hostId: String) {
    val ctx = LocalContext.current
    val shell = LocalShell.current
    val scope = rememberCoroutineScope()
    val state by SshHosts.store.state.collectAsState()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { SshHosts.store.load(Terminals.storeFile(ctx)) } }
    val host = state.hosts.firstOrNull { it.id == hostId }
    if (host == null) {
        Scaffold(topBar = { BlTopBar("Terminal", onBack = { shell.back() }) }) { pad ->
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        return
    }
    val live = remember(host.id) { Terminals.get(host) }
    val phase by live.phase.collectAsState()
    val ask by live.ask.collectAsState()
    val view = remember(host.id) { TermView(live) }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    // The size on screen lives here and changes at once; the store only remembers it (it saves in the background, so
    // reading it back for the next tap would lag behind quick taps or a pinch).
    var font by remember { mutableIntStateOf(state.fontSize) }
    fun saveFont(px: Int) {
        val v = px.coerceIn(SshHosts.MIN_FONT, SshHosts.MAX_FONT)
        font = v
        scope.launch(Dispatchers.IO) { SshHosts.store.update(Terminals.storeFile(ctx)) { it.copy(fontSize = v) } }
    }
    fun stepFont(by: Int) { val v = (font + by).coerceIn(SshHosts.MIN_FONT, SshHosts.MAX_FONT); if (v != font) { view.js("bl.font($v)"); saveFont(v) } }
    // The compose box keeps its text (and what was sent) through a rotation or a trip to another app. Sent lines stay
    // in memory only, never on disk: a compose box can hold things you wouldn't want saved.
    var composeOpen by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    val sentLines = rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })) { mutableStateListOf<String>() }
    var menu by remember { mutableStateOf(false) }
    var commands by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf<SshHosts.Command?>(null) }
    var result by remember { mutableStateOf<Pair<SshHosts.Command, SshClient.ExecResult>?>(null) }
    var confirm by remember { mutableStateOf<SshHosts.Command?>(null) }
    var newCommand by remember { mutableStateOf(false) }

    LaunchedEffect(host.id) {
        if (live.phase.value is Terminals.Phase.Idle || live.phase.value is Terminals.Phase.Closed) {
            if (live.phase.value is Terminals.Phase.Closed) live.note("— reconnecting —")
            Terminals.connect(ctx, live)
        }
        withContext(Dispatchers.IO) { SshHosts.store.update(Terminals.storeFile(ctx)) { it.copy(lastHost = host.id) } }
    }
    // The page and the SSH session get ready in either order (a trust prompt can hold the session back): whichever is
    // second starts the shell.
    LaunchedEffect(phase) {
        if (phase is Terminals.Phase.Connected && view.ready) {
            Terminals.startShell(live, view.cols, view.rows)
            view.js("bl.focus()")
        }
    }
    DisposableEffect(host.id) { onDispose { if (live.sink != null && view.webView == null) live.sink = null } }

    fun runCommand(c: SshHosts.Command) {
        val s = live.session ?: return shell.toast("Not connected")
        running = c
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { SshClient(Terminals.knownHosts(ctx)).exec(s, c.command) } }
            running = null
            r.fold(onSuccess = { result = c to it }, onFailure = { shell.toast("${c.label} failed: ${it.message}") })
        }
    }

    Scaffold(topBar = {
        BlTopBar(host.name, subtitle = when (val p = phase) {
            is Terminals.Phase.Connected -> host.label
            is Terminals.Phase.Connecting -> "connecting…"
            is Terminals.Phase.Failed -> "not connected"
            is Terminals.Phase.Closed -> "disconnected"
            else -> host.label
        }, onBack = { shell.back() }) {
            IconButton(onClick = { commands = true }, enabled = phase is Terminals.Phase.Connected) { Icon(Icons.Rounded.Bolt, "Quick commands") }
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Paste") }, leadingIcon = { Icon(Icons.Rounded.ContentPaste, null) }, onClick = {
                    menu = false
                    val clip = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
                    if (!clip.isNullOrEmpty()) view.js("bl.text(" + org.json.JSONObject.quote(clip) + ")")
                })
                DropdownMenuItem(text = { Text("Bigger text") }, leadingIcon = { Icon(Icons.Rounded.TextIncrease, null) }, onClick = { stepFont(+1) })
                DropdownMenuItem(text = { Text("Smaller text") }, leadingIcon = { Icon(Icons.Rounded.TextDecrease, null) }, onClick = { stepFont(-1) })
                DropdownMenuItem(text = { Text("Reconnect") }, leadingIcon = { Icon(Icons.Rounded.Refresh, null) }, onClick = {
                    menu = false; live.close("Reconnecting…"); Terminals.connect(ctx, live)
                })
                DropdownMenuItem(text = { Text("Disconnect") }, leadingIcon = { Icon(Icons.Rounded.LinkOff, null) }, onClick = {
                    menu = false; live.close(); Terminals.live.remove(host.id); shell.back()
                })
            }
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding().background(Color(0xFF0B0E13))) {
            when (val p = phase) {
                is Terminals.Phase.Connecting -> Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(10.dp))
                    Text(if (p.link != null) "Tailscale wants you to confirm it's you" else p.note.ifBlank { "Connecting…" }, color = Color(0xFFE7ECF3),
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    if (p.link != null) Button(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.link))) } }) { Text("Approve") }
                }
                is Terminals.Phase.Failed -> Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(p.message, color = Color(0xFFFFB020), style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { live.phase.value = Terminals.Phase.Idle; Terminals.connect(ctx, live) }) { Text("Try again") }
                        if (p.keyChanged) OutlinedButton(onClick = {
                            scope.launch(Dispatchers.IO) { SshClient(Terminals.knownHosts(ctx)).forget(host.host, host.port) }
                            shell.toast("Saved key forgotten — connect again to see and trust the new one")
                        }) { Text("I reinstalled it — forget the old key") }
                    }
                }
                is Terminals.Phase.Closed -> Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(p.message, color = Color(0xFF8D99AB), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Button(onClick = { live.note("— reconnecting —"); Terminals.connect(ctx, live) }) { Text("Reconnect") }
                }
                else -> {}
            }
            TerminalView(live, view, font, awake = phase is Terminals.Phase.Connected, onKeys = { c, a -> c?.let { ctrl = it }; a?.let { alt = it } },
                onFont = ::saveFont,
                modifier = Modifier.weight(1f).fillMaxWidth())
            KeyBar(ctrl, alt, composeOpen, onKey = { view.js("bl.key('$it')") }, onText = { view.js("bl.text(" + org.json.JSONObject.quote(it) + ")") },
                onCtrl = { ctrl = !ctrl; view.js("bl.ctrl($ctrl)") }, onAlt = { alt = !alt; view.js("bl.alt($alt)") },
                onCompose = { composeOpen = !composeOpen; if (!composeOpen) view.js("bl.focus()") })
            if (composeOpen) ComposeBar(draft, { draft = it }, sentLines,
                onSend = {
                    view.js("bl.line(" + org.json.JSONObject.quote(draft) + ", true)")
                    if (draft.isNotBlank()) { sentLines.remove(draft); sentLines.add(0, draft); while (sentLines.size > MAX_SENT) sentLines.removeAt(sentLines.lastIndex) }
                    draft = ""
                })
        }
    }

    ask?.let { a ->
        var text by remember(a) { mutableStateOf("") }
        AlertDialog(onDismissRequest = { a.answer.complete(null) }, title = { Text(a.title) },
            text = {
                Column {
                    Text(a.text, fontFamily = if (a.secret) null else FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    if (a.secret) OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.padding(top = 8.dp),
                        visualTransformation = if (a.echo) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation())
                }
            },
            confirmButton = { TextButton(onClick = { a.answer.complete(if (a.secret) text else "yes") }) { Text(if (a.secret) "Send" else "Trust") } },
            dismissButton = { TextButton(onClick = { a.answer.complete(null) }) { Text("Cancel") } })
    }

    if (commands) ModalBottomSheet(onDismissRequest = { commands = false }) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Quick commands", style = MaterialTheme.typography.titleMedium)
            Text("Run on ${host.label}; the output shows here, the terminal is untouched.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
            SshHosts.commandsFor(state, host).forEach { c ->
                BlCard(Modifier.fillMaxWidth().padding(vertical = 3.dp), onClick = { commands = false; if (c.confirm) confirm = c else runCommand(c) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Terminal, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(10.dp))
                        Text(c.label, Modifier.weight(1f))
                        if (!c.builtIn) IconButton(onClick = {
                            scope.launch(Dispatchers.IO) { SshHosts.store.update(Terminals.storeFile(ctx)) { s -> s.copy(commands = s.commands.filter { it.id != c.id }) } }
                        }) { Icon(Icons.Rounded.Delete, "Remove") }
                    }
                }
            }
            OutlinedButton(onClick = { commands = false; newCommand = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Icon(Icons.Rounded.Add, null); Spacer(Modifier.size(8.dp)); Text("Add your own")
            }
        }
    }
    confirm?.let { c -> ConfirmDialog(c.label + "?", c.command, "Run", destructive = false, onDismiss = { confirm = null }) { runCommand(c) } }
    running?.let { c ->
        AlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Text(c.label) }, text = {
            Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp)); Spacer(Modifier.size(12.dp)); Text("Running…") }
        })
    }
    result?.let { (c, r) ->
        AlertDialog(onDismissRequest = { result = null }, title = { Text(c.label) },
            text = {
                SelectionContainer {
                    Text(r.output.ifBlank { "(no output)" } + (if (r.truncated) "\n…(cut short)" else "") + if (r.exit > 0) "\n\nexit status ${r.exit}" else "",
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                        modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()))
                }
            },
            confirmButton = { TextButton(onClick = { result = null }) { Text("Close") } },
            dismissButton = { TextButton(onClick = {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(c.label, r.output))
                shell.toast("Copied")
            }) { Text("Copy") } })
    }
    if (newCommand) {
        var label by remember { mutableStateOf("") }
        var cmd by remember { mutableStateOf("") }
        var askFirst by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { newCommand = false }, title = { Text("New quick command") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(label, { label = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(cmd, { cmd = it }, label = { Text("Command") }, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    FilterChip(askFirst, { askFirst = !askFirst }, label = { Text("Ask before running") })
                }
            },
            confirmButton = { TextButton(enabled = label.isNotBlank() && cmd.isNotBlank(), onClick = {
                val c = SshHosts.Command("c" + System.currentTimeMillis().toString(36), label.trim(), cmd.trim(), askFirst)
                scope.launch(Dispatchers.IO) { SshHosts.store.update(Terminals.storeFile(ctx)) { s -> s.copy(commands = s.commands + c) } }
                newCommand = false
            }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { newCommand = false }) { Text("Cancel") } })
    }
}

/** How many sent compose-box lines the ⟲ menu keeps. */
private const val MAX_SENT = 20

@Composable
private fun KeyBar(ctrl: Boolean, alt: Boolean, composing: Boolean, onKey: (String) -> Unit, onText: (String) -> Unit, onCtrl: () -> Unit, onAlt: () -> Unit,
                   onCompose: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color(0xFF121821)).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        @Composable fun k(label: String, active: Boolean = false, onClick: () -> Unit) = TextButton(onClick = onClick,
            modifier = Modifier.heightIn(min = 44.dp).background(if (active) Color(0x552BD9A8) else Color(0xFF18202B), MaterialTheme.shapes.small)) {
            Text(label, color = if (active) Color(0xFF2BD9A8) else Color(0xFFE7ECF3), fontFamily = FontFamily.Monospace, fontSize = 15.sp)
        }
        // The compose box first: it is where long messages (to Claude, say) are easiest to type.
        TextButton(onClick = onCompose, modifier = Modifier.heightIn(min = 44.dp)
            .background(if (composing) Color(0x552BD9A8) else Color(0xFF18202B), MaterialTheme.shapes.small)) {
            Icon(Icons.Rounded.EditNote, "Compose box", tint = if (composing) Color(0xFF2BD9A8) else Color(0xFFE7ECF3))
        }
        k("Esc") { onKey("esc") }
        k("Tab") { onKey("tab") }
        k("Ctrl", ctrl, onCtrl)
        k("Alt", alt, onAlt)
        k("^C") { onKey("ctrlc") }
        k("←") { onKey("left") }; k("↑") { onKey("up") }; k("↓") { onKey("down") }; k("→") { onKey("right") }
        listOf("|", "/", "-", "~", "$", "*", ":", "\"", "'", "&", ">").forEach { s -> k(s) { onText(s) } }
        k("Home") { onKey("home") }; k("End") { onKey("end") }; k("PgUp") { onKey("pgup") }; k("PgDn") { onKey("pgdn") }
    }
}

/**
 * Type with the full keyboard — suggestions, autocorrect, voice typing — and send the finished text to the terminal in
 * one go. The terminal itself takes plain keys only, so this is the place for prose.
 */
@Composable
private fun ComposeBar(text: String, onText: (String) -> Unit, sent: List<String>, onSend: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    var recent by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().background(Color(0xFF121821)).padding(start = 4.dp, end = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        // Recently sent: pick one to put it back in the box (to send again, or fix and send)
        Box {
            IconButton(onClick = { recent = true }, enabled = sent.isNotEmpty()) {
                Icon(Icons.Rounded.History, "Recently sent", tint = if (sent.isNotEmpty()) Color(0xFFE7ECF3) else Color(0xFF5B6676))
            }
            DropdownMenu(recent, { recent = false }) {
                sent.forEach { line ->
                    DropdownMenuItem(text = { Text(line, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        onClick = { recent = false; onText(line); runCatching { focus.requestFocus() } })
                }
            }
        }
        OutlinedTextField(text, onText, modifier = Modifier.weight(1f).focusRequester(focus), maxLines = 5,
            placeholder = { Text("Type or dictate, then send", color = Color(0xFF8D99AB)) },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color(0xFFE7ECF3)),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = true))
        // Send types the text and presses Return (an empty box just presses Return)
        IconButton(onClick = onSend) {
            Icon(Icons.AutoMirrored.Rounded.Send, "Send", tint = if (text.isNotBlank()) Color(0xFF2BD9A8) else Color(0xFF5B6676))
        }
    }
}

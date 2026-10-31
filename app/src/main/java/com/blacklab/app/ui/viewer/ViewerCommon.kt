package com.blacklab.app.ui.viewer

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.blacklab.app.core.Files
import com.blacklab.app.files.FileKinds
import com.blacklab.app.files.FileOps
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.library.mediaInfo
import com.blacklab.app.ui.theme.LocalBlColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** An item in a viewer's overflow menu. */
data class MenuItem(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/**
 * The ⋮ menu every viewer shares: viewer-specific [items] first, then share, open-with, save a copy
 * into BlackLab (for files opened from other apps), details and copy path.
 */
@Composable
fun FileMenu(file: File, items: List<MenuItem> = emptyList()) {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "More") }
    DropdownMenu(open, { open = false }) {
        items.forEach { m -> DropdownMenuItem(text = { Text(m.label) }, leadingIcon = { Icon(m.icon, null) }, onClick = { open = false; m.onClick() }) }
        if (items.isNotEmpty()) HorizontalDivider()
        DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = { open = false; Files.share(ctx, listOf(file)) })
        DropdownMenuItem(text = { Text("Open with…") }, leadingIcon = { Icon(Icons.Default.OpenInNew, null) }, onClick = { open = false; Files.open(ctx, file) })
        if (!app.storage.contains(file)) DropdownMenuItem(text = { Text("Save to BlackLab") }, leadingIcon = { Icon(Icons.Default.SaveAlt, null) }, onClick = {
            open = false
            app.io {
                val f = runCatching { FileOps.saveToBlackLab(app, file) }.getOrNull()
                withContext(Dispatchers.Main) { shell.toast(if (f != null) "Saved to BlackLab › ${f.parentFile?.name}" else "Couldn't save a copy") }
            }
        })
        DropdownMenuItem(text = { Text("Details") }, leadingIcon = { Icon(Icons.Default.Info, null) }, onClick = { open = false; info = true })
    }
    if (info) {
        val details = remember(file) { mediaInfo(file) + ("Type" to FileKinds.of(file).label) }
        AlertDialog(
            onDismissRequest = { info = false },
            title = { Text("Details") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    details.forEach { (k, v) ->
                        Text(k, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                        Text(v, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { info = false }) { Text("Close") } },
        )
    }
}

/**
 * Read-only HTML renderer with JavaScript, file and network access off — safe for documents from anywhere.
 * [dark] restyles plain documents for the dark theme; [zoom] is a text size percentage.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlView(html: String, modifier: Modifier = Modifier, dark: Boolean = LocalBlColors.current.isDark, zoom: Int = 100, extraCss: String = "", allowJs: Boolean = false) {
    val page = remember(html, dark, extraCss) { wrapReadable(html, dark, extraCss) }
    AndroidView(
        factory = { c ->
            WebView(c).apply {
                settings.javaScriptEnabled = allowJs
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = false
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest) = true
                }
            }
        },
        update = { w ->
            w.settings.textZoom = zoom
            if (w.tag != page) { w.tag = page; w.loadDataWithBaseURL(null, page, "text/html", "UTF-8", null) }
        },
        modifier = modifier,
    )
}

fun wrapReadable(body: String, dark: Boolean, extraCss: String = ""): String {
    val fg = if (dark) "#E6E8EC" else "#1B1D21"
    val bg = if (dark) "#0E0F12" else "#FFFFFF"
    val muted = if (dark) "#9AA0A8" else "#5F6670"
    val line = if (dark) "#2A2D33" else "#D9DCE1"
    val accent = if (dark) "#5FE3BE" else "#0A7D63"
    return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline'; font-src data:;">
<style>
html,body{background:$bg;color:$fg;font:16px/1.6 -apple-system,Roboto,'Segoe UI',sans-serif;margin:0;padding:0;word-wrap:break-word}
body{padding:18px 18px 80px}
h1,h2,h3,h4{line-height:1.25;margin:1.2em 0 .5em}
a{color:$accent} img,svg,video{max-width:100%;height:auto;border-radius:6px}
table{border-collapse:collapse;max-width:100%;display:block;overflow-x:auto;margin:12px 0}
td,th{border:1px solid $line;padding:6px 10px;vertical-align:top} th{background:${if (dark) "#1A1C20" else "#F2F4F7"}}
blockquote{border-left:3px solid $accent;margin:12px 0;padding:2px 14px;color:$muted}
pre,code{font-family:'Roboto Mono',monospace;background:${if (dark) "#16181C" else "#F2F4F7"};border-radius:6px}
pre{padding:12px;overflow-x:auto} code{padding:1px 5px}
hr{border:none;border-top:1px solid $line;margin:24px 0}
.slide{background:${if (dark) "#16181C" else "#F7F8FA"};border:1px solid $line;border-radius:14px;padding:18px 20px;margin:0 0 18px;position:relative;aspect-ratio:auto}
.slide .num{position:absolute;top:10px;right:14px;color:$muted;font-size:12px}
.slide h2{margin-top:0} details{color:$muted;margin-top:10px}
$extraCss
</style></head><body>$body</body></html>"""
}

@Composable
fun BusyOverlay(text: String, progress: Float? = null) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
        Column(Modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (progress != null) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }) else CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(text)
        }
    }
}

/** Opens [f] in BlackLab's own viewer/player for its type. */
fun openInApp(shell: com.blacklab.app.ui.components.Shell, app: com.blacklab.app.AppContainer, f: File) {
    if (f.isDirectory) return
    shell.go(FileKinds.route(f, app.storage.relative(f)))
}

fun encodePath(app: com.blacklab.app.AppContainer, f: File): String = Uri.encode(app.storage.relative(f))

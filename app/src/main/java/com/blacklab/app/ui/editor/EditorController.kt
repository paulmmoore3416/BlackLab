package com.blacklab.app.ui.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.blacklab.app.core.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.max

data class EditorState(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val sup: Boolean = false,
    val sub: Boolean = false,
    val ul: Boolean = false,
    val ol: Boolean = false,
    val check: Boolean = false,
    val align: String = "left",
    val block: String = "P",
    val size: Float = 11f,
    val family: String = "Calibri",
    val table: Boolean = false,
    val link: String = "",
    val image: Boolean = false,
    val lineHeight: String = "",
)

/** Kotlin side of the contenteditable editor living in assets/editor/editor.html. */
class EditorController(initialHtml: String, val placeholder: String = "Start writing…") {
    @Volatile var initialHtml: String = initialHtml
    var webView: WebView? = null
        internal set

    val state = MutableStateFlow(EditorState())
    val words = MutableStateFlow(0)
    val chars = MutableStateFlow(0)
    val ready = MutableStateFlow(false)
    val imageSelected = MutableStateFlow(false)
    val findResult = MutableStateFlow(0 to -1)
    /** Bumped on every user edit; screens debounce on it to autosave. */
    val editTick = MutableStateFlow(0L)
    val textZoom = MutableStateFlow(100)

    val bridge = EditorBridge(this)

    fun js(script: String) {
        val wv = webView ?: return
        wv.post { webView?.evaluateJavascript(script, null) }
    }

    fun exec(cmd: String, value: String? = null) = js("bl.exec(${q(cmd)}, ${value?.let { q(it) } ?: "undefined"})")
    fun block(tag: String) = js("bl.block(${q(tag)})")
    fun fontSize(pt: Float) = js("bl.fontSize(${pt})")
    fun fontName(name: String) = js("bl.fontName(${q(name)})")
    fun insertHtml(html: String) = js("bl.insertHtml(${q(html)})")
    fun insertText(text: String) = js("bl.insertText(${q(text)})")
    fun table(rows: Int, cols: Int, header: Boolean) = js("bl.table($rows, $cols, $header)")
    fun tableOp(op: String) = js("bl.tableOp(${q(op)})")
    fun image(dataUri: String) = js("bl.image(${q(dataUri)})")
    fun imageOp(op: String, value: String = "") = js("bl.imageOp(${q(op)}, ${q(value)})")
    fun link(url: String, text: String) = js("bl.link(${q(url)}, ${q(text)})")
    fun unlink() = js("bl.unlink()")
    fun indent(dir: Int) = js("bl.indent($dir)")
    fun lineHeight(v: String) = js("bl.lineHeight(${q(v)})")
    fun checklist() = js("bl.checklist()")
    fun pageBreak() = js("bl.pageBreak()")
    fun find(term: String, matchCase: Boolean) = js("bl.find(${q(term)}, $matchCase)")
    fun findStep(dir: Int) = js("bl.findStep($dir)")
    fun replaceCurrent(rep: String, term: String, matchCase: Boolean) = js("bl.replaceCurrent(${q(rep)}, ${q(term)}, $matchCase)")
    fun replaceAll(term: String, rep: String, matchCase: Boolean) = js("bl.replaceAll(${q(term)}, ${q(rep)}, $matchCase)")
    fun clearFind() = js("bl.clearFind()")
    fun setLightDesk(light: Boolean) = js("bl.setLight($light)")
    fun setHtml(html: String) { initialHtml = html; js("bl.setHtml(${q(html)})") }

    suspend fun html(): String = evalString("bl.getHtml()") ?: initialHtml
    suspend fun plainText(): String = evalString("bl.plainText()") ?: ""
    suspend fun selectedText(): String = evalString("bl.selectedText()") ?: ""

    private suspend fun evalString(script: String): String? = suspendCancellableCoroutine { cont ->
        val wv = webView
        if (wv == null || !ready.value) { cont.resume(null); return@suspendCancellableCoroutine }
        wv.post {
            val live = webView
            if (live == null) { if (cont.isActive) cont.resume(null); return@post }
            live.evaluateJavascript(script) { r ->
                val v = runCatching { JSONTokener(r).nextValue() as? String }.getOrNull()
                if (cont.isActive) cont.resume(v)
            }
        }
    }

    companion object {
        fun q(s: String): String = JSONObject.quote(s)

        /** Loads an image (EXIF-correct), downsizes it and returns a JPEG data URI for inline embedding. */
        fun imageDataUri(context: Context, uri: Uri, maxDim: Int = 1600): String? = runCatching {
            val src = ImageDecoder.createSource(context.contentResolver, uri)
            decode(src, maxDim)
        }.getOrNull()

        fun imageDataUri(file: File, maxDim: Int = 1600): String? = runCatching {
            decode(ImageDecoder.createSource(file), maxDim)
        }.getOrNull()

        private fun decode(src: ImageDecoder.Source, maxDim: Int): String {
            val bmp = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width; val h = info.size.height
                val scale = maxDim.toFloat() / max(w, h)
                if (scale < 1f) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            }
            val out = ByteArrayOutputStream()
            val hasAlpha = bmp.hasAlpha()
            bmp.compress(if (hasAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 85, out)
            val mime = if (hasAlpha) "image/png" else "image/jpeg"
            return "data:$mime;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    }
}

class EditorBridge(private val c: EditorController) {
    @JavascriptInterface fun getInitialHtml(): String = c.initialHtml
    @JavascriptInterface fun getPlaceholder(): String = c.placeholder
    @JavascriptInterface fun onReady() { c.ready.value = true }

    @JavascriptInterface fun onChange(words: Int, chars: Int, userEdit: Int) {
        c.words.value = words
        c.chars.value = chars
        if (userEdit == 1) c.editTick.value = c.editTick.value + 1
    }

    @JavascriptInterface fun onImageSelected(selected: Boolean) { c.imageSelected.value = selected }
    @JavascriptInterface fun onFind(count: Int, index: Int) { c.findResult.value = count to index }

    @JavascriptInterface fun onState(json: String) {
        runCatching {
            val o = JSONObject(json)
            c.state.value = EditorState(
                bold = o.optBoolean("bold"), italic = o.optBoolean("italic"), underline = o.optBoolean("underline"),
                strike = o.optBoolean("strike"), sup = o.optBoolean("sup"), sub = o.optBoolean("sub"),
                ul = o.optBoolean("ul"), ol = o.optBoolean("ol"), check = o.optBoolean("check"),
                align = o.optString("align", "left"), block = o.optString("block", "P"),
                size = o.optDouble("size", 11.0).toFloat(), family = o.optString("family", "Calibri"),
                table = o.optBoolean("table"), link = o.optString("link", ""), image = o.optBoolean("image"),
                lineHeight = o.optString("lineHeight", ""),
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RichEditorView(controller: EditorController, lightDesk: Boolean, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false
                settings.textZoom = controller.textZoom.value
                setBackgroundColor(if (lightDesk) 0xFFE9EBEF.toInt() else 0xFF15171B.toInt())
                isVerticalScrollBarEnabled = true
                overScrollMode = WebView.OVER_SCROLL_NEVER
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        Files.openUrl(view.context, request.url.toString())
                        return true
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        view.evaluateJavascript("bl.setLight($lightDesk)", null)
                    }
                }
                addJavascriptInterface(controller.bridge, "Android")
                controller.webView = this
                loadUrl("file:///android_asset/editor/editor.html")
            }
        },
        update = { wv ->
            if (wv.settings.textZoom != controller.textZoom.value) wv.settings.textZoom = controller.textZoom.value
        },
        onRelease = { wv ->
            if (controller.webView === wv) controller.webView = null
            controller.ready.value = false
            wv.removeJavascriptInterface("Android")
            wv.destroy()
        },
    )
}

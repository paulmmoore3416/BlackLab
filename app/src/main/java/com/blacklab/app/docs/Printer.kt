package com.blacklab.app.docs

import android.annotation.SuppressLint
import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.blacklab.app.data.ContentStore

/** High-fidelity printing / "Save as PDF" through the system print dialog. */
object Printer {
    // Keep a strong reference until the print job has been handed off.
    @SuppressLint("StaticFieldLeak")
    private var pending: WebView? = null

    fun print(context: Context, title: String, bodyHtml: String, page: PageSetup) {
        val wv = WebView(context)
        pending = wv
        wv.settings.javaScriptEnabled = false
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                val pm = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
                val media = when (page.size) {
                    "A4" -> PrintAttributes.MediaSize.ISO_A4
                    "A5" -> PrintAttributes.MediaSize.ISO_A5
                    "LEGAL" -> PrintAttributes.MediaSize.NA_LEGAL
                    else -> PrintAttributes.MediaSize.NA_LETTER
                }.let { if (page.landscape) it.asLandscape() else it.asPortrait() }
                val attrs = PrintAttributes.Builder().setMediaSize(media).build()
                pm.print(title.ifBlank { "BlackLab document" }, view.createPrintDocumentAdapter(title), attrs)
                view.postDelayed({ if (pending === view) pending = null }, 60_000)
            }
        }
        val css = page.css() + " body { max-width: none; padding: 0; } .page-break { border: none; margin: 0; }"
        wv.loadDataWithBaseURL(null, ContentStore.wrapHtml(title, bodyHtml, css), "text/html", "utf-8", null)
    }
}

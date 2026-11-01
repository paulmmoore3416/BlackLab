package com.blacklab.app.ui.signature

import androidx.compose.ui.input.nestedscroll.nestedScroll
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.blacklab.app.AppContainer
import com.blacklab.app.core.Folder
import com.blacklab.app.core.Keys
import com.blacklab.app.core.Signatures
import com.blacklab.app.core.Time
import com.blacklab.app.docs.DocExporter
import com.blacklab.app.docs.DocImporter
import com.blacklab.app.docs.ExportFormat
import com.blacklab.app.docs.PageSetup
import com.blacklab.app.files.PdfTools
import com.blacklab.app.ui.components.BlCard
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.ConfirmDialog
import com.blacklab.app.ui.components.LabeledSwitchRow
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.SectionHeader
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.components.rememberDocumentScanner
import com.blacklab.app.ui.components.settingsState
import com.blacklab.app.ui.viewer.BusyOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Settings › Signatures (also in the drawer): your saved signatures and initials, and "Sign a document". */
@Composable
fun SignaturesScreen() {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val s = settingsState()
    val scope = rememberCoroutineScope()
    val sigs = rememberSignatures()
    var capture by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Signatures.Sig?>(null) }
    var renaming by remember { mutableStateOf<Signatures.Sig?>(null) }
    var details by remember { mutableStateOf(false) }

    fun openToSign(f: File) = shell.go("view?path=${Uri.encode(f.absolutePath)}&sign=true")
    fun prepare(label: String, block: suspend () -> File?) {
        busy = label
        scope.launch {
            val f = withContext(Dispatchers.IO) { runCatching { block() }.getOrNull() }
            busy = null
            if (f == null) shell.toast("BlackLab can't open that file for signing") else openToSign(f)
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) prepare("Preparing to sign…") { SignDocs.toPdf(ctx, app, uri) }
    }
    val scanner = rememberDocumentScanner(app, s.activeProfile) { r ->
        val pdf = r.pdf ?: r.pages.takeIf { it.isNotEmpty() }?.let { pages -> File(ctx.cacheDir, "scan_${System.currentTimeMillis()}.pdf").also { PdfTools.init(ctx); PdfTools.imagesToPdf(pages, it, "FIT") } }
        if (pdf != null) prepare("Preparing to sign…") { SignDocs.copyIn(app, pdf, "Scan " + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm"))) }
    }

    val titleScroll = com.blacklab.app.ui.components.rememberCollapsingTitle()
    Scaffold(Modifier.nestedScroll(titleScroll.nestedScrollConnection), topBar = { BlTopBar("Signatures", onBack = { shell.back() }, showMenu = false, scrollBehavior = titleScroll, large = true) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                BlCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(10.dp))
                        Text("Kept encrypted on this phone", style = MaterialTheme.typography.titleSmall)
                    }
                    Text("Your signatures and initials are encrypted with a key that never leaves this phone. They aren't backed up, synced to BlackLab Desktop or shared, " +
                        "and they only end up in a document you choose to sign.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                LabeledSwitchRow("Confirm before signing", "Fingerprint or screen lock before a saved signature is placed", s.sigConfirm, icon = Icons.Rounded.Fingerprint) { v ->
                    app.settings.update { putBoolean(Keys.SIG_CONFIRM, v) }
                }
            }

            item { SectionHeader("Sign a document") }
            item {
                BlCard(Modifier.fillMaxWidth()) {
                    Text("Pick a PDF, Word document, picture or text file, or scan a paper one. BlackLab turns it into a PDF, you place your signature, initials and the date, and it's saved to Documents › Signed.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { pick.launch(SignDocs.MIME) }) { Icon(Icons.Rounded.Description, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Choose a file") }
                        OutlinedButton(onClick = scanner) { Icon(Icons.Rounded.DocumentScanner, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Scan & sign") }
                    }
                    TextButton(onClick = { details = true }, modifier = Modifier.padding(top = 4.dp)) { Text("My details for forms") }
                    Text("PDF forms: open the PDF › ⋮ › Fill in form › Auto-fill my details, then Sign here on each signature box. " +
                        "You can also sign from a PDF › Mark up & sign · Documents › Insert › Signature · a notebook page › Insert › Signature.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
                }
            }

            listOf(Signatures.SIGNATURE to "Signatures", Signatures.INITIALS to "Initials").forEach { (kind, title) ->
                val list = sigs.filter { it.kind == kind }
                item { SectionHeader(title, action = if (kind == Signatures.INITIALS) "Add initials" else "Add signature") { capture = kind } }
                if (list.isEmpty()) item {
                    BlCard(Modifier.fillMaxWidth()) {
                        Text(if (kind == Signatures.INITIALS) "No initials yet. Some forms ask you to initial each page." else "No signature yet. Draw it once and use it on any document.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { capture = kind }) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text(if (kind == Signatures.INITIALS) "Add initials" else "Add signature") }
                    }
                }
                items(list, key = { it.id }) { sig ->
                    var menu by remember { mutableStateOf(false) }
                    BlCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SignatureThumb(sig, height = 64)
                            Spacer(Modifier.size(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(sig.label, style = MaterialTheme.typography.titleSmall)
                                    if (sig.isDefault) { Spacer(Modifier.size(6.dp)); Icon(Icons.Rounded.Star, "Default", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary) }
                                }
                                Text((if (sig.isDefault) "Default · " else "") + "Added " + Time.relativeDay(Time.toDate(sig.created)),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                            DropdownMenu(menu, { menu = false }) {
                                if (!sig.isDefault) DropdownMenuItem(text = { Text("Make default") }, onClick = { menu = false; app.io { Signatures.setDefault(ctx, sig.id) } })
                                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = sig })
                                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; deleting = sig })
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.size(32.dp)) }
        }
    }

    capture?.let { kind -> SignatureCaptureDialog(kind, onDismiss = { capture = null }) { _, _ -> capture = null; shell.toast(if (kind == Signatures.INITIALS) "Initials saved" else "Signature saved") } }
    deleting?.let { sig -> ConfirmDialog("Delete ${sig.label.lowercase()}?", "It's removed from this phone. Documents you already signed keep it.", "Delete", onDismiss = { deleting = null }) { app.io { Signatures.delete(ctx, sig.id) }; deleting = null } }
    renaming?.let { sig ->
        var name by remember(sig.id) { mutableStateOf(sig.label) }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Rename") },
            text = { OutlinedTextField(name, { name = it.take(40) }, singleLine = true, label = { Text("Name (e.g. Full signature, Short)") }) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { app.io { Signatures.rename(ctx, sig.id, name.trim()) }; renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } })
    }
    if (details) com.blacklab.app.ui.viewer.FormDetailsDialog(com.blacklab.app.core.FormAutofill.profile(ctx, s), onDismiss = { details = false }) { p ->
        com.blacklab.app.core.FormAutofill.saveContact(ctx, p); details = false; shell.toast("Details saved")
    }
    busy?.let { BusyOverlay(it) }
}

/** Turns anything signable into a PDF inside BlackLab › Documents › Signed. */
object SignDocs {
    val MIME = arrayOf(
        "application/pdf", "image/*", "text/*", "application/rtf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/msword",
        "application/vnd.oasis.opendocument.text",
    )

    private fun signedDir(app: AppContainer) = File(app.storage.dir(app.settings.current.activeProfile, Folder.DOCUMENTS), "Signed").also { it.mkdirs() }

    fun copyIn(app: AppContainer, pdf: File, title: String): File {
        val out = com.blacklab.app.core.Files.unique(signedDir(app), title, "pdf")
        pdf.copyTo(out, true); app.storage.scan(out)
        return out
    }

    fun toPdf(context: Context, app: AppContainer, uri: Uri): File? {
        val name = com.blacklab.app.core.Files.displayName(context, uri)
        val title = name.substringBeforeLast('.').ifBlank { "Document" }
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = context.contentResolver.getType(uri).orEmpty()
        val tmp = File(context.cacheDir, "sign_in_${System.currentTimeMillis()}.$ext")
        try {
            if (!com.blacklab.app.core.Files.copyFromUri(context, uri, tmp)) return null
            PdfTools.init(context)
            return when {
                ext == "pdf" || mime == "application/pdf" -> copyIn(app, tmp, title)
                mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp", "gif") -> {
                    val pdf = File(context.cacheDir, "sign_img_${System.currentTimeMillis()}.pdf")
                    PdfTools.imagesToPdf(listOf(tmp), pdf, "FIT"); copyIn(app, pdf, title).also { pdf.delete() }
                }
                ext in setOf("docx", "docm", "dotx", "html", "htm", "md", "markdown", "rtf", "txt", "csv", "log") || mime.startsWith("text/") || mime.contains("wordprocessingml") -> {
                    val html = DocImporter.import(context, Uri.fromFile(tmp)).html
                    DocExporter.export(html, title, PageSetup(), ExportFormat.PDF, signedDir(app)).also { app.storage.scan(it) }
                }
                else -> null
            }
        } finally { tmp.delete() }
    }
}

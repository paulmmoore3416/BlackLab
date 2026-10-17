package com.blacklab.app.ui.components

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.blacklab.app.AppContainer
import com.blacklab.app.core.BlackLabStorage
import com.blacklab.app.core.Files
import com.blacklab.app.core.Folder
import com.blacklab.app.core.Profile
import com.blacklab.app.data.ContentStore
import com.blacklab.app.data.DocumentEntity
import com.blacklab.app.data.TaskEntity
import com.blacklab.app.data.TaskOps
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/** On-device text recognition (ML Kit via Google Play services). */
object Ocr {
    suspend fun recognize(context: Context, uri: Uri): String = suspendCancellableCoroutine { cont ->
        val image = runCatching { InputImage.fromFilePath(context, uri) }.getOrNull()
        if (image == null) { cont.resume(""); return@suspendCancellableCoroutine }
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(image)
            .addOnSuccessListener { if (cont.isActive) cont.resume(it.text) }
            .addOnFailureListener { if (cont.isActive) cont.resume("") }
    }

    suspend fun recognize(context: Context, file: File): String = recognize(context, Uri.fromFile(file))
}

data class ScanResult(val pdf: File?, val pages: List<File>)

/**
 * Launches Google's document scanner (edge detection, perspective fix, cleanup) and saves the
 * PDF + page images into BlackLab/<profile>/Scans.
 */
@Composable
fun rememberDocumentScanner(app: AppContainer, profile: Profile, onResult: (ScanResult) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val result = GmsDocumentScanningResult.fromActivityResultIntent(r.data) ?: return@rememberLauncherForActivityResult
        app.io {
            val dir = app.storage.dir(profile, Folder.SCANS)
            val stamp = BlackLabStorage.timestamp()
            val pdf = result.pdf?.uri?.let { uri ->
                File(dir, "Scan_$stamp.pdf").takeIf { Files.copyFromUri(ctx, uri, it) }
            }
            val pages = result.pages.orEmpty().mapIndexedNotNull { i, page ->
                val f = File(dir, "Scan_${stamp}_p${i + 1}.jpg")
                if (Files.copyFromUri(ctx, page.imageUri, f)) f else null
            }
            pdf?.let(app.storage::scan)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(ScanResult(pdf, pages)) }
        }
    }
    return {
        val activity = ctx.findActivity()
        if (activity != null) {
            val options = GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)
                .setPageLimit(50)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG, GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build()
            GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
                .addOnSuccessListener { sender -> launcher.launch(IntentSenderRequest.Builder(sender).build()) }
                .addOnFailureListener { Toast.makeText(ctx, "Document scanner unavailable: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
}

/** Google code scanner (QR, barcodes, asset tags) – no camera permission needed. */
@Composable
fun rememberCodeScanner(onResult: (Barcode) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    return {
        GmsBarcodeScanning.getClient(ctx).startScan()
            .addOnSuccessListener { onResult(it) }
            .addOnFailureListener { Toast.makeText(ctx, "Scanner unavailable: ${it.message}", Toast.LENGTH_LONG).show() }
    }
}

@Composable
fun CodeResultDialog(code: Barcode, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val app = rememberApp()
    val shell = LocalShell.current
    val profile = currentProfile()
    val value = code.rawValue ?: code.displayValue ?: ""
    val url = code.url?.url ?: value.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (code.valueType == Barcode.TYPE_URL) "Link" else if (code.format == Barcode.FORMAT_QR_CODE) "QR code" else "Barcode") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectionContainer { Text(value, style = MaterialTheme.typography.bodyLarge) }
                if (url != null) FilledTonalButton(onClick = { Files.openUrl(ctx, url); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text("Open link") }
                OutlinedButton(onClick = {
                    app.io { TaskOps.save(app, TaskEntity(profile = profile.name, title = "Asset $value", notes = "Scanned ${com.blacklab.app.core.Time.dateTime(System.currentTimeMillis())}\n$value")) }
                    shell.toast("Task created"); onDismiss()
                }, modifier = Modifier.fillMaxWidth()) { Text("Create task / work order") }
                OutlinedButton(onClick = {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("code", value))
                    onDismiss()
                }, modifier = Modifier.fillMaxWidth()) { Text("Copy") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Shows recognized text with options to copy or turn into a document / task. */
@Composable
fun OcrResultDialog(text: String, sourceName: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val app = rememberApp()
    val shell = LocalShell.current
    val profile = currentProfile()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Extracted text") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (text.isBlank()) Text("No text found.") else SelectionContainer { Text(text) }
            }
        },
        confirmButton = {
            if (text.isNotBlank()) TextButton(onClick = {
                app.io {
                    val title = "Text from $sourceName"
                    val html = ContentStore.textToHtml(text)
                    val rel = app.content.writeDocument(profile, title, "", "", html)
                    val id = app.db.documents().insert(DocumentEntity(profile = profile.name, title = title, filePath = rel, preview = ContentStore.preview(text), wordCount = ContentStore.wordCount(text)))
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { shell.go("doc/$id") }
                }
                onDismiss()
            }) { Text("Create document") }
        },
        dismissButton = {
            TextButton(onClick = {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("text", text))
                Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show(); onDismiss()
            }) { Text("Copy") }
        },
    )
}

/** Wraps a scan: optional OCR of each page into a new editable document. */
@Composable
fun ScanDoneDialog(result: ScanResult, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val app = rememberApp()
    val shell = LocalShell.current
    val profile = currentProfile()
    var working by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scan saved") },
        text = {
            Text(
                (result.pdf?.let { "PDF: Scans/${it.name}\n" } ?: "") + "${result.pages.size} page image(s) saved." +
                    if (working) "\n\nReading text…" else "",
            )
        },
        confirmButton = {
            TextButton(enabled = !working && result.pages.isNotEmpty(), onClick = {
                working = true
                app.io {
                    val text = result.pages.map { Ocr.recognize(ctx, it) }.joinToString("\n\n").trim()
                    val title = "Scan ${com.blacklab.app.core.Time.date(System.currentTimeMillis())}"
                    val html = ContentStore.textToHtml(text.ifBlank { "(no text found)" })
                    val rel = app.content.writeDocument(profile, title, "Scans", "", html)
                    val id = app.db.documents().insert(DocumentEntity(profile = profile.name, title = title, folder = "Scans", filePath = rel,
                        preview = ContentStore.preview(text), wordCount = ContentStore.wordCount(text)))
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onDismiss(); shell.go("doc/$id") }
                }
            }) { Text("Convert to editable text") }
        },
        dismissButton = {
            Column {
                result.pdf?.let { pdf -> TextButton(onClick = { Files.open(ctx, pdf) }) { Text("Open PDF") } }
                result.pdf?.let { pdf -> TextButton(onClick = { Files.share(ctx, listOf(pdf)) }) { Text("Share") } }
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        },
    )
}

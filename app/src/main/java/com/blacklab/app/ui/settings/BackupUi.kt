package com.blacklab.app.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.blacklab.app.AppContainer
import com.blacklab.app.core.BackupCrypto
import com.blacklab.app.core.BackupManager
import com.blacklab.app.core.Files
import com.blacklab.app.core.Keys
import com.blacklab.app.core.Settings
import com.blacklab.app.core.Time
import com.blacklab.app.ui.components.BlCard
import com.blacklab.app.ui.components.ConfirmDialog
import com.blacklab.app.ui.components.LabeledSwitchRow
import com.blacklab.app.ui.components.Shell
import com.blacklab.app.ui.components.SettingRow
import com.blacklab.app.ui.components.findActivity
import com.blacklab.app.ui.theme.LocalBlColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.system.exitProcess

/** UI state for Settings › Storage & backup. */
class BackupUi(private val app: AppContainer, private val shell: Shell) {
    var picking by mutableStateOf(false)
    var settingPassphrase by mutableStateOf(false)
    /** File or Uri waiting for its passphrase. */
    var askPassphraseFor by mutableStateOf<Any?>(null)
    var passphraseError by mutableStateOf<String?>(null)
    var busy by mutableStateOf<String?>(null)
    var inspected by mutableStateOf<BackupManager.Inspection?>(null)
    var verifyOnly by mutableStateOf(false)
    var confirmDeletePlain by mutableStateOf(false)
    var hasPassphrase by mutableStateOf(app.backups.hasPassphrase)
    var plainCount by mutableIntStateOf(app.backups.unencryptedBackups().size)

    fun refresh() { hasPassphrase = app.backups.hasPassphrase; plainCount = app.backups.unencryptedBackups().size }

    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }

    fun open(target: Any, verify: Boolean, passphrase: CharArray? = null) {
        verifyOnly = verify
        busy = if (verify) "Checking the backup…" else "Opening the backup…"
        app.io {
            val r = runCatching {
                when (target) {
                    is File -> app.backups.inspect(target, passphrase)
                    is Uri -> app.backups.inspect(target, passphrase)
                    else -> error("Unknown backup")
                }
            }
            main {
                busy = null
                r.onSuccess { askPassphraseFor = null; passphraseError = null; inspected = it }
                    .onFailure { e ->
                        when (e) {
                            is BackupCrypto.NeedsPassphrase -> { askPassphraseFor = target; passphraseError = null }
                            is BackupCrypto.WrongPassphrase -> { askPassphraseFor = target; passphraseError = "That passphrase doesn't open this backup" }
                            else -> { askPassphraseFor = null; shell.toast(e.message ?: "That backup can't be read") }
                        }
                    }
            }
        }
    }

    fun verifyLatest() {
        val f = app.backups.listBackups().firstOrNull() ?: return shell.toast("No backups yet — tap Back up now")
        open(f, verify = true)
    }

    fun backupNow(share: (File) -> Unit) {
        busy = "Backing up…"
        app.io {
            val f = runCatching { app.backups.backupNow() }
            main {
                busy = null; refresh()
                f.onSuccess { shell.toast("Encrypted backup saved: ${it.name}", "Share") { share(it) } }.onFailure { shell.toast("Backup failed: ${it.message}") }
            }
        }
    }

    fun setPassphrase(p: CharArray) {
        busy = "Securing backups…"
        app.io {
            val r = runCatching { app.backups.setPassphrase(p) }
            main {
                busy = null; refresh()
                r.onSuccess { shell.toast("Backup passphrase set. New backups can be restored on any phone with it.") }.onFailure { shell.toast(it.message ?: "Couldn't set the passphrase") }
            }
        }
    }

    fun restore(i: BackupManager.Inspection, onDone: () -> Unit) {
        busy = "Restoring…"
        app.io {
            val r = runCatching { app.backups.restore(i) }
            main {
                busy = null
                r.onSuccess { onDone() }.onFailure { inspected = null; shell.toast(it.message ?: "Restore failed") }
            }
        }
    }

    fun deletePlain() {
        val n = app.backups.unencryptedBackups().count { it.delete() }
        refresh()
        shell.toast("Deleted $n unencrypted backup${if (n == 1) "" else "s"}")
    }
}

fun LazyListScope.backupItems(ui: BackupUi, s: Settings, app: AppContainer, share: (File) -> Unit) {
    if (!ui.hasPassphrase) item {
        BlCard(Modifier.fillMaxWidth().padding(vertical = 4.dp), accent = LocalBlColors.current.warning) {
            Text("Set a backup passphrase", style = MaterialTheme.typography.titleSmall)
            Text("Backups are encrypted. Until you set a passphrase they can only be restored on this phone, so they won't help if the phone is lost or BlackLab is reinstalled.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { ui.settingPassphrase = true }) { Text("Set passphrase") }
        }
    }
    item {
        SettingRow("Back up now",
            (if (s.lastBackupDay > 0) "Last backup ${Time.date(java.time.LocalDate.ofEpochDay(s.lastBackupDay))}" else "Database, settings, Garage, conversations") + " · encrypted · BlackLab/Backups",
            Icons.Rounded.Backup) { ui.backupNow(share) }
    }
    item {
        SettingRow("Backup passphrase", if (ui.hasPassphrase) "Set — backups made from now on restore on any phone" else "Not set — backups restore on this phone only",
            if (ui.hasPassphrase) Icons.Rounded.Lock else Icons.Rounded.Key) { ui.settingPassphrase = true }
    }
    item { LabeledSwitchRow("Automatic daily backup", "Keeps the 14 most recent", s.autoBackup, icon = Icons.Rounded.Autorenew) { v -> app.settings.update { putBoolean(Keys.AUTO_BACKUP, v) } } }
    item {
        SettingRow("Verify latest backup", "Decrypts and checks it without changing anything", Icons.Rounded.Verified) {
            ui.verifyLatest()
        }
    }
    item { SettingRow("Restore from backup", "Replace current data with a backup", Icons.Rounded.Restore) { ui.picking = true } }
    if (ui.plainCount > 0) item {
        SettingRow("Delete ${ui.plainCount} unencrypted backup${if (ui.plainCount == 1) "" else "s"}", "Made before 5.10 — readable by any app with storage access", Icons.Rounded.DeleteSweep) { ui.confirmDeletePlain = true }
    }
}

@Composable
fun BackupDialogs(ui: BackupUi, app: AppContainer) {
    val ctx = LocalContext.current
    val activity = ctx.findActivity()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) ui.open(uri, verify = false) }

    ui.busy?.let { msg ->
        AlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Text(msg) }, text = { CircularProgressIndicator() })
    }

    if (ui.picking) {
        val backups = remember { app.backups.listBackups() }
        AlertDialog(
            onDismissRequest = { ui.picking = false },
            title = { Text("Restore from backup") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    if (backups.isEmpty()) Text("No backups in BlackLab/Backups yet.")
                    backups.forEach { f ->
                        val label = f.nameWithoutExtension.removePrefix("BlackLab_backup_").removePrefix("BlackLab_before-restore_")
                        val kind = when {
                            f.name.startsWith("BlackLab_before-restore_") -> "Before a restore · "
                            f.extension == "zip" -> "Unencrypted · "
                            else -> ""
                        }
                        SettingRow(label, "$kind${Files.formatSize(f.length())} · ${Time.dateTime(f.lastModified())}", Icons.Rounded.Restore) {
                            ui.picking = false; ui.open(f, verify = false)
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    TextButton(onClick = { ui.picking = false; picker.launch(arrayOf("*/*")) }) { Text("Choose a backup file…") }
                }
            },
            confirmButton = { TextButton(onClick = { ui.picking = false }) { Text("Close") } },
        )
    }

    if (ui.settingPassphrase) PassphraseDialog(
        title = if (ui.hasPassphrase) "Change backup passphrase" else "Set a backup passphrase",
        body = "You'll need it to restore on a new phone or after reinstalling. BlackLab can't recover it for you. Backups made before this change keep the passphrase they were made with.",
        confirm = true, error = null,
        onDismiss = { ui.settingPassphrase = false },
    ) { p -> ui.settingPassphrase = false; ui.setPassphrase(p) }

    ui.askPassphraseFor?.let { target ->
        PassphraseDialog(
            title = "Backup passphrase",
            body = "This backup was made on another phone or install. Enter the backup passphrase that was set when it was made.",
            confirm = false, error = ui.passphraseError,
            onDismiss = { ui.askPassphraseFor = null; ui.passphraseError = null },
        ) { p -> ui.open(target, ui.verifyOnly, p) }
    }

    ui.inspected?.let { i ->
        val made = (if (i.appVersion.isNotBlank()) "BlackLab ${i.appVersion} · " else "") + Time.dateTime(i.created)
        val extras = buildList {
            if (i.files.any { it.startsWith("garage/") }) add("Garage")
            if (i.files.any { it.startsWith("knowledge/") }) add("conversations")
            if (i.files.any { it.startsWith("assistant/") }) add("assistant history")
            if (i.files.any { it.startsWith("facility/") }) add("Facility")
        }
        val details = "Made $made\n${i.summary}" + (if (extras.isNotEmpty()) "\nAlso: ${extras.joinToString(", ")}" else "") +
            (if (!i.encrypted) "\nThis is an old unencrypted backup." else "")
        if (ui.verifyOnly) AlertDialog(
            onDismissRequest = { app.backups.discard(); ui.inspected = null },
            title = { Text("Backup checks out") },
            text = { Text("$details\n\nIt decrypted, its database passed an integrity check, and it can be restored on this version of BlackLab.") },
            confirmButton = { TextButton(onClick = { app.backups.discard(); ui.inspected = null }) { Text("Done") } },
        ) else ConfirmDialog("Restore this backup?",
            "$details\n\nYour current tasks, events, notes, courses, Garage and settings will be replaced. They're saved first as a “before-restore” backup. Files in the BlackLab folder aren't touched. BlackLab will close; reopen it afterwards.",
            "Restore", onDismiss = { app.backups.discard(); ui.inspected = null }) {
            ui.restore(i) { activity?.finishAffinity(); exitProcess(0) }
        }
    }

    if (ui.confirmDeletePlain) ConfirmDialog("Delete unencrypted backups?",
        "Removes ${ui.plainCount} backup${if (ui.plainCount == 1) "" else "s"} made before 5.10. Make an encrypted backup first if you haven't today.",
        onDismiss = { ui.confirmDeletePlain = false }) { ui.deletePlain() }
}

@Composable
private fun PassphraseDialog(title: String, body: String, confirm: Boolean, error: String?, onDismiss: () -> Unit, onDone: (CharArray) -> Unit) {
    var p by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    val tooShort = confirm && p.length < 8
    val mismatch = confirm && p2.isNotEmpty() && p != p2
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(p, { p = it }, label = { Text("Passphrase") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), isError = error != null,
                    supportingText = { (error ?: if (confirm && p.isNotEmpty() && tooShort) "At least 8 characters" else null)?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth())
                if (confirm) OutlinedTextField(p2, { p2 = it }, label = { Text("Type it again") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), isError = mismatch,
                    supportingText = { if (mismatch) Text("Doesn't match") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = p.isNotEmpty() && !tooShort && (!confirm || p == p2), onClick = { onDone(p.toCharArray()); p = ""; p2 = "" }) { Text(if (confirm) "Save" else "Open") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

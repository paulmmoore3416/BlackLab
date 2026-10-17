package com.blacklab.app.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.blacklab.app.BuildConfig
import com.blacklab.app.MainActivity
import com.blacklab.app.core.Files
import com.blacklab.app.core.Keys
import com.blacklab.app.core.Profile
import com.blacklab.app.core.Time
import com.blacklab.app.docs.PageSetup
import com.blacklab.app.ui.components.BlCard
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.ConfirmDialog
import com.blacklab.app.ui.components.DropdownField
import com.blacklab.app.ui.components.LabeledSwitchRow
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.SectionHeader
import com.blacklab.app.ui.components.SettingRow
import com.blacklab.app.ui.components.TextInputDialog
import com.blacklab.app.ui.components.TimePickerDialogBox
import com.blacklab.app.ui.components.findActivity
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.components.settingsState
import com.blacklab.app.ui.theme.LocalBlColors
import java.io.File
import java.time.DayOfWeek
import kotlin.system.exitProcess

@Composable
fun SettingsScreen() {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val s = settingsState()
    var edit by remember { mutableStateOf<Triple<String, String, String>?>(null) } // key, title, value
    var numberEdit by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var timeEdit by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var allFiles by remember { mutableStateOf(app.storage.hasAllFilesAccess) }
    var notifOk by remember { mutableStateOf(NotificationManagerCompat.from(ctx).areNotificationsEnabled()) }
    var restoreList by remember { mutableStateOf(false) }
    var restoreTarget by remember { mutableStateOf<Any?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        allFiles = app.storage.hasAllFilesAccess
        if (allFiles) app.storage.ensureStructure()
        notifOk = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifOk = it }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) restoreTarget = uri }
    val activity = ctx.findActivity() as? MainActivity

    Scaffold(topBar = { BlTopBar("Settings", onBack = { shell.back() }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp)) {
            item { SectionHeader("You") }
            item { SettingRow("Name", s.userName.ifBlank { "Not set" }, Icons.Default.Badge) { edit = Triple(Keys.USER_NAME, "Your name", s.userName) } }
            item { SettingRow("Job title", s.jobTitle.ifBlank { "Not set" }, Icons.Default.Work) { edit = Triple(Keys.JOB_TITLE, "Job title", s.jobTitle) } }
            item { SettingRow("Organization", s.organization.ifBlank { "Not set" }, Icons.Default.Business) { edit = Triple(Keys.ORGANIZATION, "Organization", s.organization) } }

            item { SectionHeader("Accounts") }
            item { SettingRow("Personal account name", s.personalName, Icons.Default.Person) { edit = Triple(Keys.PERSONAL_NAME, "Personal account name", s.personalName) } }
            item { SettingRow("Professional account name", s.professionalName, Icons.Default.Work) { edit = Triple(Keys.PROFESSIONAL_NAME, "Professional account name", s.professionalName) } }
            item {
                Text("Open BlackLab in", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 6.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Profile.entries.forEachIndexed { i, p ->
                        SegmentedButton(s.defaultProfile == p, { app.settings.update { putString(Keys.DEFAULT_PROFILE, p.name) } }, SegmentedButtonDefaults.itemShape(i, 2)) { Text(app.settings.profileName(p)) }
                    }
                }
            }

            item { SectionHeader("Appearance") }
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("DARK" to "Dark", "LIGHT" to "Light", "SYSTEM" to "System").forEachIndexed { i, (k, l) ->
                        SegmentedButton(s.themeMode == k, { app.settings.update { putString(Keys.THEME_MODE, k) } }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(l) }
                    }
                }
            }
            item { LabeledSwitchRow("Material You colors", "Use your wallpaper colors instead of account accents", s.dynamicColor, icon = Icons.Default.Palette) { v -> app.settings.update { putBoolean(Keys.DYNAMIC_COLOR, v) } } }

            item { SectionHeader("Security") }
            item {
                LabeledSwitchRow("Lock BlackLab", "Fingerprint, face or screen lock to open", s.appLock, icon = Icons.Default.Fingerprint) { v ->
                    if (v && activity != null) activity.authenticate("Enable app lock", "Confirm it's you") { ok -> if (ok) app.settings.update { putBoolean(Keys.APP_LOCK, true) } }
                    else app.settings.update { putBoolean(Keys.APP_LOCK, v) }
                }
            }
            if (s.appLock) item {
                DropdownField("Lock after leaving the app", listOf(0 to "Immediately", 30 to "30 seconds", 60 to "1 minute", 300 to "5 minutes", 900 to "15 minutes"), s.lockTimeoutSec,
                    { v -> app.settings.update { putInt(Keys.LOCK_TIMEOUT, v) } }, Modifier.padding(horizontal = 8.dp))
            }
            item { LabeledSwitchRow("Lock ${s.professionalName} account", "Require unlock when switching to it", s.lockProfessional, icon = Icons.Default.Lock) { v -> app.settings.update { putBoolean(Keys.LOCK_PROFESSIONAL, v) } } }

            item { SectionHeader("Storage & backup") }
            item {
                BlCard(Modifier.fillMaxWidth(), accent = if (allFiles) null else LocalBlColors.current.warning) {
                    Text(if (allFiles) "BlackLab folder: Internal storage › BlackLab" else "BlackLab folder: app storage (hidden from Files)", style = MaterialTheme.typography.titleSmall)
                    Text(app.storage.root.absolutePath, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${Files.formatSize(app.storage.freeBytes())} free on device", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!allFiles) FilledTonalButton(onClick = { ctx.startActivity(app.storage.allFilesAccessIntent()) }, modifier = Modifier.padding(top = 8.dp)) { Text("Allow All-files access") }
                }
            }
            item { SettingRow("Browse the BlackLab folder", null, Icons.Default.FolderOpen) { shell.go("library?tab=BROWSE") } }
            item {
                SettingRow("Back up now", if (s.lastBackupDay > 0) "Last backup ${Time.date(java.time.LocalDate.ofEpochDay(s.lastBackupDay))} · BlackLab/Backups" else "Database + settings → BlackLab/Backups", Icons.Default.Backup) {
                    app.io {
                        val f = runCatching { app.backups.backupNow() }
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            f.onSuccess { shell.toast("Backup saved: ${it.name}", "Share") { Files.share(ctx, listOf(it)) } }.onFailure { shell.toast("Backup failed: ${it.message}") }
                        }
                    }
                }
            }
            item { LabeledSwitchRow("Automatic daily backup", "Keeps the 14 most recent", s.autoBackup, icon = Icons.Default.Autorenew) { v -> app.settings.update { putBoolean(Keys.AUTO_BACKUP, v) } } }
            item { SettingRow("Restore from backup", "Replace current data with a backup", Icons.Default.Restore) { restoreList = true } }

            item { SectionHeader("Notifications") }
            if (!notifOk) item {
                FilledTonalButton(onClick = {
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else ctx.startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, ctx.packageName))
                }) { Text("Enable notifications") }
            }
            item { LabeledSwitchRow("Morning agenda", "Daily summary at ${Time.time(s.agendaMinute)}", s.agendaNotify, icon = Icons.Default.WbSunny) { v -> app.settings.update { putBoolean(Keys.AGENDA_NOTIFY, v) }; app.requestReminderSync() } }
            if (s.agendaNotify) item { SettingRow("Agenda time", Time.time(s.agendaMinute), Icons.Default.Schedule) { timeEdit = Keys.AGENDA_MINUTE to s.agendaMinute } }
            item { LabeledSwitchRow("Journal reminder", "Nudge at ${Time.time(s.journalMinute)} if you haven't written", s.journalReminder, icon = Icons.Default.AutoStories) { v -> app.settings.update { putBoolean(Keys.JOURNAL_REMINDER, v) }; app.requestReminderSync() } }
            if (s.journalReminder) item { SettingRow("Journal reminder time", Time.time(s.journalMinute), Icons.Default.Schedule) { timeEdit = Keys.JOURNAL_MINUTE to s.journalMinute } }
            item {
                DropdownField("Class reminders", listOf(0 to "Off", 5 to "5 min before", 10 to "10 min before", 15 to "15 min before", 30 to "30 min before", 60 to "1 hour before"), s.classReminderMinutes,
                    { v -> app.settings.update { putInt(Keys.CLASS_REMINDER, v) }; app.requestReminderSync() }, Modifier.padding(8.dp))
            }
            item { SettingRow("Default task reminder time", Time.time(s.defaultTaskReminderMinute), Icons.Default.Alarm) { timeEdit = Keys.TASK_REMINDER_MINUTE to s.defaultTaskReminderMinute } }

            item { SectionHeader("Calendar") }
            item {
                DropdownField("Week starts on", listOf(7 to "Sunday", 1 to "Monday", 6 to "Saturday"), s.weekStart, { v -> app.settings.update { putInt(Keys.WEEK_START, v) } }, Modifier.padding(8.dp))
            }
            item { SettingRow("Working hours start", Time.time(s.workStartMinute), Icons.Default.WorkHistory) { timeEdit = Keys.WORK_START to s.workStartMinute } }
            item { SettingRow("Working hours end", Time.time(s.workEndMinute), Icons.Default.WorkOff) { timeEdit = Keys.WORK_END to s.workEndMinute } }
            item {
                DropdownField("Default event length", listOf(15 to "15 min", 30 to "30 min", 45 to "45 min", 60 to "1 hour", 90 to "1.5 hours", 120 to "2 hours"), s.defaultEventMinutes,
                    { v -> app.settings.update { putInt(Keys.DEFAULT_EVENT_MIN, v) } }, Modifier.padding(8.dp))
            }
            item {
                DropdownField("Default event reminder", listOf(0 to "At start", 5 to "5 min", 10 to "10 min", 15 to "15 min", 30 to "30 min", 60 to "1 hour"), s.defaultEventReminder,
                    { v -> app.settings.update { putInt(Keys.DEFAULT_EVENT_REMINDER, v) } }, Modifier.padding(8.dp))
            }

            item { SectionHeader("Education") }
            item { SettingRow("Degree credit target", "${s.targetCredits.toInt()} credits", Icons.Default.School) { numberEdit = Triple(Keys.TARGET_CREDITS, "Credits required to graduate", s.targetCredits.toInt().toString()) } }
            item { SettingRow("Weekly study goal", Time.hoursLabel(s.weeklyStudyGoalMin), Icons.Default.Flag) { numberEdit = Triple(Keys.STUDY_GOAL, "Weekly study goal (minutes)", s.weeklyStudyGoalMin.toString()) } }
            item { SettingRow("Focus session", "${s.pomodoroWork} min", Icons.Default.Timer) { numberEdit = Triple(Keys.POMO_WORK, "Focus minutes", s.pomodoroWork.toString()) } }
            item { SettingRow("Short break", "${s.pomodoroBreak} min", Icons.Default.Coffee) { numberEdit = Triple(Keys.POMO_BREAK, "Short break minutes", s.pomodoroBreak.toString()) } }
            item { SettingRow("Long break", "${s.pomodoroLongBreak} min", Icons.Default.Weekend) { numberEdit = Triple(Keys.POMO_LONG, "Long break minutes", s.pomodoroLongBreak.toString()) } }

            item { SectionHeader("Documents") }
            item { DropdownField("Default page size", PageSetup.sizes, s.defaultPageSize, { v -> app.settings.update { putString(Keys.PAGE_SIZE, v) } }, Modifier.padding(8.dp)) }

            item { SectionHeader("About") }
            item {
                BlCard(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
                    Text("BlackLab ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.titleSmall)
                    Text("Private by design: your data stays on this device in the BlackLab folder and app database. No account, no cloud, no tracking.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Optimized for Google Pixel 10 Pro XL and newer.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    edit?.let { (key, title, value) ->
        TextInputDialog(title, value, title, onDismiss = { edit = null }) { v -> app.settings.update { putString(key, v) } }
    }
    numberEdit?.let { (key, title, value) ->
        TextInputDialog(title, value, title, keyboardType = KeyboardType.Number, onDismiss = { numberEdit = null }) { v ->
            val n = v.toIntOrNull() ?: return@TextInputDialog
            app.settings.update { if (key == Keys.TARGET_CREDITS) putFloat(key, n.toFloat()) else putInt(key, n.coerceAtLeast(1)) }
        }
    }
    timeEdit?.let { (key, minute) ->
        TimePickerDialogBox(minute, { timeEdit = null }) { m -> app.settings.update { putInt(key, m) }; app.requestReminderSync() }
    }
    if (restoreList) {
        val backups = remember { app.backups.listBackups() }
        AlertDialog(
            onDismissRequest = { restoreList = false },
            title = { Text("Restore from backup") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    if (backups.isEmpty()) Text("No backups in BlackLab/Backups yet.")
                    backups.forEach { f ->
                        SettingRow(f.nameWithoutExtension.removePrefix("BlackLab_backup_"), "${Files.formatSize(f.length())} · ${Time.dateTime(f.lastModified())}", Icons.Default.Restore) {
                            restoreList = false; restoreTarget = f
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    TextButton(onClick = { restoreList = false; restorePicker.launch(arrayOf("application/zip", "*/*")) }) { Text("Choose a backup file…") }
                }
            },
            confirmButton = { TextButton(onClick = { restoreList = false }) { Text("Close") } },
        )
    }
    restoreTarget?.let { target ->
        ConfirmDialog("Restore this backup?", "Your current tasks, events, courses and document index will be replaced. Files in the BlackLab folder are not touched. BlackLab will close; reopen it afterwards.",
            "Restore", onDismiss = { restoreTarget = null }) {
            val ok = when (target) { is File -> app.backups.restore(target); is Uri -> app.backups.restore(target); else -> false }
            if (ok) { activity?.finishAffinity(); exitProcess(0) } else shell.toast("Restore failed — not a BlackLab backup")
        }
    }
}

@Suppress("unused")
private fun dayName(d: Int) = Time.dayFull(DayOfWeek.of(d))

package com.blacklab.app.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.blacklab.app.MainActivity
import com.blacklab.app.R
import com.blacklab.app.core.Keys
import com.blacklab.app.core.Profile
import com.blacklab.app.ui.components.LabeledSwitchRow
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.theme.LocalBlColors
import com.blacklab.app.ui.theme.Mint

@Composable
fun OnboardingScreen() {
    val app = rememberApp()
    val ctx = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var org by remember { mutableStateOf("") }
    var personal by remember { mutableStateOf("Personal") }
    var professional by remember { mutableStateOf("Professional") }
    var defaultProfile by remember { mutableStateOf(Profile.PROFESSIONAL) }
    var appLock by remember { mutableStateOf(false) }
    var lockPro by remember { mutableStateOf(false) }

    var allFiles by remember { mutableStateOf(app.storage.hasAllFilesAccess) }
    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    var notif by remember { mutableStateOf(granted(Manifest.permission.POST_NOTIFICATIONS)) }
    var camMic by remember { mutableStateOf(granted(Manifest.permission.CAMERA) && granted(Manifest.permission.RECORD_AUDIO)) }
    var cal by remember { mutableStateOf(granted(Manifest.permission.READ_CALENDAR)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        allFiles = app.storage.hasAllFilesAccess
        notif = granted(Manifest.permission.POST_NOTIFICATIONS)
        camMic = granted(Manifest.permission.CAMERA) && granted(Manifest.permission.RECORD_AUDIO)
        cal = granted(Manifest.permission.READ_CALENDAR)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        notif = granted(Manifest.permission.POST_NOTIFICATIONS)
        camMic = granted(Manifest.permission.CAMERA) && granted(Manifest.permission.RECORD_AUDIO)
        cal = granted(Manifest.permission.READ_CALENDAR)
    }
    val allFilesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        allFiles = app.storage.hasAllFilesAccess
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().imePadding()
    ) {
        // progress dots
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.Center) {
            repeat(4) { i ->
                Box(Modifier.padding(3.dp).size(if (i == step) 22.dp else 8.dp, 8.dp).clip(CircleShape)
                    .background(if (i <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest))
            }
        }
        AnimatedContent(step, label = "onboarding", modifier = Modifier.weight(1f)) { s ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
                when (s) {
                    0 -> {
                        Spacer(Modifier.height(40.dp))
                        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(140.dp).align(Alignment.CenterHorizontally))
                        Text("BlackLab", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
                        Text("Your personal & professional command center.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                        Spacer(Modifier.height(32.dp))
                        Feature(Icons.Default.PhotoCamera, "Pro camera & recorder", "Full-resolution capture, night & portrait modes, 4K HDR video, lossless audio with live transcription.")
                        Feature(Icons.Default.Description, "Documents & journal", "Word-class editing with tables, images and styles. Export to DOCX, PDF, Markdown.")
                        Feature(Icons.Default.TaskAlt, "Tasks & schedule", "Smart lists, My Day, natural-language entry, recurring events, free-time finder.")
                        Feature(Icons.Default.School, "Education", "Courses, grades & GPA, certifications with CEUs, focus timer and flashcards.")
                    }
                    1 -> {
                        Header("About you", "Personalizes greetings, document headers and templates.")
                        OutlinedTextField(name, { name = it }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(title, { title = it }, label = { Text("Job title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Director of Facility Operations", "Facility Manager", "Operations Manager", "Maintenance Supervisor").forEach { t ->
                                AssistChip(onClick = { title = t }, label = { Text(t) })
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(org, { org = it }, label = { Text("Organization (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(24.dp))
                        Header("Your two accounts", "Everything — files, tasks, calendars, courses — is kept separate per account.")
                        OutlinedTextField(personal, { personal = it }, label = { Text("Personal account name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            leadingIcon = { Icon(Icons.Default.Person, null, tint = Mint) })
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(professional, { professional = it }, label = { Text("Professional account name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            leadingIcon = { Icon(Icons.Default.Work, null, tint = com.blacklab.app.ui.theme.Cobalt) })
                        Spacer(Modifier.height(14.dp))
                        Text("Open BlackLab in", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(6.dp))
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            Profile.entries.forEachIndexed { i, p ->
                                SegmentedButton(selected = defaultProfile == p, onClick = { defaultProfile = p }, shape = SegmentedButtonDefaults.itemShape(i, 2)) {
                                    Text(if (p == Profile.PERSONAL) personal.ifBlank { "Personal" } else professional.ifBlank { "Professional" })
                                }
                            }
                        }
                    }
                    2 -> {
                        Header("Storage & permissions", "BlackLab creates a “BlackLab” folder on your phone. Every photo, video, recording, scan, document and journal entry is saved there as a real file — viewable in the Files app and on your PC over USB.")
                        PermCard(Icons.Default.FolderSpecial, "All-files access", "Lets BlackLab create /BlackLab in internal storage (kept even if the app is reinstalled).", allFiles) {
                            allFilesLauncher.launch(app.storage.allFilesAccessIntent())
                        }
                        PermCard(Icons.Default.PhotoCamera, "Camera & microphone", "For photos, video, document scans and audio recording.", camMic) {
                            permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                        }
                        PermCard(Icons.Default.NotificationsActive, "Notifications", "Reminders for tasks, events, classes, deadlines and certification renewals.", notif) {
                            permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                        }
                        PermCard(Icons.Default.CalendarMonth, "Calendars (optional)", "Show your Google / Outlook calendars alongside BlackLab events.", cal) {
                            permLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR))
                        }
                    }
                    3 -> {
                        Header("Security", "Protect your workspace with your fingerprint, face or screen lock.")
                        LabeledSwitchRow("Lock BlackLab", "Require unlock when opening the app", appLock, icon = Icons.Default.Fingerprint) { appLock = it }
                        LabeledSwitchRow("Lock ${professional.ifBlank { "Professional" }} account", "Require unlock when switching to it", lockPro, icon = Icons.Default.Lock) { lockPro = it }
                        Spacer(Modifier.height(24.dp))
                        Text("You're all set.", style = MaterialTheme.typography.headlineSmall)
                        Text("Folder: ${if (allFiles) "Internal storage/BlackLab" else "App storage (you can enable All-files access later in Settings)"}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (step > 0) TextButton(onClick = { step-- }) { Text("Back") }
            Spacer(Modifier.weight(1f))
            Button(onClick = {
                if (step < 3) step++
                else {
                    app.settings.update {
                        putString(Keys.USER_NAME, name.trim())
                        putString(Keys.JOB_TITLE, title.trim())
                        putString(Keys.ORGANIZATION, org.trim())
                        putString(Keys.PERSONAL_NAME, personal.trim().ifBlank { "Personal" })
                        putString(Keys.PROFESSIONAL_NAME, professional.trim().ifBlank { "Professional" })
                        putString(Keys.DEFAULT_PROFILE, defaultProfile.name)
                        putString(Keys.ACTIVE_PROFILE, defaultProfile.name)
                        putBoolean(Keys.APP_LOCK, appLock)
                        putBoolean(Keys.LOCK_PROFESSIONAL, lockPro)
                        putBoolean(Keys.SHOW_DEVICE_CALS, cal)
                        putBoolean(Keys.ONBOARDED, true)
                    }
                    app.storage.ensureStructure()
                    app.requestReminderSync()
                }
            }) { Text(if (step < 3) "Continue" else "Open BlackLab") }
        }
    }
}

@Composable
private fun Header(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.headlineMedium)
    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
}

@Composable
private fun Feature(icon: ImageVector, title: String, body: String) {
    Row(Modifier.padding(vertical = 8.dp)) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(LocalBlColors.current.accentSoft), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PermCard(icon: ImageVector, title: String, body: String, granted: Boolean, onGrant: () -> Unit) {
    com.blacklab.app.ui.components.BlCard(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (granted) LocalBlColors.current.success else MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            if (granted) Icon(Icons.Default.CheckCircle, "Granted", tint = LocalBlColors.current.success)
            else FilledTonalButton(onClick = onGrant) { Text("Allow") }
        }
    }
}

@Composable
fun LockScreen(activity: MainActivity) {
    val unlock = {
        activity.authenticate("Unlock BlackLab", "Use your fingerprint, face or screen lock") { ok -> if (ok) MainActivity.locked.value = false }
    }
    LaunchedEffect(Unit) { unlock() }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(120.dp))
        Text("BlackLab is locked", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))
        Button(onClick = unlock) {
            Icon(Icons.Default.Fingerprint, null); Spacer(Modifier.width(8.dp)); Text("Unlock")
        }
    }
}

package com.blacklab.app.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.lifecycleScope
import com.blacklab.app.app
import com.blacklab.app.brainstorm.NoteItem
import com.blacklab.app.brainstorm.NoteOps
import com.blacklab.app.data.NoteEntity
import com.blacklab.app.ui.theme.BlackLabTheme
import kotlinx.coroutines.launch

/**
 * Picks which Brainstorm note a Checklist widget shows. Listing note titles reveals content, so
 * when the BlackLab app lock is on this asks for a fingerprint / screen lock first.
 */
class ChecklistConfigActivity : FragmentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private val unlocked = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        // Only configure widgets that really are BlackLab checklist widgets
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)
        if (info == null || info.provider != ComponentName(this, ChecklistWidgetReceiver::class.java)) { finish(); return }

        val app = app
        if (app.settings.current.appLock && canAuthenticate()) authenticate() else unlocked.value = true

        setContent {
            val s by app.settings.state.collectAsState()
            BlackLabTheme(s.themeMode, s.dynamicColor, s.activeProfile, com.blacklab.app.ui.theme.ThemeChoice.from(s)) {
                val ok by unlocked
                var notes by remember { mutableStateOf<List<NoteEntity>?>(null) }
                LaunchedEffect(ok) { if (ok) notes = Widgets.pinnableNotes(this@ChecklistConfigActivity) }
                Scaffold(topBar = { TopAppBar(title = { Text("Pin a note to the home screen") }) }) { pad ->
                    if (!ok) {
                        Column(Modifier.fillMaxSize().padding(pad).padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Unlock BlackLab to choose a note.")
                            androidx.compose.material3.TextButton(onClick = { authenticate() }) { Text("Unlock") }
                        }
                        return@Scaffold
                    }
                    LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(vertical = 8.dp)) {
                        item {
                            Row(Modifier.fillMaxWidth().clickable { newChecklist() }.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.size(16.dp))
                                Text("New checklist", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        val list = notes
                        if (list != null && list.isEmpty()) item {
                            Text("No notes in ${app.settings.profileName(s.activeProfile)} yet.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(list.orEmpty(), key = { it.id }) { n ->
                            Row(Modifier.fillMaxWidth().clickable { choose(n.id) }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (n.checklist) Icons.Default.Checklist else Icons.AutoMirrored.Filled.Notes, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.size(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(n.title.ifBlank { if (n.checklist) "Checklist" else "Note" }, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                    val sub = NoteOps.summary(n)
                                    if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                                if (n.pinned) Icon(Icons.Default.PushPin, "Pinned", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun newChecklist() {
        lifecycleScope.launch {
            val app = app
            val id = NoteOps.save(app, NoteEntity(profile = app.settings.current.activeProfile.name, title = "Checklist", checklist = true,
                items = NoteOps.itemsJson(emptyList<NoteItem>())))
            choose(id)
        }
    }

    private fun choose(noteId: Long) {
        lifecycleScope.launch {
            val glanceId = GlanceAppWidgetManager(this@ChecklistConfigActivity).getGlanceIdBy(appWidgetId)
            updateAppWidgetState(this@ChecklistConfigActivity, glanceId) { it[ChecklistWidget.NoteKey] = noteId }
            ChecklistWidget().update(this@ChecklistConfigActivity, glanceId)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
            finish()
        }
    }

    private fun canAuthenticate() =
        BiometricManager.from(this).canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

    private fun authenticate() {
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { unlocked.value = true }
        }).authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle("Unlock BlackLab").setSubtitle("Choose a note for the widget")
                .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL).build()
        )
    }
}

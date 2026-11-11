package com.blacklab.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blacklab.app.AppContainer
import com.blacklab.app.core.CrashLog
import com.blacklab.app.core.Health
import com.blacklab.app.ui.components.BlCard
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.rememberApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The pattern for screens with real state (see docs/ARCHITECTURE.md): a ViewModel owns loading and the state,
 * survives rotation and process-death-free configuration changes, and the composable only renders [UiState].
 */
class HealthViewModel(private val app: AppContainer) : ViewModel() {
    data class UiState(val loading: Boolean = true, val checks: List<Health.Check> = emptyList(), val summary: String = "")

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        _state.value = _state.value.copy(loading = true)
        viewModelScope.launch {
            val checks = withContext(Dispatchers.IO) { Health.evaluate(gather()) }
            _state.value = UiState(false, checks, Health.summary(checks))
        }
    }

    private suspend fun gather(): Health.Inputs {
        val ctx = app.context
        val s = app.settings.current
        val now = System.currentTimeMillis()
        val backups = app.backups.listBackups()
        val stores = listOf(
            com.blacklab.app.facility.Facility.store.also { com.blacklab.app.facility.Facility.load(ctx) },
            com.blacklab.app.vehicle.Garage.store.also { com.blacklab.app.vehicle.Garage.load(ctx) },
            com.blacklab.app.vehicle.VehicleLink.store.also { com.blacklab.app.vehicle.VehicleLink.load(ctx) },
            com.blacklab.app.vehicle.Nhtsa.store.also { com.blacklab.app.vehicle.Nhtsa.load(ctx) },
        )
        val crashes = CrashLog.entries(ctx).filter { it.time > now - 7 * 86_400_000L }
        val desk = com.blacklab.app.assistant.DesktopAi.status.value
        val index = app.assistant.index
        runCatching { index.ensure() }
        return Health.Inputs(
            now = now, newestBackupAt = backups.firstOrNull()?.lastModified(), backupCount = backups.size,
            plainBackups = app.backups.unencryptedBackups().size, autoBackup = s.autoBackup, backupPassphraseSet = app.backups.hasPassphrase,
            storeProblems = stores.mapNotNull { st -> st.problem?.let { st.name to it } }.toMap(), storeReadOnly = stores.filter { it.readOnly }.map { it.name },
            dbVersion = app.db.openHelper.readableDatabase.version,
            desktopEnabled = s.asstDesktopAi, desktopState = desk.state.name, desktopLatencyMs = desk.latencyMs,
            vaultEnabled = s.vaultEnabled, vaultLastSyncAt = com.blacklab.app.link.VaultFeed.lastSyncAt(app),
            crashes7d = crashes.size, lastCrashTitle = crashes.firstOrNull()?.title,
            freeBytes = app.storage.freeBytes(), nanoStatus = com.blacklab.app.assistant.Nano.status.value.name, indexPassages = index.size,
        )
    }
}

@Composable
fun HealthScreen() {
    val app = rememberApp()
    val shell = LocalShell.current
    val vm: HealthViewModel = viewModel { HealthViewModel(app) }
    val ui by vm.state.collectAsStateWithLifecycle()

    Scaffold(topBar = {
        BlTopBar("Health", subtitle = if (ui.loading) "Checking…" else ui.summary, onBack = { shell.back() }, actions = {
            IconButton(onClick = { vm.refresh() }) { Icon(Icons.Rounded.Refresh, "Check again") }
        })
    }) { pad ->
        if (ui.loading && ui.checks.isEmpty()) {
            Row(Modifier.padding(pad).fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ui.checks, key = { it.id }) { c ->
                val (icon, tint, word) = when (c.level) {
                    Health.Level.OK -> Triple(Icons.Rounded.CheckCircle, Color(0xFF2BD9A8), "OK")
                    Health.Level.INFO -> Triple(Icons.Rounded.Info, MaterialTheme.colorScheme.primary, "Note")
                    Health.Level.WARN -> Triple(Icons.Rounded.Warning, Color(0xFFFFB74D), "Warning")
                    Health.Level.BAD -> Triple(Icons.Rounded.Error, MaterialTheme.colorScheme.error, "Problem")
                }
                BlCard(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$word: ${c.title}. ${c.detail}" }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, tint = tint)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.title, style = MaterialTheme.typography.titleSmall)
                            Text(c.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (c.fixLabel != null && c.fixRoute != null) TextButton(onClick = { shell.go(c.fixRoute) }) { Text(c.fixLabel) }
                    }
                }
            }
        }
    }
}

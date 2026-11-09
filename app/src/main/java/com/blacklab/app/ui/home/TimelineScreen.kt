package com.blacklab.app.ui.home

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blacklab.app.AppContainer
import com.blacklab.app.core.DayTimeline
import com.blacklab.app.core.Profile
import com.blacklab.app.data.CalendarLogic
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
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Gathers one day for [DayTimeline] (used by the Timeline screen and the assistant). */
object DayTimelineData {
    suspend fun inputs(app: AppContainer, day: LocalDate): DayTimeline.Inputs = withContext(Dispatchers.IO) {
        val ctx = app.context
        val zone = ZoneId.systemDefault()
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val events = app.db.events().all().flatMap { CalendarLogic.expand(it, day, day) }.map { o ->
            DayTimeline.Event(o.start.atZone(zone).toInstant().toEpochMilli(), o.event.title, o.event.location)
        }
        val bh = runCatching {
            val s = com.blacklab.app.bh.BhStore.load(ctx)
            val rounds = s.eoc.filter { it.time in start until end }
            DayTimeline.BhCounts(s.checks.count { it.time in start until end }, rounds.size, rounds.sumOf { r -> r.areas.sumOf { a -> a.results.count { it.value == "ISSUE" } } })
        }.getOrDefault(DayTimeline.BhCounts())
        DayTimeline.Inputs(day, zone, tasks = app.db.tasks().all(), events = events, facility = com.blacklab.app.facility.Facility.load(ctx),
            trips = com.blacklab.app.vehicle.VehicleLink.load(ctx).trips, recordings = Profile.entries.flatMap { app.db.recordings().allFor(it.name) },
            notes = app.db.notes().all().filter { it.trashedAt == null }.map { it.updatedAt to it.title }, bh = bh)
    }

    suspend fun handoff(app: AppContainer, day: LocalDate = LocalDate.now()): String {
        val i = inputs(app, day)
        val tomorrow = withContext(Dispatchers.IO) {
            app.db.events().all().flatMap { CalendarLogic.expand(it, day.plusDays(1), day.plusDays(1)) }
                .map { o -> DayTimeline.Event(o.start.atZone(i.zone).toInstant().toEpochMilli(), o.event.title, o.event.location) }
        }
        return DayTimeline.handoff(day, i.zone, DayTimeline.build(i), i.tasks, com.blacklab.app.facility.Facility.due(i.facility), tomorrow, app.settings.current.userName)
    }
}

class TimelineViewModel(private val app: AppContainer) : ViewModel() {
    data class UiState(val day: LocalDate = LocalDate.now(), val loading: Boolean = true, val items: List<DayTimeline.Item> = emptyList(), val handoff: String? = null)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init { load(LocalDate.now()) }

    fun load(day: LocalDate) {
        _state.value = _state.value.copy(day = day, loading = true)
        viewModelScope.launch {
            val items = DayTimeline.build(DayTimelineData.inputs(app, day))
            _state.value = _state.value.copy(loading = false, items = items)
        }
    }

    fun makeHandoff() = viewModelScope.launch { _state.value = _state.value.copy(handoff = DayTimelineData.handoff(app, _state.value.day)) }
    fun closeHandoff() { _state.value = _state.value.copy(handoff = null) }
}

@Composable
fun TimelineScreen() {
    val app = rememberApp()
    val shell = LocalShell.current
    val vm: TimelineViewModel = viewModel { TimelineViewModel(app) }
    val ui by vm.state.collectAsStateWithLifecycle()
    val zone = remember { ZoneId.systemDefault() }
    val isToday = ui.day == LocalDate.now()

    Scaffold(
        topBar = {
            BlTopBar("Timeline", subtitle = if (isToday) "Today · ${ui.items.size} things" else ui.day.format(DateTimeFormatter.ofPattern("EEE, MMM d")) + " · ${ui.items.size} things",
                onBack = { shell.back() }) {
                IconButton(onClick = { vm.load(ui.day.minusDays(1)) }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Previous day") }
                IconButton(onClick = { vm.load(ui.day.plusDays(1)) }, enabled = !isToday) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Next day") }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { vm.makeHandoff() }, icon = { Icon(Icons.Rounded.Handshake, null) }, text = { Text("Ops handoff") })
        },
    ) { pad ->
        if (ui.loading && ui.items.isEmpty()) {
            Row(Modifier.padding(pad).fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (ui.items.isEmpty()) item {
                Text("Nothing logged on this day yet. Work orders, inspections, finished tasks, meetings, recordings, notes and drives show up here.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
            }
            items(ui.items, key = { it.time.toString() + it.kind + it.title }) { it ->
                val time = DayTimeline.clock(it.time, zone)
                BlCard(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$time, ${it.kind.label}: ${it.title}. ${it.detail}" },
                    onClick = it.route?.let { r -> { shell.go(r) } }) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(time, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(64.dp))
                        Text(it.kind.emoji)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(it.title, style = MaterialTheme.typography.titleSmall)
                            Text(it.kind.label + if (it.detail.isNotBlank()) " · ${it.detail}" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    ui.handoff?.let { text -> HandoffDialog(text, onDismiss = { vm.closeHandoff() }) }
}

@Composable
private fun HandoffDialog(text: String, onDismiss: () -> Unit) {
    val app = rememberApp()
    val shell = LocalShell.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Handshake, null) },
        title = { Text("Ops handoff") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("Behavioral Health is never included.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.padding(top = 6.dp))
                Text(text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                app.context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    .putExtra(Intent.EXTRA_SUBJECT, text.lineSequence().first()), "Share handoff").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("Share") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { clipboard.setText(AnnotatedString(text)); shell.toast("Copied") }) { Text("Copy") }
                if (app.settings.current.vaultEnabled && com.blacklab.app.assistant.DesktopAi.usable) TextButton(enabled = !sending, onClick = {
                    sending = true
                    scope.launch {
                        val r = com.blacklab.app.link.VaultFeed.capture(text, text.lineSequence().first())
                        sending = false
                        shell.toast(r.message)
                    }
                }) { Text("To vault") }
            }
        },
    )
}

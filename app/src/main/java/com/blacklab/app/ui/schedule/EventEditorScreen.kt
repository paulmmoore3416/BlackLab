package com.blacklab.app.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.blacklab.app.core.Files
import com.blacklab.app.core.Recurrence
import com.blacklab.app.core.Time
import com.blacklab.app.data.EventEntity
import com.blacklab.app.ui.components.BlCard
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.ChipFlow
import com.blacklab.app.ui.components.ColorDot
import com.blacklab.app.ui.components.ConfirmDialog
import com.blacklab.app.ui.components.DatePickerDialogBox
import com.blacklab.app.ui.components.DropdownField
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.PickerRow
import com.blacklab.app.ui.components.RepeatDialog
import com.blacklab.app.ui.components.TimePickerDialogBox
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.components.settingsState
import com.blacklab.app.ui.theme.Amber
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

private val ReminderOptions = listOf(0, 5, 10, 15, 30, 60, 120, 1440, 2880, 10080)

@Composable
fun EventEditorScreen(id: Long, start: Long?, allDayDefault: Boolean) {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val s = settingsState()
    val calendars by remember(s.activeProfile) { app.db.events().observeCalendars(listOf(s.activeProfile.name)) }.collectAsState(emptyList())
    var e by remember { mutableStateOf<EventEntity?>(null) }
    LaunchedEffect(id) {
        e = if (id != 0L) app.db.events().get(id) else {
            val st = start ?: Time.millis(Time.today(), 9 * 60)
            EventEntity(
                profile = s.activeProfile.name,
                calendarId = app.db.events().calendars(s.activeProfile.name).firstOrNull()?.id,
                title = "",
                start = if (allDayDefault) Time.millis(Time.toDate(st)) else st,
                end = if (allDayDefault) Time.millis(Time.toDate(st).plusDays(1)) else st + s.defaultEventMinutes * 60_000L,
                allDay = allDayDefault,
                reminders = if (allDayDefault) "" else s.defaultEventReminder.toString(),
            )
        }
    }
    var dialog by remember { mutableStateOf<String?>(null) }
    val ev = e

    // Conflicts with other busy items
    val startDate = ev?.let { Time.toDate(it.start) } ?: Time.today()
    val endDate = ev?.let { Time.toDate(it.end) } ?: Time.today()
    val nearby = rememberCalendarItems(startDate, endDate)
    val conflicts = remember(nearby, ev) {
        if (ev == null || ev.allDay || !ev.busy) emptyList() else {
            val a = Time.toLdt(ev.start); val b = Time.toLdt(ev.end)
            nearby.filter { it.busy && !it.allDay && !(it.kind == CalKind.EVENT && it.refId == ev.id) && it.start < b && it.end > a }
        }
    }

    fun save() {
        val cur = e ?: return
        if (cur.title.isBlank()) { shell.toast("Add a title"); return }
        app.io {
            if (cur.id == 0L) app.db.events().insert(cur) else app.db.events().update(cur.copy(updatedAt = Time.now()))
            app.requestReminderSync()
        }
        shell.back()
    }

    Scaffold(
        topBar = {
            BlTopBar(
                title = if (id == 0L) "New event" else "Edit event",
                onBack = { shell.back() },
                actions = {
                    if (id != 0L) IconButton(onClick = { dialog = "delete" }) { Icon(Icons.Default.Delete, "Delete") }
                    TextButton(onClick = { save() }) { Text("Save") }
                },
            )
        },
    ) { pad ->
        if (ev == null) return@Scaffold
        val sLdt = Time.toLdt(ev.start)
        val eLdt = Time.toLdt(ev.end)
        Column(Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            TextField(
                ev.title, { e = ev.copy(title = it) }, placeholder = { Text("Add title") }, textStyle = MaterialTheme.typography.headlineSmall,
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            if (conflicts.isNotEmpty()) {
                BlCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), accent = Amber) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = Amber)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Conflicts with ${conflicts.size} item(s)", style = MaterialTheme.typography.titleSmall)
                            conflicts.take(3).forEach { Text("• ${it.title} (${Time.time(it.start)}–${Time.time(it.end)})", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
            BlCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(16.dp))
                    Text("All-day", Modifier.weight(1f))
                    Switch(ev.allDay, { on ->
                        e = if (on) ev.copy(allDay = true, start = Time.millis(Time.toDate(ev.start)), end = Time.millis(Time.toDate(ev.end).let { d -> if (d <= Time.toDate(ev.start)) d.plusDays(1) else d.plusDays(1) }))
                        else ev.copy(allDay = false, start = Time.millis(Time.toDate(ev.start), 9 * 60), end = Time.millis(Time.toDate(ev.start), 9 * 60 + s.defaultEventMinutes))
                    })
                }
                Row {
                    PickerRow(Icons.Default.Event, "Starts", Time.date(sLdt.toLocalDate()), Modifier.weight(1f)) { dialog = "startDate" }
                    if (!ev.allDay) TextButton(onClick = { dialog = "startTime" }, modifier = Modifier.align(Alignment.CenterVertically)) { Text(Time.time(sLdt)) }
                }
                Row {
                    val shownEnd = if (ev.allDay) eLdt.toLocalDate().minusDays(1).let { if (it < sLdt.toLocalDate()) sLdt.toLocalDate() else it } else eLdt.toLocalDate()
                    PickerRow(Icons.Default.EventBusy, "Ends", Time.date(shownEnd), Modifier.weight(1f)) { dialog = "endDate" }
                    if (!ev.allDay) TextButton(onClick = { dialog = "endTime" }, modifier = Modifier.align(Alignment.CenterVertically)) { Text(Time.time(eLdt)) }
                }
                if (!ev.allDay) {
                    ChipFlow(Modifier.padding(start = 48.dp, bottom = 6.dp)) {
                        listOf(15, 30, 45, 60, 90, 120, 240).forEach { m ->
                            val cur = Duration.between(sLdt, eLdt).toMinutes().toInt()
                            FilterChip(cur == m, { e = ev.copy(end = ev.start + m * 60_000L) }, label = { Text(Time.hoursLabel(m)) })
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PickerRow(Icons.Default.Repeat, "Does not repeat", ev.repeatRule?.let { Recurrence.parse(it)?.describe(sLdt.toLocalDate()) }, onClear = { e = ev.copy(repeatRule = null) }) { dialog = "repeat" }
            }
            Spacer(Modifier.height(12.dp))
            BlCard(Modifier.fillMaxWidth()) {
                DropdownField(
                    "Calendar",
                    listOf<Pair<Long?, String>>(null to "No calendar") + calendars.map { it.id to it.name },
                    ev.calendarId,
                    { e = ev.copy(calendarId = it) },
                )
                calendars.firstOrNull { it.id == ev.calendarId }?.let { c ->
                    Row(Modifier.padding(top = 6.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        ColorDot(Color(c.color), 12.dp); Spacer(Modifier.width(8.dp))
                        Text("Color from calendar", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Reminders", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val selected = ev.reminders.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
                ChipFlow(Modifier.padding(top = 4.dp)) {
                    ReminderOptions.forEach { m ->
                        FilterChip(m in selected, {
                            val next = if (m in selected) selected - m else selected + m
                            e = ev.copy(reminders = next.sorted().joinToString(","))
                        }, label = { Text(if (m == 0) "At start" else Time.minutesLabel(m)) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Show as busy", Modifier.weight(1f))
                    Switch(ev.busy, { e = ev.copy(busy = it) })
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(ev.location, { e = ev.copy(location = it) }, label = { Text("Location / building / room") }, singleLine = true,
                leadingIcon = { Icon(Icons.Default.LocationOn, null) },
                trailingIcon = { if (ev.location.isNotBlank()) IconButton(onClick = { Files.openMap(ctx, ev.location) }) { Icon(Icons.Default.Map, "Map") } },
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(ev.url, { e = ev.copy(url = it) }, label = { Text("Meeting link (Teams, Zoom, Meet)") }, singleLine = true,
                leadingIcon = { Icon(Icons.Default.VideoCall, null) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(ev.attendees, { e = ev.copy(attendees = it) }, label = { Text("Attendees (emails, comma separated)") },
                leadingIcon = { Icon(Icons.Default.Group, null) },
                trailingIcon = { if (ev.attendees.isNotBlank()) IconButton(onClick = { Files.email(ctx, ev.attendees, ev.title, "${Time.dateTime(ev.start)}\n${ev.location}\n${ev.url}") }) { Icon(Icons.Default.Email, "Email") } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(ev.notes, { e = ev.copy(notes = it) }, label = { Text("Notes / agenda") }, minLines = 4,
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Notes, null) }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(80.dp))
        }
    }

    val cur = e ?: return
    val sL = Time.toLdt(cur.start)
    val eL = Time.toLdt(cur.end)
    val dur = cur.end - cur.start
    when (dialog) {
        "startDate" -> DatePickerDialogBox(sL.toLocalDate(), { dialog = null }) { d ->
            d ?: return@DatePickerDialogBox
            val ns = Time.millis(LocalDateTime.of(d, sL.toLocalTime()))
            e = cur.copy(start = ns, end = ns + dur)
        }
        "startTime" -> TimePickerDialogBox(Time.minuteOfDay(sL), { dialog = null }) { m ->
            val ns = Time.millis(sL.toLocalDate(), m)
            e = cur.copy(start = ns, end = ns + dur)
        }
        "endDate" -> DatePickerDialogBox(if (cur.allDay) eL.toLocalDate().minusDays(1) else eL.toLocalDate(), { dialog = null }) { d ->
            d ?: return@DatePickerDialogBox
            val ne = if (cur.allDay) Time.millis(d.plusDays(1)) else Time.millis(LocalDateTime.of(d, eL.toLocalTime()))
            e = cur.copy(end = maxOf(ne, cur.start + if (cur.allDay) 86_400_000L else 15 * 60_000L))
        }
        "endTime" -> TimePickerDialogBox(Time.minuteOfDay(eL), { dialog = null }) { m ->
            var ne = Time.millis(eL.toLocalDate(), m)
            if (ne <= cur.start) ne = Time.millis(sL.toLocalDate().let { if (Time.millis(it, m) <= cur.start) it.plusDays(1) else it }, m)
            e = cur.copy(end = ne)
        }
        "repeat" -> RepeatDialog(cur.repeatRule, sL.toLocalDate(), { dialog = null }) { e = cur.copy(repeatRule = it) }
        "delete" -> ConfirmDialog("Delete event?", "“${cur.title}”${if (cur.repeatRule != null) " and all its repeats" else ""} will be deleted.", onDismiss = { dialog = null }) {
            app.io { app.db.events().delete(cur); app.requestReminderSync() }
            shell.back()
        }
    }
}

@Suppress("unused")
private fun LocalDate.isWeekend() = dayOfWeek.value >= 6

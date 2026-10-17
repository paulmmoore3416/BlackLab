package com.blacklab.app.ui.tasks

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.blacklab.app.core.Files
import com.blacklab.app.core.Folder
import com.blacklab.app.core.Recurrence
import com.blacklab.app.core.Time
import com.blacklab.app.data.SubtaskEntity
import com.blacklab.app.data.TaskEntity
import com.blacklab.app.data.TaskOps
import com.blacklab.app.ui.components.BlCard
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.ChipFlow
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
import com.blacklab.app.ui.theme.LocalBlColors
import kotlinx.coroutines.delay
import java.time.LocalDate

@Composable
fun TaskDetailScreen(id: Long) {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val settings = settingsState()
    var task by remember { mutableStateOf<TaskEntity?>(null) }
    var taskId by remember { mutableStateOf(id) }
    LaunchedEffect(id) {
        task = if (id == 0L) TaskEntity(profile = settings.activeProfile.name, title = "") else app.db.tasks().get(id)
    }
    val steps by remember(taskId) { app.db.tasks().observeSubtasks(taskId) }.collectAsState(emptyList())
    val lists by remember(settings.activeProfile) { app.db.tasks().observeLists(settings.activeProfile.name) }.collectAsState(emptyList())
    var dialog by remember { mutableStateOf<String?>(null) }
    var pendingReminderDay by remember { mutableStateOf<LocalDate?>(null) }

    // autosave after edits settle
    LaunchedEffect(task) {
        val t = task ?: return@LaunchedEffect
        delay(350)
        if (t.title.isBlank() && t.id == 0L) return@LaunchedEffect
        val newId = TaskOps.save(app, t)
        if (t.id == 0L) { taskId = newId; task = t.copy(id = newId) }
    }
    val leave = {
        task?.let { t -> if (t.id != 0L && t.title.isBlank()) app.io { TaskOps.delete(app, t) } }
        shell.back()
    }
    BackHandler { leave() }

    val attach = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val t = task ?: return@rememberLauncherForActivityResult
        if (uri != null) app.io {
            val name = Files.displayName(ctx, uri)
            val dest = Files.unique(app.storage.dir(settings.activeProfile, Folder.IMPORTS), name.substringBeforeLast('.'), name.substringAfterLast('.', "bin"))
            if (Files.copyFromUri(ctx, uri, dest)) task = t.copy(attachmentPath = app.storage.relative(dest))
        }
    }

    val t = task
    Scaffold(
        topBar = {
            BlTopBar(
                title = lists.firstOrNull { it.id == t?.listId }?.let { "${it.emoji} ${it.name}" } ?: "Task",
                onBack = { leave() },
                actions = {
                    if (t != null) IconButton(onClick = { task = t.copy(important = !t.important) }) {
                        if (t.important) Icon(Icons.Default.Star, "Important", tint = Amber) else Icon(Icons.Outlined.StarBorder, "Important")
                    }
                    IconButton(onClick = { dialog = "delete" }) { Icon(Icons.Default.Delete, "Delete") }
                },
            )
        },
    ) { pad ->
        if (t == null) return@Scaffold
        Column(Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    Modifier.padding(top = 14.dp).size(26.dp).clip(CircleShape)
                        .background(if (t.completed) LocalBlColors.current.accent else Color.Transparent)
                        .border(2.dp, if (t.completed) LocalBlColors.current.accent else PriorityColors[t.priority].takeIf { t.priority > 0 } ?: MaterialTheme.colorScheme.outline, CircleShape)
                        .clickable { app.io { TaskOps.setCompleted(app, t, !t.completed) }; task = t.copy(completed = !t.completed, completedAt = if (!t.completed) Time.now() else null) },
                    contentAlignment = Alignment.Center,
                ) { if (t.completed) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp)) }
                TextField(
                    value = t.title, onValueChange = { task = t.copy(title = it) },
                    placeholder = { Text("Task name") },
                    textStyle = MaterialTheme.typography.headlineSmall.copy(textDecoration = if (t.completed) TextDecoration.LineThrough else null),
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.weight(1f),
                )
            }

            // Steps
            steps.forEach { s -> StepRow(s) }
            var newStep by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 12.dp, end = 4.dp))
                TextField(
                    newStep, { newStep = it }, placeholder = { Text(if (steps.isEmpty()) "Add step" else "Next step") }, singleLine = true,
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (newStep.isNotBlank() && t.id != 0L) {
                            val title = newStep.trim(); newStep = ""
                            app.io { app.db.tasks().insertSubtask(SubtaskEntity(taskId = t.id, title = title, sortOrder = steps.size)) }
                        }
                    }),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))

            BlCard(Modifier.fillMaxWidth()) {
                val today = Time.today().toEpochDay()
                PickerRow(Icons.Default.WbSunny, "Add to My Day", if (t.myDayDay == today) "Added to My Day" else null, onClear = { task = t.copy(myDayDay = null) }) {
                    task = t.copy(myDayDay = if (t.myDayDay == today) null else today)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PickerRow(Icons.Default.NotificationsActive, "Remind me", t.reminderAt?.let { Time.dateTime(it) }, onClear = { task = t.copy(reminderAt = null) }) { dialog = "remindDate" }
                PickerRow(Icons.Default.Event, "Add due date", t.dueDay?.let { "Due " + Time.relativeDay(LocalDate.ofEpochDay(it)) },
                    valueColor = if ((t.dueDay ?: Long.MAX_VALUE) < today && !t.completed) LocalBlColors.current.danger else null,
                    onClear = { task = t.copy(dueDay = null, dueMinute = null) }) { dialog = "due" }
                if (t.dueDay == null) {
                    ChipFlow(Modifier.padding(start = 48.dp, bottom = 6.dp)) {
                        AssistChip({ task = t.copy(dueDay = today) }, { Text("Today") })
                        AssistChip({ task = t.copy(dueDay = today + 1) }, { Text("Tomorrow") })
                        AssistChip({ task = t.copy(dueDay = Time.today().with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY)).toEpochDay()) }, { Text("Next week") })
                    }
                } else {
                    PickerRow(Icons.Default.Schedule, "Add time", t.dueMinute?.let { Time.time(it) }, onClear = { task = t.copy(dueMinute = null) }) { dialog = "dueTime" }
                }
                PickerRow(Icons.Default.Repeat, "Repeat", t.repeatRule?.let { Recurrence.parse(it)?.describe(t.dueDay?.let(LocalDate::ofEpochDay)) },
                    onClear = { task = t.copy(repeatRule = null) }) { dialog = "repeat" }
            }
            Spacer(Modifier.height(12.dp))
            BlCard(Modifier.fillMaxWidth()) {
                Text("Priority", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChipFlow(Modifier.padding(top = 6.dp)) {
                    PriorityLabels.forEachIndexed { i, l ->
                        FilterChip(t.priority == i, { task = t.copy(priority = i) }, label = { Text(l) },
                            leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(PriorityColors[i])) })
                    }
                }
                Spacer(Modifier.height(10.dp))
                DropdownField("List", listOf<Pair<Long?, String>>(null to "📥 Tasks") + lists.map { it.id to "${it.emoji} ${it.name}" }, t.listId, { task = t.copy(listId = it) })
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(t.tags, { task = t.copy(tags = it) }, label = { Text("Tags (comma separated)") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Sell, null) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                DropdownField("Time estimate", listOf<Pair<Int?, String>>(null to "None", 15 to "15 min", 30 to "30 min", 45 to "45 min", 60 to "1 hour", 90 to "1.5 hours", 120 to "2 hours", 240 to "Half day", 480 to "Full day"),
                    t.estimateMinutes, { task = t.copy(estimateMinutes = it) })
            }
            Spacer(Modifier.height(12.dp))
            BlCard(Modifier.fillMaxWidth()) {
                PickerRow(Icons.Default.AttachFile, "Add file", t.attachmentPath?.substringAfterLast('/'), onClear = { task = t.copy(attachmentPath = null) }) {
                    val p = t.attachmentPath
                    if (p != null) Files.open(ctx, app.storage.resolve(p)) else attach.launch(arrayOf("*/*"))
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(t.notes, { task = t.copy(notes = it) }, label = { Text("Notes") }, minLines = 4,
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Notes, null) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), modifier = Modifier.fillMaxWidth())
            Text(
                "Created ${Time.dateTime(t.createdAt)}" + (t.completedAt?.let { " · Completed ${Time.dateTime(it)}" } ?: ""),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp).align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(40.dp))
        }
    }

    val cur = task ?: return
    when (dialog) {
        "due" -> DatePickerDialogBox(cur.dueDay?.let(LocalDate::ofEpochDay), { dialog = null }, allowClear = true) { d -> task = cur.copy(dueDay = d?.toEpochDay()) }
        "dueTime" -> TimePickerDialogBox(cur.dueMinute ?: settings.defaultTaskReminderMinute, { dialog = null }) { m ->
            task = cur.copy(dueMinute = m, reminderAt = cur.reminderAt ?: cur.dueDay?.let { Time.millis(LocalDate.ofEpochDay(it), m) })
        }
        "remindDate" -> DatePickerDialogBox(cur.reminderAt?.let { Time.toDate(it) } ?: cur.dueDay?.let(LocalDate::ofEpochDay) ?: Time.today(), { if (pendingReminderDay == null) dialog = null }) { d ->
            if (d != null) { pendingReminderDay = d; dialog = "remindTime" } else dialog = null
        }
        "remindTime" -> TimePickerDialogBox(cur.reminderAt?.let { Time.minuteOfDay(it) } ?: settings.defaultTaskReminderMinute, { dialog = null; pendingReminderDay = null }) { m ->
            pendingReminderDay?.let { task = cur.copy(reminderAt = Time.millis(it, m)) }
            pendingReminderDay = null
        }
        "repeat" -> RepeatDialog(cur.repeatRule, cur.dueDay?.let(LocalDate::ofEpochDay) ?: Time.today(), { dialog = null }) { r ->
            task = cur.copy(repeatRule = r, dueDay = cur.dueDay ?: if (r != null) Time.today().toEpochDay() else null)
        }
        "delete" -> ConfirmDialog("Delete task?", "“${cur.title}” will be permanently deleted.", onDismiss = { dialog = null }) {
            app.io { if (cur.id != 0L) TaskOps.delete(app, cur) }
            task = null
            shell.back()
        }
    }
}

@Composable
private fun StepRow(s: SubtaskEntity) {
    val app = rememberApp()
    var text by remember(s.id) { mutableStateOf(s.title) }
    LaunchedEffect(text) { if (text != s.title) { delay(500); app.db.tasks().updateSubtask(s.copy(title = text)) } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(s.done, { app.io { app.db.tasks().updateSubtask(s.copy(done = it)) } })
        TextField(
            text, { text = it }, singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(textDecoration = if (s.done) TextDecoration.LineThrough else null),
            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { app.io { app.db.tasks().deleteSubtask(s) } }) { Icon(Icons.Default.Close, "Remove step", Modifier.size(18.dp)) }
    }
}

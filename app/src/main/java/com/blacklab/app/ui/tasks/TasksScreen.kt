package com.blacklab.app.ui.tasks

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blacklab.app.core.Keys
import com.blacklab.app.core.Recurrence
import com.blacklab.app.core.Time
import com.blacklab.app.data.SubtaskEntity
import com.blacklab.app.data.TaskEntity
import com.blacklab.app.data.TaskListEntity
import com.blacklab.app.data.TaskOps
import com.blacklab.app.ui.components.pressable
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.ColorPickerRow
import com.blacklab.app.ui.components.ConfirmDialog
import com.blacklab.app.ui.components.EmptyState
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.Pill
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.components.settingsState
import com.blacklab.app.ui.theme.Amber
import com.blacklab.app.ui.theme.LocalBlColors
import com.blacklab.app.ui.theme.Palette
import java.time.LocalDate

val PriorityColors = listOf(Color(0xFF8A8F98), Color(0xFF64B5F6), Color(0xFFFFD54F), Color(0xFFFF8A50), Color(0xFFEF5350))
val PriorityLabels = listOf("None", "Low", "Medium", "High", "Urgent")

@Composable
fun TasksScreen() {
    val app = rememberApp()
    val shell = LocalShell.current
    val settings = settingsState()
    val profile = settings.activeProfile
    val tasks by remember(profile) { app.db.tasks().observe(profile.name) }.collectAsState(emptyList())
    val lists by remember(profile) { app.db.tasks().observeLists(profile.name) }.collectAsState(emptyList())
    val subtasks by remember(profile) { app.db.tasks().observeAllSubtasks(profile.name) }.collectAsState(emptyList())
    var filter by rememberSaveable { mutableStateOf("myday") }
    var search by rememberSaveable { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var showCompleted by remember { mutableStateOf(true) }
    var planMyDay by remember { mutableStateOf(false) }
    var editList by remember { mutableStateOf<TaskListEntity?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val today = Time.today().toEpochDay()
    val subByTask = remember(subtasks) { subtasks.groupBy { it.taskId } }
    val listById = remember(lists) { lists.associateBy { it.id } }

    val filtered = remember(tasks, filter, search, settings.taskSort) {
        var base = when {
            filter == "myday" -> tasks.filter { it.myDayDay == today || (!it.completed && it.dueDay != null && it.dueDay <= today && it.myDayDay == today) }
            filter == "important" -> tasks.filter { it.important }
            filter == "planned" -> tasks.filter { it.dueDay != null }
            filter == "inbox" -> tasks.filter { it.listId == null }
            filter == "completed" -> tasks.filter { it.completed }
            filter.startsWith("list:") -> tasks.filter { it.listId == filter.removePrefix("list:").toLongOrNull() }
            else -> tasks
        }
        if (search.isNotBlank()) base = tasks.filter { it.title.contains(search, true) || it.notes.contains(search, true) || it.tags.contains(search, true) }
        val cmp: Comparator<TaskEntity> = when (settings.taskSort) {
            "PRIORITY" -> compareByDescending<TaskEntity> { it.priority }.thenBy { it.dueDay ?: Long.MAX_VALUE }
            "CREATED" -> compareByDescending { it.createdAt }
            "ALPHA" -> compareBy { it.title.lowercase() }
            "IMPORTANT" -> compareByDescending<TaskEntity> { it.important }.thenBy { it.dueDay ?: Long.MAX_VALUE }
            else -> compareBy<TaskEntity> { it.dueDay ?: Long.MAX_VALUE }.thenBy { it.dueMinute ?: 9999 }.thenByDescending { it.priority }
        }
        base.sortedWith(cmp)
    }
    val open = filtered.filter { !it.completed }
    val done = filtered.filter { it.completed }.sortedByDescending { it.completedAt ?: 0 }

    Scaffold(
        topBar = {
            BlTopBar(
                title = when {
                    filter == "myday" -> "My Day"
                    filter == "important" -> "Important"
                    filter == "planned" -> "Planned"
                    filter == "inbox" -> "Tasks"
                    filter == "completed" -> "Completed"
                    filter.startsWith("list:") -> listById[filter.removePrefix("list:").toLongOrNull()]?.name ?: "List"
                    else -> "All tasks"
                },
                subtitle = if (filter == "myday") Time.longDate(Time.today()) else "${open.size} open · ${done.size} done",
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) search = "" }) { Icon(Icons.Default.Search, "Search") }
                    IconButton(onClick = { planMyDay = true }) { Icon(Icons.Default.WbSunny, "Plan my day") }
                    var sortMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
                        DropdownMenu(sortMenu, { sortMenu = false }) {
                            listOf("DUE" to "Due date", "PRIORITY" to "Priority", "IMPORTANT" to "Importance", "CREATED" to "Creation date", "ALPHA" to "Alphabetically").forEach { (k, l) ->
                                DropdownMenuItem(text = { Text(l) }, onClick = { app.settings.update { putString(Keys.TASK_SORT, k) }; sortMenu = false },
                                    trailingIcon = { if (settings.taskSort == k) Icon(Icons.Default.Check, null) })
                            }
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Clear completed") }, leadingIcon = { Icon(Icons.Default.DeleteSweep, null) }, onClick = { sortMenu = false; confirmClear = true })
                        }
                    }
                },
            )
        },
        bottomBar = { QuickAddBar(filter, lists) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (searching) {
                OutlinedTextField(search, { search = it }, placeholder = { Text("Search tasks, notes, #tags") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            }
            // List selector
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val openTasks = tasks.filter { !it.completed }
                ListChip("☀️ My Day", openTasks.count { it.myDayDay == today }, filter == "myday") { filter = "myday" }
                ListChip("⭐ Important", openTasks.count { it.important }, filter == "important") { filter = "important" }
                ListChip("📅 Planned", openTasks.count { it.dueDay != null }, filter == "planned") { filter = "planned" }
                ListChip("∞ All", openTasks.size, filter == "all") { filter = "all" }
                ListChip("📥 Tasks", openTasks.count { it.listId == null }, filter == "inbox") { filter = "inbox" }
                lists.forEach { l ->
                    ListChip("${l.emoji.ifBlank { "●" }} ${l.name}", openTasks.count { it.listId == l.id }, filter == "list:${l.id}", Color(l.color)) { filter = "list:${l.id}" }
                }
                ListChip("✓ Completed", tasks.count { it.completed }, filter == "completed") { filter = "completed" }
                FilterChip(false, { editList = TaskListEntity(profile = profile.name, name = "", color = Palette[lists.size % Palette.size]) },
                    label = { Text("New list") }, leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(16.dp)) })
            }
            if (filter.startsWith("list:")) {
                val l = listById[filter.removePrefix("list:").toLongOrNull()]
                if (l != null) TextButton(onClick = { editList = l }, modifier = Modifier.padding(start = 8.dp)) {
                    Icon(Icons.Default.Edit, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Edit list")
                }
            }
            if (open.isEmpty() && done.isEmpty()) {
                EmptyState(
                    if (filter == "myday") Icons.Default.WbSunny else Icons.Default.TaskAlt,
                    if (filter == "myday") "Focus on your day" else "Nothing here",
                    if (filter == "myday") "Add tasks below, or tap ☀ to plan your day from suggestions." else "Add a task below. Try: “Walkthrough Bldg C Friday 8am !high #inspection”",
                    Modifier.padding(top = 32.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    val groups: List<Pair<String, List<TaskEntity>>> =
                        if (filter == "myday" || filter == "completed" || settings.taskSort != "DUE") listOf("" to open) else sectionize(open, today)
                    groups.forEach { (label, items) ->
                        if (items.isEmpty()) return@forEach
                        if (label.isNotEmpty()) item(key = "h-$label") {
                            Text(label, style = MaterialTheme.typography.labelLarge,
                                color = if (label == "Overdue") LocalBlColors.current.danger else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))
                        }
                        items(items, key = { it.id }) { t ->
                            SwipeTask(t, subByTask[t.id].orEmpty(), listById[t.listId], showList = !filter.startsWith("list:"))
                        }
                    }
                    if (done.isNotEmpty() && filter != "completed") {
                        item(key = "done-header") {
                            Row(Modifier.fillMaxWidth().clickable { showCompleted = !showCompleted }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (showCompleted) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Completed (${done.size})", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (showCompleted || filter == "completed") items(done.take(200), key = { it.id }) { t ->
                        SwipeTask(t, subByTask[t.id].orEmpty(), listById[t.listId], showList = true)
                    }
                }
            }
        }
    }

    if (planMyDay) PlanMyDaySheet(tasks.filter { !it.completed }, onDismiss = { planMyDay = false })
    editList?.let { l -> ListEditorDialog(l, onDismiss = { editList = null }, onDeleted = { if (filter == "list:${l.id}") filter = "all" }) }
    if (confirmClear) ConfirmDialog("Clear completed?", "Permanently delete all completed tasks in this account.", "Clear", onDismiss = { confirmClear = false }) {
        app.io { app.db.tasks().clearCompleted(profile.name) }
    }
}

private fun sectionize(open: List<TaskEntity>, today: Long): List<Pair<String, List<TaskEntity>>> {
    val endOfWeek = today + 7
    return listOf(
        "Overdue" to open.filter { it.dueDay != null && it.dueDay < today },
        "Today" to open.filter { it.dueDay == today },
        "Tomorrow" to open.filter { it.dueDay == today + 1 },
        "Next 7 days" to open.filter { it.dueDay != null && it.dueDay > today + 1 && it.dueDay <= endOfWeek },
        "Later" to open.filter { it.dueDay != null && it.dueDay > endOfWeek },
        "No date" to open.filter { it.dueDay == null },
    )
}

@Composable
private fun ListChip(label: String, count: Int, selected: Boolean, color: Color? = null, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(if (count > 0) "$label  $count" else label, maxLines = 1) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = (color ?: MaterialTheme.colorScheme.primary).copy(alpha = 0.22f),
            selectedLabelColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

@Composable
fun SwipeTask(t: TaskEntity, steps: List<SubtaskEntity>, list: TaskListEntity?, showList: Boolean) {
    val app = rememberApp()
    val shell = LocalShell.current
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        when (v) {
            SwipeToDismissBoxValue.StartToEnd -> {
                app.io { TaskOps.setCompleted(app, t, !t.completed) }
                if (!t.completed) shell.toast("Completed “${t.title.take(30)}”", "Undo") { app.io { app.db.tasks().get(t.id)?.let { TaskOps.setCompleted(app, it, false) } } }
                false
            }
            SwipeToDismissBoxValue.EndToStart -> {
                app.io { TaskOps.delete(app, t) }
                shell.toast("Task deleted", "Undo") { app.io { app.db.tasks().insert(t); steps.forEach { app.db.tasks().insertSubtask(it) } } }
                true
            }
            else -> false
        }
    })
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val dir = state.dismissDirection
            val (color, icon, align) = when (dir) {
                SwipeToDismissBoxValue.StartToEnd -> Triple(LocalBlColors.current.success, Icons.Default.Check, Alignment.CenterStart)
                SwipeToDismissBoxValue.EndToStart -> Triple(LocalBlColors.current.danger, Icons.Default.Delete, Alignment.CenterEnd)
                else -> Triple(Color.Transparent, Icons.Default.Check, Alignment.Center)
            }
            Box(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 3.dp).clip(RoundedCornerShape(16.dp)).background(color.copy(alpha = 0.85f)).padding(horizontal = 20.dp), contentAlignment = align) {
                Icon(icon, null, tint = Color.White)
            }
        },
    ) { TaskRow(t, steps, list, showList) }
}

@Composable
fun TaskRow(t: TaskEntity, steps: List<SubtaskEntity>, list: TaskListEntity?, showList: Boolean = true) {
    val app = rememberApp()
    val shell = LocalShell.current
    val today = Time.today().toEpochDay()
    val pc = PriorityColors[t.priority.coerceIn(0, 4)]
    var menu by remember { mutableStateOf(false) }
    if (menu) com.blacklab.app.ui.components.ActionSheet(
        title = t.title, subtitle = listOfNotNull(t.dueDay?.let { "Due " + Time.relativeDay(java.time.LocalDate.ofEpochDay(it)) }, list?.name).joinToString(" · ").ifBlank { null },
        onDismiss = { menu = false },
        quick = listOf(
            com.blacklab.app.ui.components.SheetAction(if (t.completed) "Reopen" else "Complete", Icons.Default.Check) { app.io { TaskOps.setCompleted(app, t, !t.completed) } },
            com.blacklab.app.ui.components.SheetAction(if (t.important) "Unstar" else "Important", Icons.Default.Star) { app.io { TaskOps.save(app, t.copy(important = !t.important)) } },
            com.blacklab.app.ui.components.SheetAction(if (t.myDayDay == today) "Off My Day" else "My Day", Icons.Default.WbSunny) { app.io { TaskOps.save(app, t.copy(myDayDay = if (t.myDayDay == today) null else today)) } },
            com.blacklab.app.ui.components.SheetAction("Delete", Icons.Default.Delete, destructive = true) {
                app.io { TaskOps.delete(app, t) }
                shell.toast("Task deleted", "Undo") { app.io { app.db.tasks().insert(t); steps.forEach { app.db.tasks().insertSubtask(it) } } }
            },
        ),
        actions = listOf(
            com.blacklab.app.ui.components.SheetAction("Open details", Icons.Default.OpenInFull) { shell.go("task/${t.id}") },
            com.blacklab.app.ui.components.SheetAction("Due today", Icons.Default.Today) { app.io { TaskOps.save(app, t.copy(dueDay = today)) } },
            com.blacklab.app.ui.components.SheetAction("Due tomorrow", Icons.Default.EventAvailable) { app.io { TaskOps.save(app, t.copy(dueDay = today + 1)) } },
            com.blacklab.app.ui.components.SheetAction("Raise priority", Icons.Default.PriorityHigh, hint = "Now: " + listOf("none", "low", "medium", "high", "urgent")[t.priority.coerceIn(0, 4)]) {
                app.io { TaskOps.save(app, t.copy(priority = (t.priority + 1).coerceAtMost(4))) }
            },
            com.blacklab.app.ui.components.SheetAction("Duplicate", Icons.Default.ContentCopy) { app.io { TaskOps.save(app, t.copy(id = 0, completed = false, createdAt = Time.now(), updatedAt = Time.now())) } },
            com.blacklab.app.ui.components.SheetAction("Share", Icons.Default.Share) {
                com.blacklab.app.core.Files.shareText(shell.nav.context, buildString { append(t.title); if (t.notes.isNotBlank()) append("\n\n").append(t.notes); steps.forEach { append("\n• ").append(it.title) } }, t.title)
            },
        ),
    )
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp).clip(RoundedCornerShape(18.dp))
            .pressable(onClick = { shell.go("task/${t.id}") }, onLongClick = { menu = true }),
    ) {
        Row(Modifier.padding(start = 6.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            // completion circle tinted by priority
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable { app.io { TaskOps.setCompleted(app, t, !t.completed) } },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(22.dp).clip(CircleShape)
                        .background(if (t.completed) LocalBlColors.current.accent else Color.Transparent)
                        .border(2.dp, if (t.completed) LocalBlColors.current.accent else if (t.priority > 0) pc else MaterialTheme.colorScheme.outline, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { if (t.completed) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp)) }
            }
            Column(Modifier.weight(1f).padding(vertical = 2.dp)) {
                Text(
                    t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textDecoration = if (t.completed) TextDecoration.LineThrough else null,
                    color = if (t.completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                val meta = buildList<@Composable () -> Unit> {
                    if (t.myDayDay == today && !t.completed) add { MetaText("☀ My Day") }
                    t.dueDay?.let { d ->
                        val overdue = d < today && !t.completed
                        add {
                            MetaText("📅 " + Time.relativeDay(LocalDate.ofEpochDay(d)) + (t.dueMinute?.let { " " + Time.time(it) } ?: ""),
                                if (overdue) LocalBlColors.current.danger else if (d == today) MaterialTheme.colorScheme.primary else null)
                        }
                    }
                    if (t.reminderAt != null && !t.completed) add { MetaText("🔔") }
                    if (t.repeatRule != null) add { MetaText("↻") }
                    if (steps.isNotEmpty()) add { MetaText("${steps.count { it.done }}/${steps.size} steps") }
                    if (t.notes.isNotBlank()) add { MetaText("📝") }
                    if (t.attachmentPath != null) add { MetaText("📎") }
                    if (showList && list != null) add { MetaText("${list.emoji} ${list.name}", Color(list.color)) }
                    t.tags.split(',', ' ').filter { it.isNotBlank() }.take(3).forEach { tag -> add { MetaText("#$tag", MaterialTheme.colorScheme.secondary) } }
                }
                if (meta.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 2.dp)) { meta.take(6).forEach { it() } }
            }
            if (t.priority >= 3 && !t.completed) Pill(PriorityLabels[t.priority], pc, Modifier.padding(end = 2.dp))
            IconButton(onClick = { app.io { TaskOps.save(app, t.copy(important = !t.important)) } }) {
                if (t.important) Icon(Icons.Default.Star, "Important", tint = Amber) else Icon(Icons.Outlined.StarBorder, "Mark important", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MetaText(text: String, color: Color? = null) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = color ?: MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
}

@Composable
private fun QuickAddBar(filter: String, lists: List<TaskListEntity>) {
    val app = rememberApp()
    val shell = LocalShell.current
    val profile = settingsState().activeProfile
    var text by rememberSaveable { mutableStateOf("") }
    val parsed = remember(text) { if (text.isBlank()) null else QuickParse.parse(text) }
    fun submit() {
        // Read the live state (not the composition-time snapshot) so a doubled IME action can't add twice
        val raw = text
        if (raw.isBlank()) return
        text = ""
        val p = QuickParse.parse(raw)
        val today = Time.today().toEpochDay()
        val listId = p.listName?.let { n -> lists.firstOrNull { it.name.replace(" ", "").startsWith(n, true) }?.id }
            ?: filter.removePrefix("list:").toLongOrNull()
        val task = TaskEntity(
            profile = profile.name,
            title = p.title,
            listId = listId,
            dueDay = p.dueDay?.toEpochDay() ?: if (filter == "planned") today else null,
            dueMinute = p.dueMinute,
            reminderAt = if (p.dueDay != null && p.dueMinute != null) Time.millis(p.dueDay, p.dueMinute) else null,
            priority = p.priority,
            important = p.important || filter == "important",
            myDayDay = if (filter == "myday") today else null,
            tags = p.tags.joinToString(","),
            repeatRule = p.repeat,
        )
        app.io { TaskOps.save(app, task) }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.imePadding()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            AnimatedVisibility(parsed != null && (parsed.dueDay != null || parsed.priority > 0 || parsed.tags.isNotEmpty() || parsed.repeat != null || parsed.listName != null)) {
                parsed?.let { p ->
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        p.dueDay?.let { Pill("📅 ${Time.relativeDay(it)}${p.dueMinute?.let { m -> " " + Time.time(m) } ?: ""}") }
                        if (p.priority > 0) Pill(PriorityLabels[p.priority], PriorityColors[p.priority])
                        p.repeat?.let { Pill("↻ " + (Recurrence.parse(it)?.describe() ?: "Repeats"), MaterialTheme.colorScheme.secondary) }
                        p.listName?.let { Pill("@$it", MaterialTheme.colorScheme.tertiary) }
                        p.tags.forEach { Pill("#$it", MaterialTheme.colorScheme.secondary) }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    placeholder = { Text("Add a task") },
                    leadingIcon = { Icon(Icons.Default.Add, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                    modifier = Modifier.weight(1f),
                )
                if (text.isNotBlank()) IconButton(onClick = { submit() }) { Icon(Icons.Default.ArrowUpward, "Add", tint = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

@Composable
private fun PlanMyDaySheet(open: List<TaskEntity>, onDismiss: () -> Unit) {
    val app = rememberApp()
    val today = Time.today().toEpochDay()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val suggestions = open.filter { it.myDayDay != today }.let { list ->
        listOf(
            "Overdue" to list.filter { it.dueDay != null && it.dueDay < today },
            "Due today" to list.filter { it.dueDay == today },
            "Due tomorrow" to list.filter { it.dueDay == today + 1 },
            "Important" to list.filter { it.important && (it.dueDay == null || it.dueDay > today + 1) },
            "Recently added" to list.filter { it.dueDay == null && !it.important }.sortedByDescending { it.createdAt }.take(5),
        ).filter { it.second.isNotEmpty() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Plan my day", style = MaterialTheme.typography.headlineSmall)
            Text("Pick what deserves your focus today.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            if (suggestions.isEmpty()) Text("No suggestions — you're on top of it. ✨", modifier = Modifier.padding(vertical = 24.dp))
            LazyColumn(Modifier.height(480.dp)) {
                suggestions.forEach { (label, items) ->
                    item { Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
                    items(items, key = { "s${it.id}" }) { t ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                t.dueDay?.let { Text(Time.relativeDay(LocalDate.ofEpochDay(it)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            TextButton(onClick = { app.io { TaskOps.save(app, t.copy(dueDay = today + 1, myDayDay = null)) } }) { Text("Tomorrow") }
                            IconButton(onClick = { app.io { TaskOps.save(app, t.copy(myDayDay = today)) } }) { Icon(Icons.Default.AddCircle, "Add to My Day", tint = MaterialTheme.colorScheme.primary) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ListEditorDialog(list: TaskListEntity, onDismiss: () -> Unit, onDeleted: () -> Unit) {
    val app = rememberApp()
    var name by remember { mutableStateOf(list.name) }
    var emoji by remember { mutableStateOf(list.emoji) }
    var color by remember { mutableStateOf(list.color) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (list.id == 0L) "New list" else "Edit list") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(emoji, { emoji = it.take(2) }, label = { Text("Icon") }, singleLine = true, modifier = Modifier.width(72.dp))
                    OutlinedTextField(name, { name = it }, label = { Text("List name") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("📋", "🛠️", "🏢", "🔍", "📞", "🛒", "🏠", "💡", "🎓", "💰").forEach { e ->
                        Text(e, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { emoji = e }.padding(4.dp))
                    }
                }
                ColorPickerRow(color, { color = it })
                if (list.id != 0L) TextButton(onClick = { confirmDelete = true }) { Text("Delete list", color = LocalBlColors.current.danger) }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                val e = list.copy(name = name.trim(), emoji = emoji.trim(), color = color)
                app.io { if (e.id == 0L) app.db.tasks().insertList(e) else app.db.tasks().updateList(e) }
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (confirmDelete) ConfirmDialog("Delete “${list.name}”?", "Tasks in this list are kept and moved to Tasks.", onDismiss = { confirmDelete = false }) {
        app.io { app.db.tasks().detachList(list.id); app.db.tasks().deleteList(list) }
        onDeleted(); onDismiss()
    }
}

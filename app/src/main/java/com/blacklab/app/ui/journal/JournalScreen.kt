package com.blacklab.app.ui.journal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blacklab.app.core.Profile
import com.blacklab.app.core.Time
import com.blacklab.app.data.JournalEntity
import com.blacklab.app.ui.components.pressable
import com.blacklab.app.ui.components.BlCard
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.EmptyState
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.Pill
import com.blacklab.app.ui.components.SectionHeader
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.components.settingsState
import com.blacklab.app.ui.schedule.WeekdayHeader
import com.blacklab.app.ui.theme.Amber
import java.time.LocalDate
import java.time.YearMonth

val Moods = listOf("" to "No mood", "😞" to "Awful", "😕" to "Bad", "😐" to "Okay", "🙂" to "Good", "😄" to "Great")

data class JournalTemplate(val id: String, val name: String, val emoji: String, val html: String)

val JournalTemplates = listOf(
    JournalTemplate("blank", "Free write", "✍️", "<p><br></p>"),
    JournalTemplate("daily", "Daily reflection", "🌙", "<h2>What went well today?</h2><p><br></p><h2>What was challenging?</h2><p><br></p><h2>What did I learn?</h2><p><br></p><h2>Tomorrow's top 3</h2><ol><li><br></li><li><br></li><li><br></li></ol>"),
    JournalTemplate("gratitude", "Gratitude", "🙏", "<h2>Three things I'm grateful for</h2><ol><li><br></li><li><br></li><li><br></li></ol><h2>Someone who made a difference</h2><p><br></p><h2>A small win</h2><p><br></p>"),
    JournalTemplate("worklog", "Work log", "🏢", "<h2>Accomplishments</h2><ul><li><br></li></ul><h2>Meetings &amp; decisions</h2><ul><li><br></li></ul><h2>Issues &amp; follow-ups</h2><ul class=\"checklist\"><li><br></li></ul><h2>Notes for tomorrow</h2><p><br></p>"),
    JournalTemplate("lessons", "Lessons learned", "💡", "<h2>Situation</h2><p><br></p><h2>What happened</h2><p><br></p><h2>What I learned</h2><p><br></p><h2>What I'll do differently</h2><p><br></p>"),
    JournalTemplate("goals", "Goals & intentions", "🎯", "<h2>This week's intentions</h2><p><br></p><h2>Top priorities</h2><ul class=\"checklist\"><li><br></li><li><br></li><li><br></li></ul><h2>Habits I'm building</h2><ul><li><br></li></ul>"),
    JournalTemplate("study", "Study log", "🎓", "<h2>What I studied</h2><p><br></p><h2>Key concepts</h2><ul><li><br></li></ul><h2>Questions to follow up</h2><ul><li><br></li></ul><h2>Next session</h2><p><br></p>"),
)

private val PersonalPrompts = listOf(
    "What gave you energy today, and what drained it?", "Describe a moment today you'd like to remember.", "What are you looking forward to this week?",
    "What's one thing you'd tell your younger self?", "Who are you grateful for right now, and why?", "What's a small habit you want to build?",
    "What did you learn about yourself recently?", "Write about a place that makes you feel calm.", "What does a perfect weekend look like?",
    "What are you avoiding, and what's the first step?", "What made you laugh this week?", "What would make tomorrow great?",
    "Describe a challenge you handled well.", "What's on your mind that you haven't said out loud?", "What do you want more of in your life?",
    "What boundaries do you need to protect your time?", "Which goal deserves more of your attention?", "What are you proud of this month?",
    "If today had a title, what would it be?", "What's something you're curious about right now?",
)
private val ProfessionalPrompts = listOf(
    "What was the most important decision you made today?", "Which facility issue keeps recurring, and what's its root cause?", "Who on your team deserves recognition this week?",
    "What would you do with 10% more budget? With 10% less?", "What risk are you watching that others might be missing?", "What did a vendor or contractor do well — or poorly — recently?",
    "What process would you simplify if you could?", "Which preventive task would prevent the most reactive work?", "What feedback did you receive, and what will you do with it?",
    "What's the one metric that best describes your operation this week?", "What did you delegate today — and what should you have?", "What's a safety observation worth sharing with the team?",
    "What conversation do you need to have this week?", "Where did communication break down, and how can it improve?", "What project milestone is at risk?",
    "What did you learn from a building occupant or customer?", "What would make your next audit or inspection go smoothly?", "How are you developing your next leader?",
    "Which energy or sustainability win can you capture?", "What would you want leadership to know about your department?",
)

fun promptOfDay(profile: Profile, day: LocalDate = Time.today()): String {
    val list = if (profile == Profile.PERSONAL) PersonalPrompts else ProfessionalPrompts
    return list[(day.dayOfYear + day.year) % list.size]
}

fun streak(entries: List<JournalEntity>): Int {
    val days = entries.map { it.entryDay }.toSet()
    var d = Time.today().toEpochDay()
    if (d !in days) d -= 1 // today not written yet doesn't break the streak
    var n = 0
    while (d in days) { n++; d-- }
    return n
}

@Composable
fun JournalScreen() {
    val app = rememberApp()
    val shell = LocalShell.current
    val s = settingsState()
    val profile = s.activeProfile
    val entries by remember(profile) { app.db.journal().observe(profile.name) }.collectAsState(emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var dayFilter by rememberSaveable { mutableStateOf<Long?>(null) }
    var showCalendar by rememberSaveable { mutableStateOf(false) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var newSheet by remember { mutableStateOf<String?>(null) } // null closed, else prompt text ("" = none)
    val today = Time.today()

    val shown = remember(entries, query, favoritesOnly, dayFilter) {
        entries.filter {
            (!favoritesOnly || it.favorite) && (dayFilter == null || it.entryDay == dayFilter) &&
                (query.isBlank() || it.title.contains(query, true) || it.preview.contains(query, true) || it.tags.contains(query, true))
        }
    }
    val onThisDay = remember(entries) {
        entries.filter { val d = LocalDate.ofEpochDay(it.entryDay); d.dayOfMonth == today.dayOfMonth && d.monthValue == today.monthValue && d.year < today.year }
    }
    val monthEntries = remember(entries) { entries.filter { LocalDate.ofEpochDay(it.entryDay) >= today.withDayOfMonth(1) } }

    Scaffold(
        topBar = {
            BlTopBar("Journal", subtitle = app.settings.profileName(profile), actions = {
                IconButton(onClick = { searching = !searching; if (!searching) query = "" }) { Icon(Icons.Default.Search, "Search") }
                IconButton(onClick = { favoritesOnly = !favoritesOnly }) { Icon(if (favoritesOnly) Icons.Default.Star else Icons.Default.StarOutline, "Favorites", tint = if (favoritesOnly) Amber else MaterialTheme.colorScheme.onSurface) }
                IconButton(onClick = { showCalendar = !showCalendar; if (!showCalendar) dayFilter = null }) { Icon(Icons.Default.CalendarMonth, "Calendar") }
            })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { newSheet = "" }, icon = { Icon(Icons.Default.Edit, null) }, text = { Text("Write") })
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp)) {
            if (searching) item {
                OutlinedTextField(query, { query = it }, placeholder = { Text("Search entries") }, singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    StatBox("🔥", "${streak(entries)}", "day streak", Modifier.weight(1f))
                    StatBox("📅", "${monthEntries.size}", "this month", Modifier.weight(1f))
                    StatBox("✍️", "%,d".format(entries.sumOf { it.wordCount }), "words", Modifier.weight(1f))
                }
            }
            if (showCalendar) item {
                BlCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.Default.ChevronLeft, "Previous") }
                        Text(Time.monthYear(month.atDay(1)), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        IconButton(onClick = { month = month.plusMonths(1) }) { Icon(Icons.Default.ChevronRight, "Next") }
                    }
                    WeekdayHeader(s.weekStart)
                    val byDay = entries.groupBy { it.entryDay }
                    val first = Time.weekStart(month.atDay(1), s.weekStart)
                    repeat(6) { w ->
                        Row {
                            repeat(7) { i ->
                                val d = first.plusDays((w * 7 + i).toLong())
                                val has = byDay[d.toEpochDay()]
                                val inMonth = YearMonth.from(d) == month
                                Box(
                                    Modifier.weight(1f).height(38.dp).padding(2.dp).clip(CircleShape)
                                        .background(when { dayFilter == d.toEpochDay() -> MaterialTheme.colorScheme.primary; has != null -> MaterialTheme.colorScheme.primary.copy(alpha = 0.25f); else -> Color.Transparent })
                                        .clickable { dayFilter = if (dayFilter == d.toEpochDay()) null else d.toEpochDay() },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        has?.firstOrNull { it.mood > 0 }?.let { Moods[it.mood].first } ?: "${d.dayOfMonth}",
                                        fontSize = if (has?.any { it.mood > 0 } == true) 16.sp else 12.sp,
                                        color = if (!inMonth) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f) else if (d == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        fontWeight = if (d == today) FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                            }
                        }
                    }
                    if (dayFilter != null) TextButton(onClick = { shell.go("journal/0?day=$dayFilter") }) { Text("Write for ${Time.date(LocalDate.ofEpochDay(dayFilter!!))}") }
                }
            }
            item {
                BlCard(Modifier.fillMaxWidth().padding(vertical = 6.dp), onClick = { newSheet = promptOfDay(profile) }) {
                    Text("PROMPT OF THE DAY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(promptOfDay(profile), style = MaterialTheme.typography.titleMedium)
                    Text("Tap to write about this", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
            if (onThisDay.isNotEmpty() && dayFilter == null && query.isBlank()) {
                item { SectionHeader("On this day") }
                items(onThisDay, key = { "otd${it.id}" }) { EntryCard(it) }
            }
            item { SectionHeader(if (dayFilter != null) Time.date(LocalDate.ofEpochDay(dayFilter!!)) else "Entries") }
            if (shown.isEmpty()) item {
                EmptyState(Icons.Default.AutoStories, if (entries.isEmpty()) "Start your journal" else "No entries match",
                    if (entries.isEmpty()) "A few lines a day builds a record you'll value. Your entries are saved as files in BlackLab/Journal." else "Try clearing filters.")
            }
            items(shown, key = { it.id }) { EntryCard(it) }
        }
    }

    newSheet?.let { prompt ->
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { newSheet = null }, sheetState = sheet) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("New entry", style = MaterialTheme.typography.titleLarge)
                if (prompt.isNotBlank()) Text("“$prompt”", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
            LazyVerticalGrid(GridCells.Fixed(2), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(JournalTemplates, key = { it.id }) { t ->
                    Surface(onClick = {
                        newSheet = null
                        JournalDraft.prompt = prompt
                        val dayArg = dayFilter?.let { "&day=$it" } ?: ""
                        shell.go("journal/0?template=${t.id}$dayArg")
                    }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(t.emoji, fontSize = 22.sp); Spacer(Modifier.width(10.dp)); Text(t.name, style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Passes the chosen prompt to the editor without encoding it in the route. */
object JournalDraft { var prompt: String = "" }

@Composable
private fun StatBox(emoji: String, value: String, label: String, modifier: Modifier) {
    BlCard(modifier, contentPadding = PaddingValues(12.dp)) {
        Text(emoji, fontSize = 18.sp)
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EntryCard(e: JournalEntity) {
    val shell = LocalShell.current
    val d = LocalDate.ofEpochDay(e.entryDay)
    val app = com.blacklab.app.ui.components.rememberApp()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (menu) com.blacklab.app.ui.components.ActionSheet(
        title = e.title.ifBlank { "Untitled entry" }, subtitle = Time.longDate(d) + if (e.wordCount > 0) " · ${e.wordCount} words" else "",
        onDismiss = { menu = false },
        quick = listOf(
            com.blacklab.app.ui.components.SheetAction("Open", Icons.Default.Edit) { shell.go("journal/${e.id}") },
            com.blacklab.app.ui.components.SheetAction(if (e.favorite) "Unfavorite" else "Favorite", Icons.Default.Star) { app.io { app.db.journal().update(e.copy(favorite = !e.favorite)) } },
            com.blacklab.app.ui.components.SheetAction("Share", Icons.Default.Share) {
                app.io { val text = com.blacklab.app.data.ContentStore.plainText(app.content.readBody(e.filePath)); kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { com.blacklab.app.core.Files.shareText(ctx, text, e.title) } }
            },
            com.blacklab.app.ui.components.SheetAction("Delete", Icons.Default.Delete, destructive = true) { confirmDelete = true },
        ),
        actions = listOf(
            com.blacklab.app.ui.components.SheetAction("Open .html file", Icons.Default.Html) { com.blacklab.app.core.Files.open(ctx, app.content.file(e.filePath)) },
            com.blacklab.app.ui.components.SheetAction("New entry for this day", Icons.Default.Add) { shell.go("journal/0?day=${e.entryDay}") },
        ),
    )
    if (confirmDelete) com.blacklab.app.ui.components.ConfirmDialog("Delete entry?", "“${e.title.ifBlank { "Untitled entry" }}” will be permanently deleted.", onDismiss = { confirmDelete = false }) {
        app.io { app.content.delete(e.filePath); app.db.journal().delete(e) }
    }
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(18.dp))
            .pressable(onClick = { shell.go("journal/${e.id}") }, onLongClick = { menu = true })) {
        Row(Modifier.padding(12.dp)) {
            Column(Modifier.width(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Time.dayShort(d.dayOfWeek).uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text("${d.dayOfMonth}", style = MaterialTheme.typography.headlineSmall)
                Text(Time.short(d).substringBefore(' ') + if (d.year != Time.today().year) " '${d.year % 100}" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.title.ifBlank { "Untitled entry" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (e.mood > 0) Text(Moods[e.mood].first, fontSize = 18.sp)
                    if (e.favorite) Icon(Icons.Default.Star, null, tint = Amber, modifier = Modifier.size(16.dp))
                }
                Text(e.preview.ifBlank { "…" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                val tags = e.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (tags.isNotEmpty() || e.location.isNotBlank()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    if (e.location.isNotBlank()) Pill("📍 ${e.location}", MaterialTheme.colorScheme.tertiary)
                    tags.take(3).forEach { Pill("#$it", MaterialTheme.colorScheme.secondary) }
                }
            }
        }
    }
}

@Suppress("unused")
@Composable
private fun FilterRow(favoritesOnly: Boolean, onToggle: () -> Unit) { FilterChip(favoritesOnly, onToggle, label = { Text("Favorites") }) }

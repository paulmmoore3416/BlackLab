package com.blacklab.app.ui.schedule

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blacklab.app.core.Files
import com.blacklab.app.core.Folder
import com.blacklab.app.core.Keys
import com.blacklab.app.core.Profile
import com.blacklab.app.core.Recurrence
import com.blacklab.app.core.Time
import com.blacklab.app.core.parseExDates
import com.blacklab.app.core.toExDateString
import com.blacklab.app.data.CalendarLogic
import com.blacklab.app.data.EventCalendarEntity
import com.blacklab.app.data.EventEntity
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.ColorDot
import com.blacklab.app.ui.components.ColorPickerRow
import com.blacklab.app.ui.components.EmptyState
import com.blacklab.app.ui.components.LabeledSwitchRow
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.SectionHeader
import com.blacklab.app.ui.components.TextInputDialog
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.components.settingsState
import com.blacklab.app.ui.theme.LocalBlColors
import com.blacklab.app.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.roundToInt

enum class CalView(val label: String, val days: Int) { MONTH("Month", 0), WEEK("Week", 7), THREE("3 days", 3), DAY("Day", 1), AGENDA("Schedule", 0) }

private const val CENTER = 1200
private val HourHeight = 56.dp

fun groupByDay(items: List<CalItem>, from: LocalDate, to: LocalDate): Map<LocalDate, List<CalItem>> {
    val map = HashMap<LocalDate, MutableList<CalItem>>()
    items.forEach { it ->
        var d = maxOf(it.start.toLocalDate(), from)
        val last = minOf(if (it.allDay && it.end.toLocalDate() > it.start.toLocalDate()) it.end.toLocalDate().minusDays(1) else it.end.toLocalDate().let { e -> if (e > it.start.toLocalDate() && it.end.toLocalTime() == LocalTime.MIDNIGHT) e.minusDays(1) else e }, to)
        while (d <= last) { map.getOrPut(d) { ArrayList() }.add(it); d = d.plusDays(1) }
    }
    return map
}

@Composable
fun ScheduleScreen() {
    val app = rememberApp()
    val shell = LocalShell.current
    val settings = settingsState()
    val ctx = LocalContext.current
    val view = runCatching { CalView.valueOf(settings.calendarView) }.getOrDefault(CalView.MONTH)
    val today = Time.today()
    var selected by rememberSaveable { mutableLongStateOf(today.toEpochDay()) }
    val selDate = LocalDate.ofEpochDay(selected)
    var detail by remember { mutableStateOf<CalItem?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // ---- pagers
    val baseMonth = remember { YearMonth.from(today) }
    val monthPager = rememberPagerState(initialPage = CENTER) { 2 * CENTER }
    val gridPager = rememberPagerState(initialPage = CENTER) { 2 * CENTER }
    val visibleMonth = baseMonth.plusMonths((monthPager.currentPage - CENTER).toLong())
    fun pageStart(page: Int): LocalDate = when (view) {
        CalView.WEEK -> Time.weekStart(today, settings.weekStart).plusWeeks((page - CENTER).toLong())
        CalView.THREE -> today.plusDays(((page - CENTER) * 3).toLong())
        else -> today.plusDays((page - CENTER).toLong())
    }
    val gridStart = pageStart(gridPager.currentPage)

    val (from, to) = when (view) {
        CalView.MONTH -> visibleMonth.minusMonths(1).atDay(1).minusDays(7) to visibleMonth.plusMonths(1).atEndOfMonth().plusDays(7)
        CalView.AGENDA -> today.minusDays(14) to today.plusDays(120)
        else -> gridStart.minusDays(view.days.toLong()) to gridStart.plusDays(view.days * 2L)
    }
    val items = rememberCalendarItems(from, to)
    val byDay = remember(items, from, to) { groupByDay(items, from, to) }

    val icsImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) app.io {
            val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return@io
            val calId = app.db.events().calendars(settings.activeProfile.name).firstOrNull()?.id
            val events = Ics.parse(text, settings.activeProfile.name, calId)
            events.forEach { app.db.events().insert(it) }
            app.requestReminderSync()
            shell.toast("Imported ${events.size} event(s)")
        }
    }

    Scaffold(
        topBar = {
            BlTopBar(
                title = when (view) {
                    CalView.MONTH -> Time.monthYear(visibleMonth.atDay(1))
                    CalView.AGENDA -> "Schedule"
                    else -> Time.monthYear(gridStart)
                },
                subtitle = if (settings.showOtherProfileInCalendar) "All accounts" else app.settings.profileName(settings.activeProfile),
                actions = {
                    IconButton(onClick = {
                        selected = today.toEpochDay()
                        scope.launch { monthPager.animateScrollToPage(CENTER); gridPager.animateScrollToPage(CENTER) }
                    }) { Icon(Icons.Default.Today, "Today") }
                    var viewMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { viewMenu = true }) {
                            Icon(when (view) { CalView.MONTH -> Icons.Default.CalendarViewMonth; CalView.WEEK -> Icons.Default.CalendarViewWeek; CalView.AGENDA -> Icons.Default.ViewAgenda; else -> Icons.Default.CalendarViewDay }, "View")
                        }
                        DropdownMenu(viewMenu, { viewMenu = false }) {
                            CalView.entries.forEach { v ->
                                DropdownMenuItem(text = { Text(v.label) }, onClick = {
                                    app.settings.update { putString(Keys.CALENDAR_VIEW, v.name) }; viewMenu = false
                                    scope.launch { gridPager.scrollToPage(CENTER) }
                                }, trailingIcon = { if (v == view) Icon(Icons.Default.Check, null) })
                            }
                        }
                    }
                    var more by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { more = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(more, { more = false }) {
                            DropdownMenuItem(text = { Text("Find a time") }, leadingIcon = { Icon(Icons.Default.EventAvailable, null) }, onClick = { more = false; dialog = "find" })
                            DropdownMenuItem(text = { Text("Calendars & sources") }, leadingIcon = { Icon(Icons.Default.Layers, null) }, onClick = { more = false; dialog = "calendars" })
                            DropdownMenuItem(text = { Text("Import .ics") }, leadingIcon = { Icon(Icons.Default.Upload, null) }, onClick = { more = false; icsImport.launch(arrayOf("text/calendar", "*/*")) })
                            DropdownMenuItem(text = { Text("Export all to .ics") }, leadingIcon = { Icon(Icons.Default.Download, null) }, onClick = {
                                more = false
                                app.io {
                                    val evs = app.db.events().all().filter { it.profile == settings.activeProfile.name }
                                    val f = Files.unique(app.storage.dir(settings.activeProfile, Folder.EXPORTS), "BlackLab calendar ${Time.numeric(today).replace('/', '-')}", "ics")
                                    f.writeText(Ics.export(evs))
                                    Files.share(ctx, listOf(f), "BlackLab calendar")
                                }
                            })
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    val d = if (view == CalView.MONTH || view == CalView.AGENDA) selDate else gridStart
                    val startMin = if (d == today) ((LocalTime.now().hour + 1) * 60).coerceAtMost(23 * 60) else 9 * 60
                    shell.go("event/0?start=${Time.millis(d, startMin)}")
                },
                icon = { Icon(Icons.Default.Add, null) }, text = { Text("Event") },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            when (view) {
                CalView.MONTH -> {
                    WeekdayHeader(settings.weekStart, Modifier.padding(horizontal = 4.dp))
                    HorizontalPager(monthPager, Modifier.fillMaxWidth().height(60.dp * 6 + 4.dp), beyondViewportPageCount = 0) { page ->
                        val m = baseMonth.plusMonths((page - CENTER).toLong())
                        MonthGrid(m, settings.weekStart, selDate, today, byDay) { selected = it.toEpochDay() }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    DayList(selDate, byDay[selDate].orEmpty(), Modifier.weight(1f)) { detail = it }
                }
                CalView.AGENDA -> AgendaList(today, byDay) { detail = it }
                else -> TimeGridView(view, gridPager, ::pageStart, byDay, today, settings.workStartMinute,
                    onSlot = { ldt -> shell.go("event/0?start=${Time.millis(ldt)}") },
                    onAllDaySlot = { d -> shell.go("event/0?start=${Time.millis(d)}&allDay=true") },
                    onItem = { detail = it })
            }
        }
    }

    detail?.let { item -> ItemDetailSheet(item, onDismiss = { detail = null }) }
    when (dialog) {
        "calendars" -> CalendarsDialog { dialog = null }
        "find" -> FindTimeDialog(onDismiss = { dialog = null })
    }
}

@Composable
fun WeekdayHeader(weekStart: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        repeat(7) { i ->
            val d = DayOfWeek.of(((weekStart - 1 + i) % 7) + 1)
            Text(Time.dayShort(d).uppercase(), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                color = if (d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
private fun MonthGrid(month: YearMonth, weekStart: Int, selected: LocalDate, today: LocalDate, byDay: Map<LocalDate, List<CalItem>>, onSelect: (LocalDate) -> Unit) {
    val first = Time.weekStart(month.atDay(1), weekStart)
    Column(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
        repeat(6) { w ->
            Row(Modifier.fillMaxWidth().height(60.dp)) {
                repeat(7) { dIdx ->
                    val day = first.plusDays((w * 7 + dIdx).toLong())
                    val inMonth = YearMonth.from(day) == month
                    val isSel = day == selected
                    val dayItems = byDay[day].orEmpty()
                    Column(
                        Modifier.weight(1f).fillMaxHeight().padding(1.dp).clip(RoundedCornerShape(8.dp))
                            .background(if (isSel) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                            .clickable { onSelect(day) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.padding(top = 3.dp).size(22.dp).clip(CircleShape)
                                .background(if (day == today) MaterialTheme.colorScheme.primary else Color.Transparent),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${day.dayOfMonth}", fontSize = 12.sp, fontWeight = if (day == today || isSel) FontWeight.Bold else FontWeight.Normal,
                                color = when {
                                    day == today -> MaterialTheme.colorScheme.onPrimary
                                    !inMonth -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                        dayItems.take(2).forEach { it ->
                            Text(
                                it.title, maxLines = 1, overflow = TextOverflow.Clip, fontSize = 9.sp, lineHeight = 11.sp,
                                color = if (inMonth) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 0.5.dp).clip(RoundedCornerShape(3.dp))
                                    .background(it.color.copy(alpha = if (inMonth) 0.35f else 0.15f)).padding(horizontal = 2.dp),
                            )
                        }
                        if (dayItems.size > 2) Text("+${dayItems.size - 2}", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun DayList(day: LocalDate, items: List<CalItem>, modifier: Modifier, onItem: (CalItem) -> Unit) {
    val shell = LocalShell.current
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp)) {
        item {
            Text(Time.relativeDay(day).let { if (it.length < 10) "$it · ${Time.date(day)}" else it }, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp))
        }
        if (items.isEmpty()) item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Nothing scheduled.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = { shell.go("event/0?start=${Time.millis(day, 9 * 60)}") }) { Text("Add event") }
            }
        }
        items(items, key = { it.key }) { ItemRow(it, day) { onItem(it) } }
    }
}

@Composable
fun ItemRow(it: CalItem, day: LocalDate?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).height(38.dp).clip(RoundedCornerShape(2.dp)).background(it.color))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.width(64.dp)) {
            if (it.allDay) Text("All day", style = MaterialTheme.typography.labelMedium)
            else {
                val startsToday = day == null || it.start.toLocalDate() == day
                Text(if (startsToday) Time.time(it.start) else "…", style = MaterialTheme.typography.labelMedium)
                if (it.kind != CalKind.TASK) Text(Time.time(it.end), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(it.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOf(it.subtitle, it.location).filter { s -> s.isNotBlank() }.joinToString(" · ")
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when (it.kind) {
            CalKind.DEVICE -> Icon(Icons.Default.Sync, "Device calendar", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            CalKind.CLASS -> Icon(Icons.Default.School, null, Modifier.size(16.dp), tint = it.color)
            else -> if (it.event?.repeatRule != null) Icon(Icons.Default.Repeat, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AgendaList(today: LocalDate, byDay: Map<LocalDate, List<CalItem>>, onItem: (CalItem) -> Unit) {
    val days = remember(byDay) { (byDay.keys + today).filter { it >= today.minusDays(14) }.sorted() }
    val state = rememberLazyListState()
    LaunchedEffect(days.size) {
        var idx = 0
        for (d in days) { if (d >= today) break; idx += 1 + byDay[d].orEmpty().size }
        state.scrollToItem(idx)
    }
    if (days.size <= 1 && byDay[today].isNullOrEmpty()) {
        EmptyState(Icons.Default.EventAvailable, "Your schedule is clear", "Events, classes, deadlines and dated tasks show up here.")
        return
    }
    LazyColumn(state = state, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp)) {
        days.forEach { d ->
            stickyHeader(key = "h$d") {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(if (d == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                        Text("${d.dayOfMonth}", fontWeight = FontWeight.Bold, color = if (d == today) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(Time.relativeDay(d) + if (Time.relativeDay(d).length < 10) " · " + Time.date(d) else "", style = MaterialTheme.typography.titleSmall,
                        color = if (d < today) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                }
            }
            val list = byDay[d].orEmpty()
            if (list.isEmpty()) item(key = "empty$d") { Text("Nothing scheduled", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 52.dp, bottom = 8.dp)) }
            items(list, key = { "$d${it.key}" }) { ItemRow(it, d) { onItem(it) } }
        }
    }
}

// ------------------------------------------------------------------ time grid

private data class Placed(val item: CalItem, val startMin: Int, val endMin: Int, var col: Int = 0, var cols: Int = 1)

private fun layoutDay(items: List<CalItem>, day: LocalDate): List<Placed> {
    val evs = items.filter { !it.allDay }.map {
        val s = if (it.start.toLocalDate() < day) 0 else Time.minuteOfDay(it.start)
        var e = if (it.end.toLocalDate() > day) 1440 else Time.minuteOfDay(it.end)
        if (e < s + 20) e = s + 20
        Placed(it, s, e.coerceAtMost(1440))
    }.sortedWith(compareBy<Placed>({ it.startMin }, { -(it.endMin - it.startMin) }))
    val out = ArrayList<Placed>()
    val cluster = ArrayList<Placed>()
    val colEnds = ArrayList<Int>()
    var clusterEnd = -1
    fun finish() { cluster.forEach { it.cols = colEnds.size }; out += cluster; cluster.clear(); colEnds.clear() }
    for (p in evs) {
        if (cluster.isNotEmpty() && p.startMin >= clusterEnd) finish()
        var col = colEnds.indexOfFirst { it <= p.startMin }
        if (col < 0) { col = colEnds.size; colEnds.add(p.endMin) } else colEnds[col] = p.endMin
        p.col = col
        cluster += p
        clusterEnd = max(clusterEnd, p.endMin)
    }
    finish()
    return out
}

@Composable
private fun TimeGridView(
    view: CalView,
    pager: androidx.compose.foundation.pager.PagerState,
    pageStart: (Int) -> LocalDate,
    byDay: Map<LocalDate, List<CalItem>>,
    today: LocalDate,
    workStart: Int,
    onSlot: (LocalDateTime) -> Unit,
    onAllDaySlot: (LocalDate) -> Unit,
    onItem: (CalItem) -> Unit,
) {
    val n = view.days
    val days = (0 until n).map { pageStart(pager.currentPage).plusDays(it.toLong()) }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    LaunchedEffect(Unit) {
        val target = ((if (days.contains(today)) (LocalTime.now().hour - 2).coerceAtLeast(0) * 60 else workStart - 60).coerceAtLeast(0))
        scroll.scrollTo(with(density) { (HourHeight * (target / 60f)).toPx().roundToInt() })
    }
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = LocalTime.now() } }

    Column(Modifier.fillMaxSize()) {
        // day headers
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Spacer(Modifier.width(48.dp))
            days.forEach { d ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(Time.dayShort(d.dayOfWeek).uppercase(), style = MaterialTheme.typography.labelSmall, color = if (d == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.size(30.dp).clip(CircleShape).background(if (d == today) MaterialTheme.colorScheme.primary else Color.Transparent), contentAlignment = Alignment.Center) {
                        Text("${d.dayOfMonth}", fontWeight = FontWeight.Bold, color = if (d == today) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        // all-day row
        val anyAllDay = days.any { d -> byDay[d].orEmpty().any { it.allDay } }
        Row(Modifier.fillMaxWidth().heightIn(min = if (anyAllDay) 24.dp else 8.dp)) {
            Text(if (anyAllDay) "all-day" else "", Modifier.width(48.dp).padding(top = 4.dp), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            days.forEach { d ->
                Column(Modifier.weight(1f).padding(horizontal = 1.dp).clickable { onAllDaySlot(d) }) {
                    byDay[d].orEmpty().filter { it.allDay }.take(3).forEach { it ->
                        Text(it.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp).clip(RoundedCornerShape(4.dp)).background(it.color.copy(alpha = 0.4f))
                                .clickable { onItem(it) }.padding(horizontal = 3.dp, vertical = 1.dp))
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Row(Modifier.fillMaxWidth().height(HourHeight * 24)) {
                Column(Modifier.width(48.dp)) {
                    repeat(24) { h ->
                        Box(Modifier.height(HourHeight).fillMaxWidth()) {
                            if (h > 0) Text(Time.time(h * 60).replace(":00", ""), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.TopCenter).offset(y = (-7).dp))
                        }
                    }
                }
                HorizontalPager(pager, Modifier.weight(1f).fillMaxHeight(), beyondViewportPageCount = 0) { page ->
                    val pageDays = (0 until n).map { pageStart(page).plusDays(it.toLong()) }
                    Row(Modifier.fillMaxSize()) {
                        pageDays.forEach { d -> DayColumn(d, byDay[d].orEmpty(), d == today, now, Modifier.weight(1f), onSlot, onItem) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayColumn(day: LocalDate, items: List<CalItem>, isToday: Boolean, now: LocalTime, modifier: Modifier, onSlot: (LocalDateTime) -> Unit, onItem: (CalItem) -> Unit) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val nowColor = LocalBlColors.current.danger
    val density = LocalDensity.current
    val placed = remember(items, day) { layoutDay(items, day) }
    BoxWithConstraints(
        modifier.fillMaxHeight()
            .pointerInput(day) {
                detectTapGestures { off ->
                    val hourPx = with(density) { HourHeight.toPx() }
                    val minute = ((off.y / hourPx) * 60).toInt() / 30 * 30
                    onSlot(day.atStartOfDay().plusMinutes(minute.toLong().coerceIn(0, 23 * 60 + 30)))
                }
            },
    ) {
        val colW = maxWidth
        Canvas(Modifier.fillMaxSize()) {
            val hourPx = HourHeight.toPx()
            for (h in 0..24) drawLine(lineColor, Offset(0f, h * hourPx), Offset(size.width, h * hourPx), 1f)
            drawLine(lineColor, Offset(0f, 0f), Offset(0f, size.height), 1f)
        }
        placed.forEach { p ->
            val top: Dp = HourHeight * (p.startMin / 60f)
            val height: Dp = (HourHeight * ((p.endMin - p.startMin) / 60f)).coerceAtLeast(18.dp)
            val w = colW / p.cols
            Column(
                Modifier.offset(x = w * p.col, y = top).width(w).height(height).padding(1.dp).clip(RoundedCornerShape(6.dp))
                    .background(p.item.color.copy(alpha = if (p.item.kind == CalKind.TASK) 0.25f else 0.85f))
                    .border(if (p.item.kind == CalKind.TASK) 1.dp else 0.dp, p.item.color, RoundedCornerShape(6.dp))
                    .clickable { onItem(p.item) }.padding(horizontal = 3.dp, vertical = 2.dp),
            ) {
                val onColor = if (p.item.kind == CalKind.TASK) MaterialTheme.colorScheme.onSurface else Color(0xFF0B0D10)
                Text(p.item.title, fontSize = 10.sp, lineHeight = 11.sp, fontWeight = FontWeight.SemiBold, color = onColor, maxLines = if (height > 40.dp) 2 else 1, overflow = TextOverflow.Ellipsis)
                if (height > 34.dp) Text(Time.time(p.item.start), fontSize = 9.sp, lineHeight = 10.sp, color = onColor.copy(alpha = 0.8f), maxLines = 1)
                if (height > 56.dp && p.item.location.isNotBlank()) Text(p.item.location, fontSize = 9.sp, lineHeight = 10.sp, color = onColor.copy(alpha = 0.8f), maxLines = 1)
            }
        }
        if (isToday) {
            val y = HourHeight * ((now.hour * 60 + now.minute) / 60f)
            Box(Modifier.offset(y = y - 1.dp).fillMaxWidth().height(2.dp).background(nowColor))
            Box(Modifier.offset(x = (-4).dp, y = y - 5.dp).size(10.dp).clip(CircleShape).background(nowColor))
        }
    }
}

// ------------------------------------------------------------------ detail sheet

@Composable
private fun ItemDetailSheet(item: CalItem, onDismiss: () -> Unit) {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    LaunchedEffect(item) {
        when (item.kind) {
            CalKind.TASK -> { onDismiss(); shell.go("task/${item.refId}") }
            CalKind.CLASS, CalKind.ASSIGNMENT, CalKind.EXAM -> { onDismiss(); shell.go("course/${item.refId}") }
            CalKind.DEVICE -> { onDismiss(); DeviceCalendar.openEvent(ctx, item.refId) }
            CalKind.EVENT -> Unit
        }
    }
    val e = item.event ?: return
    var confirmDelete by remember { mutableStateOf(false) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 8.dp).size(14.dp).clip(RoundedCornerShape(4.dp)).background(item.color))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.title.ifBlank { "(No title)" }, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        if (item.allDay) Time.date(item.start.toLocalDate()) + (if (item.end.toLocalDate().minusDays(1) > item.start.toLocalDate()) " – " + Time.date(item.end.toLocalDate().minusDays(1)) else "") + " · All day"
                        else if (item.start.toLocalDate() == item.end.toLocalDate()) "${Time.date(item.start.toLocalDate())} · ${Time.time(item.start)} – ${Time.time(item.end)}"
                        else "${Time.date(item.start.toLocalDate())} ${Time.time(item.start)} – ${Time.date(item.end.toLocalDate())} ${Time.time(item.end)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    e.repeatRule?.let { Text(Recurrence.parse(it)?.describe(Time.toDate(e.start)) ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (item.subtitle.isNotBlank()) DetailLine(Icons.Default.Layers, item.subtitle + " · " + app.settings.profileName(Profile.from(e.profile)))
            if (e.location.isNotBlank()) DetailLine(Icons.Default.LocationOn, e.location) { Files.openMap(ctx, e.location) }
            if (e.url.isNotBlank()) DetailLine(Icons.Default.VideoCall, e.url) { Files.openUrl(ctx, e.url) }
            if (e.attendees.isNotBlank()) DetailLine(Icons.Default.Group, e.attendees) { Files.email(ctx, e.attendees, e.title) }
            if (e.reminders.isNotBlank()) DetailLine(Icons.Default.Notifications, e.reminders.split(',').mapNotNull { it.trim().toIntOrNull() }.joinToString(", ") { Time.minutesLabel(it) + if (it > 0) " before" else "" })
            if (!e.busy) DetailLine(Icons.Default.EventAvailable, "Shown as free")
            if (e.notes.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(e.notes, style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onDismiss(); shell.go("event/${e.id}") }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Edit, null); Spacer(Modifier.width(6.dp)); Text("Edit") }
                FilledTonalButton(onClick = { confirmDelete = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Delete, null); Spacer(Modifier.width(6.dp)); Text("Delete") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = {
                    app.io {
                        val copy = e.copy(id = 0, createdAt = Time.now(), updatedAt = Time.now(), title = e.title + " (copy)")
                        val id = app.db.events().insert(copy)
                        app.requestReminderSync()
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onDismiss(); shell.go("event/$id") }
                    }
                }, modifier = Modifier.weight(1f)) { Text("Duplicate") }
                OutlinedButton(onClick = {
                    app.io {
                        val f = Files.unique(app.storage.dir(Profile.from(e.profile), Folder.EXPORTS), e.title.ifBlank { "Event" }, "ics")
                        f.writeText(Ics.export(listOf(e)))
                        Files.share(ctx, listOf(f), e.title)
                    }
                }, modifier = Modifier.weight(1f)) { Text("Share") }
                OutlinedButton(onClick = { runCatching { ctx.startActivity(DeviceCalendar.insertIntent(e)) } }, modifier = Modifier.weight(1f)) { Text("To Google") }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete event?") },
            text = { Text(if (e.repeatRule != null) "This is a repeating event." else "“${e.title}” will be deleted.") },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    if (e.repeatRule != null) TextButton(onClick = {
                        val ex = parseExDates(e.exDates) + item.start.toLocalDate()
                        app.io { app.db.events().update(e.copy(exDates = ex.toExDateString())); app.requestReminderSync() }
                        confirmDelete = false; onDismiss()
                    }) { Text("This event only") }
                    TextButton(onClick = {
                        app.io { app.db.events().delete(e); app.requestReminderSync() }
                        confirmDelete = false; onDismiss()
                    }) { Text(if (e.repeatRule != null) "All events in series" else "Delete", color = LocalBlColors.current.danger) }
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DetailLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}

// ------------------------------------------------------------------ dialogs

@Composable
private fun CalendarsDialog(onDismiss: () -> Unit) {
    val app = rememberApp()
    val ctx = LocalContext.current
    val s = settingsState()
    val calendars by remember(s.activeProfile) { app.db.events().observeCalendars(listOf(s.activeProfile.name)) }.collectAsState(emptyList())
    var hasPerm by remember { mutableStateOf(DeviceCalendar.hasPermission(ctx)) }
    val deviceCals = remember(hasPerm) { DeviceCalendar.calendars(ctx) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        hasPerm = ok
        if (ok) app.settings.update { putBoolean(Keys.SHOW_DEVICE_CALS, true) }
    }
    var edit by remember { mutableStateOf<EventCalendarEntity?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Calendars & sources") },
        text = {
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                item { SectionHeader("BlackLab calendars", action = "New") { edit = EventCalendarEntity(profile = s.activeProfile.name, name = "", color = Palette[calendars.size % Palette.size], sortOrder = calendars.size) } }
                items(calendars, key = { it.id }) { c ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { edit = c }.padding(vertical = 2.dp)) {
                        ColorDot(Color(c.color), 14.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(c.name, Modifier.weight(1f))
                        Switch(c.visible, { v -> app.io { app.db.events().updateCalendar(c.copy(visible = v)) } })
                    }
                }
                item { SectionHeader("Also show") }
                item { LabeledSwitchRow("Dated tasks", checked = s.showTasksInCalendar) { v -> app.settings.update { putBoolean(Keys.SHOW_TASKS_IN_CAL, v) } } }
                item { LabeledSwitchRow("Classes, assignments & exams", checked = s.showClassesInCalendar) { v -> app.settings.update { putBoolean(Keys.SHOW_CLASSES_IN_CAL, v) } } }
                item {
                    LabeledSwitchRow("Both accounts", "Overlay ${app.settings.profileName(s.activeProfile.other)} events", s.showOtherProfileInCalendar) { v ->
                        app.settings.update { putBoolean(Keys.SHOW_OTHER_PROFILE_CAL, v) }
                    }
                }
                item { SectionHeader("Phone calendars (Google, Outlook…)") }
                if (!hasPerm) item {
                    FilledTonalButton(onClick = { perm.launch(Manifest.permission.READ_CALENDAR) }) { Text("Connect phone calendars") }
                } else {
                    item { LabeledSwitchRow("Show phone calendars", checked = s.showDeviceCalendars) { v -> app.settings.update { putBoolean(Keys.SHOW_DEVICE_CALS, v) } } }
                    if (s.showDeviceCalendars) items(deviceCals, key = { "d${it.id}" }) { c ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            ColorDot(Color(c.color), 12.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.name, maxLines = 1)
                                Text(c.account, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            val shown = c.id.toString() !in s.hiddenDeviceCalendars
                            Switch(shown, { v ->
                                val set = s.hiddenDeviceCalendars.toMutableSet().apply { if (v) remove(c.id.toString()) else add(c.id.toString()) }
                                app.settings.update { putStringSet(Keys.HIDDEN_DEVICE_CALS, set) }
                            })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
    edit?.let { c ->
        var name by remember(c) { mutableStateOf(c.name) }
        var color by remember(c) { mutableStateOf(c.color) }
        AlertDialog(
            onDismissRequest = { edit = null },
            title = { Text(if (c.id == 0L) "New calendar" else "Edit calendar") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.material3.OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    ColorPickerRow(color, { color = it })
                    if (c.id != 0L) TextButton(onClick = {
                        app.io { app.db.events().detachCalendar(c.id); app.db.events().deleteCalendar(c) }; edit = null
                    }) { Text("Delete calendar", color = LocalBlColors.current.danger) }
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    val n = c.copy(name = name.trim(), color = color)
                    app.io { if (n.id == 0L) app.db.events().insertCalendar(n) else app.db.events().updateCalendar(n) }
                    edit = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { edit = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FindTimeDialog(onDismiss: () -> Unit) {
    val shell = LocalShell.current
    val s = settingsState()
    var minutes by remember { mutableStateOf(60) }
    var days by remember { mutableStateOf(7) }
    var weekends by remember { mutableStateOf(false) }
    val today = Time.today()
    val items = rememberCalendarItems(today, today.plusDays(days.toLong()))
    val slots = remember(items, minutes, days, weekends, s.workStartMinute, s.workEndMinute) {
        val busy = items.filter { it.busy && !it.allDay }.map { it.start to it.end }
        CalendarLogic.freeSlots(busy, today, today.plusDays(days.toLong() - 1), s.workStartMinute, s.workEndMinute, minutes, !weekends)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Find a time") },
        text = {
            Column {
                Text("Free blocks within working hours (${Time.time(s.workStartMinute)}–${Time.time(s.workEndMinute)})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    listOf(30, 60, 90, 120).forEach { m -> FilterChip(minutes == m, { minutes = m }, label = { Text(Time.hoursLabel(m)) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(3, 7, 14).forEach { d -> FilterChip(days == d, { days = d }, label = { Text("$d days") }) }
                    FilterChip(weekends, { weekends = !weekends }, label = { Text("Weekends") })
                }
                LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 8.dp)) {
                    if (slots.isEmpty()) item { Text("No free blocks found. Try a shorter duration or longer range.", modifier = Modifier.padding(8.dp)) }
                    items(slots.take(40)) { (a, b) ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                                onDismiss(); shell.go("event/0?start=${Time.millis(a)}")
                            }.padding(vertical = 10.dp, horizontal = 6.dp),
                        ) {
                            Text(Time.relativeDay(a.toLocalDate()), Modifier.width(96.dp), fontWeight = FontWeight.SemiBold)
                            Text("${Time.time(a)} – ${Time.time(b)}", Modifier.weight(1f))
                            Text(Time.hoursLabel(Duration.between(a, b).toMinutes().toInt()), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Suppress("unused")
private fun daysBetween(a: LocalDate, b: LocalDate) = ChronoUnit.DAYS.between(a, b)

package com.blacklab.app.ui.knowledge

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.blacklab.app.assistant.Nano
import com.blacklab.app.assistant.VoiceInput
import com.blacklab.app.knowledge.Conversations
import com.blacklab.app.knowledge.Knowledge
import com.blacklab.app.ui.components.BlCard
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.ChipFlow
import com.blacklab.app.ui.components.EmptyState
import com.blacklab.app.ui.components.IconBadge
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.Pill
import com.blacklab.app.ui.components.SectionHeader
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.components.settingsState
import com.blacklab.app.ui.theme.Amber
import com.blacklab.app.ui.theme.Cobalt
import com.blacklab.app.ui.theme.Coral
import com.blacklab.app.ui.theme.Mint
import com.blacklab.app.ui.theme.Violet
import kotlinx.coroutines.launch

/** Accent + icon per knowledge base (by id), shared by every knowledge screen. */
internal fun kbStyle(id: String): Pair<ImageVector, Color> = when (id) {
    "software-engineering" -> Icons.Rounded.Architecture to Cobalt
    "software-development" -> Icons.Rounded.Code to Mint
    "ai-engineering" -> Icons.Rounded.Memory to Violet
    "ai-development" -> Icons.Rounded.AutoAwesome to Color(0xFFE57CFF)
    "github" -> Icons.Rounded.Hub to Color(0xFF9AA4B2)
    "conv-architecture" -> Icons.Rounded.AccountTree to Cobalt
    "conv-code-craft" -> Icons.Rounded.RateReview to Mint
    "conv-ai-builder" -> Icons.Rounded.Psychology to Violet
    "conv-ship-it" -> Icons.Rounded.RocketLaunch to Coral
    "conv-health" -> Icons.Rounded.FitnessCenter to Color(0xFF4CD787)
    "peer-support" -> Icons.Rounded.Diversity3 to Violet
    "mht-practice" -> Icons.Rounded.HealthAndSafety to Coral
    "conv-peer-lab" -> Icons.Rounded.Diversity3 to Color(0xFFB39DDB)
    "conv-money-home" -> Icons.Rounded.Savings to Amber
    "conv-downtime" -> Icons.Rounded.SportsFootball to Color(0xFF5BC0EB)
    else -> Icons.AutoMirrored.Rounded.MenuBook to Mint
}

private val techIds = setOf("software-engineering", "software-development", "ai-engineering", "ai-development", "github")
private val careIds = setOf("peer-support", "mht-practice")

// ------------------------------------------------------------------ hub

@Composable
fun KnowledgeScreen(startTab: String = "") {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val bases = remember { Knowledge.bases(ctx) }
    var tab by rememberSaveable { mutableIntStateOf(if (startTab.equals("library", true)) 1 else 0) }
    var query by rememberSaveable { mutableStateOf("") }
    val nano by Nano.status.collectAsStateWithLifecycle()
    val convs = bases.filter { it.kind == Knowledge.Kind.CONVERSATION }
    val domains = bases.filter { it.kind == Knowledge.Kind.DOMAIN }
    val results = remember(query) { if (query.isBlank()) emptyList() else Knowledge.search(ctx, query, 25) }

    Scaffold(topBar = {
        BlTopBar("Knowledge", subtitle = "${domains.size} expert bases · ${convs.size} conversations · ${bases.sumOf { it.entries.size }} entries · offline")
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Talk") }, icon = { Icon(Icons.Rounded.Forum, null) })
                Tab(tab == 1, { tab = 1 }, text = { Text("Library") }, icon = { Icon(Icons.AutoMirrored.Rounded.MenuBook, null) })
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (tab == 0) {
                    item {
                        Text(if (nano == Nano.Status.AVAILABLE) "Conversations run on Gemini Nano, grounded in BlackLab's knowledge bases — all on the phone."
                        else "Conversations answer from BlackLab's knowledge bases on the phone. With Gemini Nano they become free-flowing.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val tech = convs.filter { c -> c.draws.any { it in techIds } }
                    val care = convs.filter { c -> c.draws.any { it in careIds } }
                    item { SectionHeader("Tech talk") }
                    items(tech, key = { it.id }) { c -> ConversationCard(c, app.conversations) { shell.go("talk/${c.id}") } }
                    if (care.isNotEmpty()) {
                        item { SectionHeader("Behavioral health") }
                        items(care, key = { it.id }) { c -> ConversationCard(c, app.conversations) { shell.go("talk/${c.id}") } }
                    }
                    item { SectionHeader("Everyday") }
                    items(convs - tech.toSet() - care.toSet(), key = { it.id }) { c -> ConversationCard(c, app.conversations) { shell.go("talk/${c.id}") } }
                } else {
                    item {
                        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search every knowledge base") },
                            leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = RoundedCornerShape(28.dp),
                            trailingIcon = { if (query.isNotBlank()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear") } })
                    }
                    if (query.isBlank()) {
                        item { SectionHeader("Expert knowledge bases") }
                        items(domains, key = { it.id }) { b -> BaseCard(b) { shell.go("kb/${b.id}") } }
                        item { SectionHeader("Conversation notes") }
                        items(convs, key = { "n" + it.id }) { b -> BaseCard(b) { shell.go("kb/${b.id}") } }
                    } else if (results.isEmpty()) {
                        item { EmptyState(Icons.Rounded.SearchOff, "No matches", "Try a broader term, or ask the assistant — it can reason beyond these notes with Gemini Nano.") }
                    } else {
                        items(results, key = { it.entry.id }) { h -> EntryRow(h.entry, showBase = true) { shell.go("kbentry?id=" + Uri.encode(h.entry.id)) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationCard(b: Knowledge.Base, conversations: Conversations, onClick: () -> Unit) {
    val (icon, color) = kbStyle(b.id)
    val turns = remember(b.id) { conversations.history(b.id).count { it.user } }
    BlCard(Modifier.fillMaxWidth(), onClick = onClick, accent = color) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, color)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(b.title, style = MaterialTheme.typography.titleMedium)
                Text(b.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (turns > 0) Pill("$turns", color)
        }
        if (b.starters.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("“${b.starters.first()}”", style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun BaseCard(b: Knowledge.Base, onClick: () -> Unit) {
    val (icon, color) = kbStyle(b.id)
    BlCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, color)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(b.title, style = MaterialTheme.typography.titleMedium)
                Text(b.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text("${b.entries.size}", style = MaterialTheme.typography.titleSmall, color = color)
        }
    }
}

@Composable
private fun EntryRow(e: Knowledge.Entry, showBase: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val (_, color) = kbStyle(e.kbId)
    BlCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Text(e.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (showBase) Text(Knowledge.base(ctx, e.kbId)?.title.orEmpty(), style = MaterialTheme.typography.labelSmall, color = color)
        Text(e.lead, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp))
    }
}

// ------------------------------------------------------------------ one base

@Composable
fun KbScreen(id: String) {
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val base = remember(id) { Knowledge.base(ctx, id) }
    var query by rememberSaveable { mutableStateOf("") }
    if (base == null) { EmptyState(Icons.AutoMirrored.Rounded.MenuBook, "Not found", "That knowledge base isn't in this version."); return }
    val (icon, color) = kbStyle(base.id)
    val shown = remember(query) {
        if (query.isBlank()) base.entries else Knowledge.search(ctx, query, 40, setOf(base.id)).map { it.entry }
    }
    val talkTo = remember(id) { Knowledge.conversations(ctx).firstOrNull { it.id == id || id in it.draws } }
    Scaffold(topBar = {
        BlTopBar(base.title, subtitle = "${base.entries.size} entries", onBack = { shell.back() }) {
            if (talkTo != null) IconButton(onClick = { shell.go("talk/${talkTo.id}") }) { Icon(Icons.Rounded.Forum, "Talk") }
        }
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(icon, color)
                    Text(base.summary, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search ${base.title}") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = RoundedCornerShape(28.dp))
            }
            items(shown, key = { it.id }) { e -> EntryRow(e, showBase = false) { shell.go("kbentry?id=" + Uri.encode(e.id)) } }
        }
    }
}

// ------------------------------------------------------------------ one entry

@Composable
fun KbEntryScreen(id: String) {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val e = remember(id) { Knowledge.entry(ctx, id) }
    val speaking by app.speaker.speaking.collectAsStateWithLifecycle()
    if (e == null) { EmptyState(Icons.AutoMirrored.Rounded.MenuBook, "Not found", "That entry isn't in this version."); return }
    val base = remember(id) { Knowledge.base(ctx, e.kbId) }
    val (_, color) = kbStyle(e.kbId)
    val talkTo = remember(id) { Knowledge.conversations(ctx).firstOrNull { it.id == e.kbId || e.kbId in it.draws } }
    val related = remember(id) { Knowledge.search(ctx, e.title + " " + e.tags.joinToString(" "), 5).map { it.entry }.filter { it.id != e.id }.take(4) }
    DisposableEffect(Unit) { onDispose { app.speaker.stop() } }
    Scaffold(topBar = {
        BlTopBar(base?.title ?: "Knowledge", onBack = { shell.back() }) {
            IconButton(onClick = { if (speaking) app.speaker.stop() else app.speaker.speak(e.title + ". " + e.body) }) {
                Icon(if (speaking) Icons.Rounded.StopCircle else Icons.AutoMirrored.Rounded.VolumeUp, "Read aloud")
            }
        }
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(e.title, style = MaterialTheme.typography.headlineSmall)
                if (e.tags.isNotEmpty()) ChipFlow(Modifier.padding(top = 8.dp)) { e.tags.forEach { Pill(it, color) } }
            }
            item { SelectionContainer { KbBody(e.body) } }
            if (talkTo != null) item {
                Text("Keep going", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                ChipFlow(Modifier.padding(top = 6.dp)) {
                    val chips = if (base?.kind == Knowledge.Kind.DOMAIN) e.ask + "Tell me more about ${e.title.lowercase()}" else listOf("Tell me more about ${e.title.lowercase()}")
                    chips.forEach { q ->
                        SuggestionChip(onClick = { shell.go("talk/${talkTo.id}?q=" + Uri.encode(q)) }, label = { Text(q) })
                    }
                }
            }
            if (related.isNotEmpty()) {
                item { SectionHeader("Related") }
                items(related, key = { "r" + it.id }) { r -> EntryRow(r, showBase = r.kbId != e.kbId) { shell.go("kbentry?id=" + Uri.encode(r.id)) } }
            }
        }
    }
}

/** Paragraphs, "- " bullets and "**Label:**" lead-ins from the knowledge Markdown. */
@Composable
internal fun KbBody(body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        body.split(Regex("\\n\\s*\\n")).forEach { para ->
            val lines = para.lines().filter { it.isNotBlank() }
            if (lines.all { it.trimStart().startsWith("- ") }) {
                lines.forEach { l ->
                    Row { Text("•  ", style = com.blacklab.app.ui.theme.readingStyle(MaterialTheme.typography.bodyLarge)); Text(l.trimStart().removePrefix("- ").replace("**", ""), style = com.blacklab.app.ui.theme.readingStyle(MaterialTheme.typography.bodyLarge)) }
                }
            } else Text(para.replace("**", "").replace(Regex("\\s*\\n\\s*"), " ").trim(), style = com.blacklab.app.ui.theme.readingStyle(MaterialTheme.typography.bodyLarge))
        }
    }
}

// ------------------------------------------------------------------ conversation

@Composable
fun TalkScreen(id: String, initial: String = "") {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val s = settingsState()
    val scope = rememberCoroutineScope()
    val base = remember(id) { Knowledge.base(ctx, id) }
    if (base == null) { EmptyState(Icons.Rounded.Forum, "Not found", "That conversation isn't in this version."); return }
    val (icon, color) = kbStyle(id)
    val conv = app.conversations
    val all by conv.turns.collectAsStateWithLifecycle()
    val turns = all[id] ?: remember(id) { conv.open(id) }
    val busy by conv.busy.collectAsStateWithLifecycle()
    val speaking by app.speaker.speaking.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") }
    var followUps by remember { mutableStateOf(base.starters.take(4)) }
    var speak by rememberSaveable { mutableStateOf(s.asstSpeak) }
    var menu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun send(text: String) {
        if (text.isBlank() || busy) return
        input = ""; partial = ""
        app.speaker.stop()
        scope.launch {
            val a = conv.ask(id, text)
            followUps = a.followUps
            if (speak) app.speaker.speak(a.text)
        }
    }
    val voice = remember {
        VoiceInput(ctx, onPartial = { partial = it }, onFinal = { listening = false; send(it) }, onError = { listening = false; partial = ""; shell.toast(it) })
    }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) { listening = true; voice.start() } }
    fun listen() {
        app.speaker.stop()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) micPerm.launch(Manifest.permission.RECORD_AUDIO)
        else { listening = true; partial = ""; voice.start() }
    }
    DisposableEffect(Unit) {
        val again: () -> Unit = { if (app.settings.current.asstAutoListen) ContextCompat.getMainExecutor(ctx).execute { listen() } }
        app.speaker.doneListeners += again
        onDispose { voice.stop(); app.speaker.doneListeners -= again; app.speaker.stop() }
    }
    LaunchedEffect(id) {
        conv.open(id)
        if (initial.isNotBlank()) send(initial)
    }
    LaunchedEffect(turns.size, busy) { if (turns.isNotEmpty()) listState.animateScrollToItem(turns.size - 1 + if (busy) 1 else 0) }

    Scaffold(topBar = {
        BlTopBar(base.title, subtitle = if (Nano.ready && s.asstUseNano) "${Nano.engineShort} · grounded in notes" else "Offline notes", onBack = { shell.back() }) {
            IconButton(onClick = { if (speaking) app.speaker.stop() else speak = !speak }) {
                Icon(if (speaking) Icons.Rounded.StopCircle else if (speak) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff, "Voice")
            }
            androidx.compose.foundation.layout.Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Browse the notes") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.MenuBook, null) }, onClick = { menu = false; shell.go("kb/$id") })
                    DropdownMenuItem(text = { Text("Start over") }, leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null) }, onClick = {
                        menu = false; conv.clear(id); conv.open(id); followUps = base.starters.take(4)
                    })
                }
            }
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                        IconBadge(icon, color, 34.dp)
                        Text(base.summary, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(turns.size) { i ->
                    val t = turns[i]
                    TalkBubble(t, color) { srcId -> shell.go("kbentry?id=" + Uri.encode(srcId)) }
                }
                if (busy) item { Text("Thinking…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(followUps) { q -> SuggestionChip(onClick = { send(q) }, label = { Text(q, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp)) }) }
            }
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = if (listening) partial.ifBlank { "Listening…" } else input,
                    onValueChange = { if (!listening) input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Say or type anything") },
                    shape = RoundedCornerShape(28.dp), maxLines = 4, readOnly = listening,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send(input) }),
                )
                Spacer(Modifier.size(8.dp))
                if (input.isNotBlank() && !listening) FilledIconButton(onClick = { send(input) }, modifier = Modifier.size(56.dp)) { Icon(Icons.AutoMirrored.Rounded.Send, "Send") }
                else FilledIconButton(onClick = { if (listening) { voice.stop(); listening = false } else listen() }, modifier = Modifier.size(56.dp),
                    colors = if (listening) IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error) else IconButtonDefaults.filledIconButtonColors()) {
                    Icon(if (listening) Icons.Rounded.Stop else Icons.Rounded.Mic, "Talk")
                }
            }
        }
    }
}

@Composable
private fun TalkBubble(t: Conversations.Turn, color: Color, onSource: (String) -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (t.user) Alignment.End else Alignment.Start) {
        Surface(
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = if (t.user) 20.dp else 6.dp, bottomEnd = if (t.user) 6.dp else 20.dp),
            color = if (t.user) color.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                SelectionContainer { Text(t.text, style = MaterialTheme.typography.bodyMedium) }
                if (!t.user && t.engine.isNotBlank()) Text(t.engine, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (!t.user && t.sources.isNotEmpty()) {
            LazyRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(t.sources.take(3)) { sid ->
                    val title = remember(sid) { Knowledge.entry(ctx, sid)?.title ?: sid }
                    AssistChip(onClick = { onSource(sid) }, label = { Text(title.take(30)) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.MenuBook, null, Modifier.size(16.dp)) })
                }
            }
        }
    }
}

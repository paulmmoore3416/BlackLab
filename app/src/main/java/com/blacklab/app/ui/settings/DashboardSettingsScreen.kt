package com.blacklab.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.StickyNote2
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.blacklab.app.core.HomeLayout
import com.blacklab.app.core.Keys
import com.blacklab.app.core.SettingsSchema
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.ConfirmDialog
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.SectionHeader
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.components.rememberHaptics
import com.blacklab.app.ui.components.settingsState

/** Settings › Home dashboard: which cards show on Home, in what order, and how the Notes card behaves. */
@Composable
fun DashboardSettingsScreen() {
    val app = rememberApp()
    val shell = LocalShell.current
    val s = settingsState()
    val haptics = rememberHaptics()
    var confirmReset by remember { mutableStateOf(false) }
    val order = s.homeOrder
    fun saveOrder(o: List<String>) = app.settings.update { putString(HomeLayout.ORDER_KEY, HomeLayout.serialize(o)) }

    Scaffold(topBar = { BlTopBar("Home dashboard", subtitle = "${s.homeCards.size} of ${order.size} cards shown", onBack = { shell.back() }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            item {
                Text("Turn cards on or off and move them up or down. Home updates as you change things here.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            }
            item { SectionHeader("Cards on Home", action = "Show all") { app.settings.update { HomeLayout.cards.forEach { c -> putBoolean(HomeLayout.showKey(c.id), true) } } } }
            itemsIndexed(order, key = { _, id -> id }) { i, id ->
                val card = HomeLayout.card(id) ?: return@itemsIndexed
                val on = id !in s.homeHidden
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).animateItem()) {
                    Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(onClick = { haptics.tick(); saveOrder(HomeLayout.move(order, id, -1)) }, enabled = i > 0, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.KeyboardArrowUp, "Move ${card.label} up") }
                            IconButton(onClick = { haptics.tick(); saveOrder(HomeLayout.move(order, id, 1)) }, enabled = i < order.lastIndex, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.KeyboardArrowDown, "Move ${card.label} down") }
                        }
                        Spacer(Modifier.width(8.dp))
                        Icon(cardIcon(id), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.alpha(if (on) 1f else 0.4f))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp).alpha(if (on) 1f else 0.55f)) {
                            Text(card.label, style = MaterialTheme.typography.titleSmall)
                            Text(card.help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(on, { v -> haptics.toggle(v); app.settings.update { putBoolean(HomeLayout.showKey(id), v) } })
                    }
                }
            }
            item {
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { confirmReset = true }) { Icon(Icons.Rounded.RestartAlt, null); Spacer(Modifier.width(6.dp)); Text("Reset to default") }
                    FilledTonalButton(onClick = { shell.nav.navigate("home") { popUpTo("home") { inclusive = true }; launchSingleTop = true } }) { Icon(Icons.Rounded.Dashboard, null); Spacer(Modifier.width(6.dp)); Text("See Home") }
                }
            }
            item { SectionHeader("Notes card") }
            listOf(Keys.NOTES_HOME_MODE, Keys.NOTES_HOME_COUNT, Keys.NOTES_HOME_BAR).forEach { k -> SettingsSchema.def(k)?.let { d -> item(key = k) { SchemaItem(d) } } }
            item { SectionHeader("Notes lists") }
            listOf(Keys.NOTES_NEW_BOTTOM, Keys.NOTES_CHECKED_BOTTOM).forEach { k -> SettingsSchema.def(k)?.let { d -> item(key = k) { SchemaItem(d) } } }
            item {
                FilledTonalButton(onClick = { shell.go("notes") }, modifier = Modifier.padding(top = 8.dp)) { Icon(Icons.AutoMirrored.Rounded.StickyNote2, null); Spacer(Modifier.width(6.dp)); Text("Open Notes") }
            }
        }
    }
    if (confirmReset) ConfirmDialog("Reset Home?", "Shows every card again in the original order.", "Reset", destructive = false, onDismiss = { confirmReset = false }) {
        app.settings.update { remove(HomeLayout.ORDER_KEY); HomeLayout.cards.forEach { c -> remove(HomeLayout.showKey(c.id)) } }
    }
}

private fun cardIcon(id: String) = when (id) {
    "ask" -> Icons.Rounded.AutoAwesome
    "tools" -> Icons.Rounded.Summarize
    "status" -> Icons.Rounded.FiberManualRecord
    "weather" -> Icons.Rounded.WbSunny
    "bh" -> Icons.Rounded.Diversity3
    "actions" -> Icons.Rounded.Apps
    "feed" -> Icons.Rounded.History
    "notes" -> Icons.AutoMirrored.Rounded.StickyNote2
    "today" -> Icons.Rounded.CalendarMonth
    "focus" -> Icons.Rounded.TaskAlt
    "learning" -> Icons.Rounded.School
    "documents" -> Icons.Rounded.Description
    "reflect" -> Icons.Rounded.AutoStories
    else -> Icons.Rounded.Widgets
}

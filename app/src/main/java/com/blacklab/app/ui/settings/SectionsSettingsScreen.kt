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
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.blacklab.app.core.NavLayout
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.ConfirmDialog
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.SectionHeader
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.components.rememberHaptics
import com.blacklab.app.ui.components.settingsState
import com.blacklab.app.ui.navIcon

/**
 * Settings › Sections: pick the bottom tabs (2–5), order the side menu, hide what you don't use and rename any
 * section. Nothing is ever listed twice — a tab is left out of the side menu.
 */
@Composable
fun SectionsSettingsScreen() {
    val app = rememberApp()
    val shell = LocalShell.current
    val s = settingsState()
    val haptics = rememberHaptics()
    val l = s.nav
    var renaming by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    fun save(n: NavLayout.Layout) = app.settings.update {
        putString(NavLayout.TABS_KEY, NavLayout.serialize(n.tabs))
        putString(NavLayout.ORDER_KEY, NavLayout.serialize(n.order))
        putString(NavLayout.HIDDEN_KEY, NavLayout.serialize(n.hidden.toList()))
        putString(NavLayout.NAMES_KEY, NavLayout.serializeNames(n.names))
    }
    fun say(msg: String) = shell.toast(msg)

    Scaffold(topBar = { BlTopBar("Sections", subtitle = "${l.tabs.size} tabs · ${l.menu.size} in the menu · ${l.hidden.size} hidden", onBack = { shell.back() }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            item {
                Text("Pin up to ${NavLayout.MAX_TABS} sections to the bottom bar. Everything else is in the side menu, once. Hide what you don't use, or tap the pencil to rename it.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            }
            item { SectionHeader("Bottom tabs") }
            itemsIndexed(l.tabs, key = { _, r -> "tab-$r" }) { i, r ->
                SectionRow(l, r, pinned = true, hidden = false,
                    canUp = i > 0, canDown = i < l.tabs.lastIndex,
                    onUp = { haptics.tick(); save(l.copy(tabs = NavLayout.move(l.tabs, r, -1))) },
                    onDown = { haptics.tick(); save(l.copy(tabs = NavLayout.move(l.tabs, r, 1))) },
                    onPin = { NavLayout.toggleTab(l.tabs, r)?.let { save(l.copy(tabs = it)) } ?: say("Keep at least ${NavLayout.MIN_TABS} tabs.") },
                    onHide = { NavLayout.toggleHidden(l, r)?.let { save(it) } ?: say(if (r == "home") "Home can't be hidden." else "Keep at least ${NavLayout.MIN_TABS} tabs.") },
                    onRename = { renaming = r })
            }
            item { SectionHeader("Side menu") }
            val menu = l.menu
            itemsIndexed(menu, key = { _, r -> "menu-$r" }) { i, r ->
                SectionRow(l, r, pinned = false, hidden = false,
                    canUp = i > 0, canDown = i < menu.lastIndex,
                    onUp = { haptics.tick(); save(l.copy(order = NavLayout.move(l.order, r, -(l.order.indexOf(r) - l.order.indexOf(menu[i - 1]))))) },
                    onDown = { haptics.tick(); save(l.copy(order = NavLayout.move(l.order, r, l.order.indexOf(menu[i + 1]) - l.order.indexOf(r)))) },
                    onPin = { NavLayout.toggleTab(l.tabs, r)?.let { save(l.copy(tabs = it)) } ?: say("The bottom bar holds ${NavLayout.MAX_TABS} tabs. Unpin one first.") },
                    onHide = { NavLayout.toggleHidden(l, r)?.let { save(it) } ?: say("Home can't be hidden.") },
                    onRename = { renaming = r })
            }
            if (l.hidden.isNotEmpty()) {
                item { SectionHeader("Hidden") }
                itemsIndexed(l.order.filter { it in l.hidden }, key = { _, r -> "hidden-$r" }) { _, r ->
                    SectionRow(l, r, pinned = false, hidden = true, canUp = false, canDown = false, onUp = {}, onDown = {},
                        onPin = {}, onHide = { NavLayout.toggleHidden(l, r)?.let { save(it) } }, onRename = { renaming = r })
                }
            }
            item {
                OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.padding(top = 12.dp)) {
                    Icon(Icons.Rounded.RestartAlt, null); Spacer(Modifier.width(6.dp)); Text("Reset sections")
                }
            }
        }
    }

    renaming?.let { r ->
        var text by remember(r) { mutableStateOf(l.label(r)) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename ${NavLayout.section(r)?.label ?: r}") },
            text = { OutlinedTextField(text, { text = it.take(32) }, singleLine = true, label = { Text("Name") }) },
            confirmButton = { TextButton(onClick = {
                val name = text.trim().takeIf { it.isNotEmpty() && it != NavLayout.section(r)?.label }
                save(l.copy(names = if (name == null) l.names - r else l.names + (r to name))); renaming = null
            }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { save(l.copy(names = l.names - r)); renaming = null }) { Text("Use default") } },
        )
    }
    if (confirmReset) ConfirmDialog("Reset sections?", "Restores the default tabs, menu order and names, and shows hidden sections.", "Reset", destructive = false,
        onDismiss = { confirmReset = false }) {
        app.settings.update { listOf(NavLayout.TABS_KEY, NavLayout.ORDER_KEY, NavLayout.HIDDEN_KEY, NavLayout.NAMES_KEY).forEach { remove(it) } }
    }
}

@Composable
private fun SectionRow(
    l: NavLayout.Layout, route: String, pinned: Boolean, hidden: Boolean, canUp: Boolean, canDown: Boolean,
    onUp: () -> Unit, onDown: () -> Unit, onPin: () -> Unit, onHide: () -> Unit, onRename: () -> Unit,
) {
    val sec = NavLayout.section(route) ?: return
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!hidden) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = onUp, enabled = canUp, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.KeyboardArrowUp, "Move ${l.label(route)} up") }
                IconButton(onClick = onDown, enabled = canDown, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.KeyboardArrowDown, "Move ${l.label(route)} down") }
            } else Spacer(Modifier.width(34.dp))
            Spacer(Modifier.width(6.dp))
            Icon(navIcon(route, pinned), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.alpha(if (hidden) 0.4f else 1f))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp).alpha(if (hidden) 0.55f else 1f)) {
                Text(l.label(route), style = MaterialTheme.typography.titleSmall)
                Text(if (l.names.containsKey(route)) "${sec.label} · ${sec.help}" else sec.help, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                IconButton(onClick = onRename) { Icon(Icons.Rounded.Edit, "Rename ${l.label(route)}") }
                if (!hidden) IconButton(onClick = onPin) {
                    Icon(if (pinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin, if (pinned) "Unpin from bottom bar" else "Pin to bottom bar",
                        tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (route != "home") IconButton(onClick = onHide) {
                    Icon(if (hidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (hidden) "Show ${l.label(route)}" else "Hide ${l.label(route)}")
                }
            }
        }
    }
}

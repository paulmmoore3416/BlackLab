package com.blacklab.app.ui.bh

import com.blacklab.app.ui.theme.readable
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.blacklab.app.bh.Bh
import com.blacklab.app.bh.BhStore
import com.blacklab.app.bh.Forms
import com.blacklab.app.bh.Library
import com.blacklab.app.bh.Specs
import com.blacklab.app.ui.components.BlCard
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.ConfirmDialog
import com.blacklab.app.ui.components.DropdownField
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.Pill
import com.blacklab.app.ui.components.SectionHeader
import com.blacklab.app.ui.theme.Amber
import com.blacklab.app.ui.theme.Cobalt
import com.blacklab.app.ui.theme.Danger
import com.blacklab.app.ui.theme.Mint
import com.blacklab.app.ui.theme.Success
import com.blacklab.app.ui.theme.Violet

@Composable
fun BhResourcesScreen() = BhGuarded {
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val s = bhState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var favOnly by rememberSaveable { mutableStateOf(false) }
    var edit by remember { mutableStateOf<Bh.Resource?>(null) }
    var del by remember { mutableStateOf<Bh.Resource?>(null) }
    val all = (s.resources + Library.RESOURCES)
    val shown = all.filter { r ->
        (!favOnly || r.id in s.favorites) && (category.isBlank() || r.category == category) &&
            (query.isBlank() || listOf(r.name, r.category, r.notes, r.eligibility, r.address).any { it.contains(query, true) })
    }.sortedWith(compareBy({ it.id !in s.favorites }, { it.category != "Crisis" }, { it.builtin }, { it.name }))
    val referrals = s.records(Specs.REFERRAL)

    Scaffold(topBar = {
        BlTopBar("Resources & Referrals", subtitle = "${all.size} resources · ${referrals.count { it.v("status") in setOf("Made", "Pending", "Not connected yet", "Waitlisted") }} referrals open", onBack = { shell.back() }) {
            IconButton(onClick = { edit = Bh.Resource(name = "", category = "Other") }) { Icon(Icons.Rounded.Add, "Add resource") }
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Directory") }); Tab(tab == 1, { tab = 1 }, text = { Text("Referrals") })
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (tab == 0) {
                    item {
                        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search resources") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                            singleLine = true, shape = RoundedCornerShape(28.dp))
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OptionalDropdown("Category", Library.RESOURCE_CATEGORIES, category, { category = it }, Modifier.weight(1f))
                            IconButton(onClick = { favOnly = !favOnly }) { Icon(if (favOnly) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Favorites only", tint = if (favOnly) Amber.readable() else MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                    items(shown, key = { it.id }) { r -> ResourceCard(r, r.id in s.favorites, onEdit = { edit = if (r.builtin) r.copy(id = Bh.id(), builtin = false) else r }, onDelete = { del = r }) }
                    item {
                        Text("National numbers change occasionally — confirm before relying on one, and add your county crisis team, local shelters, food banks, clinics and meetings with +.",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    item {
                        TextButton(onClick = { shell.go("bh/form/${Specs.REFERRAL}") }) { Icon(Icons.Rounded.Add, null); Text("New referral") }
                    }
                    val groups = referrals.groupBy { it.v("status").ifBlank { "Made" } }
                    listOf("Pending", "Made", "Not connected yet", "Waitlisted", "Connected", "Declined", "Ineligible").forEach { st ->
                        val list = groups[st].orEmpty()
                        if (list.isNotEmpty()) {
                            item(key = "h$st") { SectionHeader("$st · ${list.size}") }
                            items(list, key = { it.id }) { r ->
                                BlCard(Modifier.fillMaxWidth(), onClick = { shell.go("bh/form/${Specs.REFERRAL}?id=${r.id}") }, accent = statusColor(st)) {
                                    Text(r.v("resource").ifBlank { "Referral" }, style = MaterialTheme.typography.titleSmall)
                                    Text(listOfNotNull(r.v("who").ifBlank { null } ?: s.participants.firstOrNull { it.id == r.ref }?.alias, r.v("reason").ifBlank { null }, r.v("follow").ifBlank { null }?.let { "follow up $it" })
                                        .joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    if (referrals.isEmpty()) item { Text("Tap Refer on any resource to start a referral with it filled in.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
    edit?.let { r -> ResourceDialog(r, onDismiss = { edit = null }) { BhStore.upsertResource(ctx, it) } }
    del?.let { r -> ConfirmDialog("Delete ${r.name}?", "Removes it from your directory.", onDismiss = { del = null }) { BhStore.deleteResource(ctx, r.id) } }
}

private fun statusColor(st: String) = when (st) { "Connected" -> Success; "Declined", "Ineligible" -> null; "Pending", "Not connected yet" -> Amber; else -> Cobalt }

@Composable
private fun ResourceCard(r: Bh.Resource, fav: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    val shell = LocalShell.current
    BlCard(Modifier.fillMaxWidth(), accent = if (r.category == "Crisis") Danger else if (fav) Amber else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.name, style = MaterialTheme.typography.titleSmall)
                Text(listOfNotNull(r.category, r.hours.ifBlank { null }, r.cost.ifBlank { null }).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!r.builtin) Pill("Local", Violet)
            IconButton(onClick = { BhStore.toggleFavorite(ctx, r.id) }) { Icon(if (fav) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Favorite", tint = if (fav) Amber.readable() else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        listOfNotNull(r.phone.ifBlank { null }?.let { "☎ $it" }, r.text.ifBlank { null }?.let { "✉ $it" }, r.address.ifBlank { null }, r.eligibility.ifBlank { null }?.let { "Eligibility: $it" })
            .forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (r.notes.isNotBlank()) Text(r.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row {
            if (r.phone.isNotBlank()) IconButton(onClick = { dial(ctx, r.phone) }) { Icon(Icons.Rounded.Call, "Call", tint = Mint.readable()) }
            Library.smsTarget(r.text)?.let { (num, body) -> IconButton(onClick = { sms(ctx, num, body) }) { Icon(Icons.Rounded.Sms, "Text", tint = Mint.readable()) } }
            if (r.url.isNotBlank()) IconButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url))) } }) { Icon(Icons.Rounded.Language, "Website", tint = Cobalt.readable()) }
            if (r.address.isNotBlank()) IconButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(r.address)))) } }) { Icon(Icons.Rounded.Map, "Map", tint = Cobalt.readable()) }
            IconButton(onClick = {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText(r)), "Share resource"))
            }) { Icon(Icons.Rounded.Share, "Share") }
            IconButton(onClick = {
                val rec = Forms.blank(Specs.referral).with("resource", r.name).with("category", r.category).with("status", "Made")
                BhStore.saveRecord(ctx, rec); shell.go("bh/form/${Specs.REFERRAL}?id=${rec.id}")
            }) { Icon(Icons.Rounded.AssignmentInd, "Refer") }
            IconButton(onClick = onEdit) { Icon(if (r.builtin) Icons.Rounded.ContentCopy else Icons.Rounded.Edit, if (r.builtin) "Copy to my resources" else "Edit") }
            if (!r.builtin) IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, "Delete") }
        }
    }
}

/** Resource info to hand to someone — no personal details involved. */
private fun shareText(r: Bh.Resource) = listOfNotNull(r.name, r.phone.ifBlank { null }?.let { "Call: $it" }, r.text.ifBlank { null }?.let { "Text: $it" }, r.url.ifBlank { null },
    r.address.ifBlank { null }, r.hours.ifBlank { null }?.let { "Hours: $it" }, r.notes.ifBlank { null }).joinToString("\n")

private fun dial(ctx: Context, phone: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone.filter { it.isDigit() || it == '+' }))) }

private fun sms(ctx: Context, number: String, body: String) = runCatching {
    ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).putExtra("sms_body", body))
}

@Composable
private fun ResourceDialog(r: Bh.Resource, onDismiss: () -> Unit, onSave: (Bh.Resource) -> Unit) {
    var x by remember { mutableStateOf(r) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (r.name.isBlank()) "Add a resource" else "Resource") }, text = {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(x.name, { x = x.copy(name = it) }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
            DropdownField("Category", Library.RESOURCE_CATEGORIES.map { it to it }, x.category, { x = x.copy(category = it) })
            OutlinedTextField(x.phone, { x = x.copy(phone = it) }, Modifier.fillMaxWidth(), label = { Text("Phone") }, singleLine = true)
            OutlinedTextField(x.text, { x = x.copy(text = it) }, Modifier.fillMaxWidth(), label = { Text("Text line") }, placeholder = { Text("e.g. Text HOME to 741741") }, singleLine = true)
            OutlinedTextField(x.url, { x = x.copy(url = it) }, Modifier.fillMaxWidth(), label = { Text("Website") }, singleLine = true)
            OutlinedTextField(x.address, { x = x.copy(address = it) }, Modifier.fillMaxWidth(), label = { Text("Address") })
            OutlinedTextField(x.hours, { x = x.copy(hours = it) }, Modifier.fillMaxWidth(), label = { Text("Hours") }, singleLine = true)
            OutlinedTextField(x.eligibility, { x = x.copy(eligibility = it) }, Modifier.fillMaxWidth(), label = { Text("Eligibility") })
            DropdownField("Cost", listOf("" to "—", "Free" to "Free", "Sliding scale" to "Sliding scale", "Medicaid accepted" to "Medicaid accepted", "Insurance" to "Insurance",
                "Self-pay" to "Self-pay"), x.cost, { x = x.copy(cost = it) })
            OutlinedTextField(x.notes, { x = x.copy(notes = it) }, Modifier.fillMaxWidth(), label = { Text("Notes") })
        }
    }, confirmButton = { TextButton(enabled = x.name.isNotBlank(), onClick = { onSave(x.copy(builtin = false)); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

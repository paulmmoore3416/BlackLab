package com.blacklab.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.blacklab.app.core.Recurrence
import com.blacklab.app.core.Time
import java.time.DayOfWeek
import java.time.LocalDate

@Composable
fun RepeatDialog(current: String?, anchor: LocalDate, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    val parsed = Recurrence.parse(current)
    var custom by remember { mutableStateOf(current != null && Recurrence.presets.none { it.second == current }) }
    var interval by remember { mutableStateOf((parsed?.interval ?: 1).toString()) }
    var freq by remember { mutableStateOf(parsed?.freq ?: Recurrence.Freq.WEEKLY) }
    var byDay by remember { mutableStateOf(parsed?.byDay ?: setOf(anchor.dayOfWeek)) }
    var endMode by remember { mutableStateOf(if (parsed?.until != null) 1 else if (parsed?.count != null) 2 else 0) }
    var until by remember { mutableStateOf(parsed?.until ?: anchor.plusMonths(3)) }
    var count by remember { mutableStateOf((parsed?.count ?: 10).toString()) }
    var pickUntil by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Repeat") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Recurrence.presets.forEach { (label, rule) ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(rule); onDismiss() }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = !custom && current == rule, onClick = { onPick(rule); onDismiss() })
                        Text(label)
                    }
                }
                Row(Modifier.fillMaxWidth().clickable { custom = true }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = custom, onClick = { custom = true })
                    Text("Custom…")
                }
                if (custom) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Every ")
                        OutlinedTextField(interval, { interval = it.filter(Char::isDigit).take(3) }, singleLine = true, modifier = Modifier.width(72.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    ChipFlow(Modifier.padding(vertical = 6.dp)) {
                        Recurrence.Freq.entries.forEach { f ->
                            FilterChip(freq == f, { freq = f }, label = { Text(f.name.lowercase().replace("ly", "").replace("dai", "day").replaceFirstChar { it.uppercase() } + "(s)") })
                        }
                    }
                    if (freq == Recurrence.Freq.WEEKLY) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            DayOfWeek.entries.forEach { d ->
                                FilterChip(d in byDay, { byDay = if (d in byDay) byDay - d else byDay + d }, label = { Text(Time.dayNarrow(d)) })
                            }
                        }
                    }
                    Text("Ends", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    listOf("Never", "On date", "After").forEachIndexed { i, l ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { endMode = i }) {
                            RadioButton(endMode == i, { endMode = i })
                            Text(l)
                            if (i == 1 && endMode == 1) TextButton(onClick = { pickUntil = true }) { Text(Time.date(until)) }
                            if (i == 2 && endMode == 2) {
                                OutlinedTextField(count, { count = it.filter(Char::isDigit).take(4) }, singleLine = true, modifier = Modifier.width(80.dp).padding(start = 8.dp),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                                Text(" times")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (custom) TextButton(onClick = {
                val r = Recurrence(
                    freq = freq,
                    interval = interval.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                    byDay = if (freq == Recurrence.Freq.WEEKLY) byDay else emptySet(),
                    until = if (endMode == 1) until else null,
                    count = if (endMode == 2) count.toIntOrNull()?.coerceAtLeast(1) else null,
                )
                onPick(r.toRule()); onDismiss()
            }) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (pickUntil) DatePickerDialogBox(until, { pickUntil = false }) { it?.let { d -> until = d } }
}

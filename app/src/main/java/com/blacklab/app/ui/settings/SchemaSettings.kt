package com.blacklab.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.blacklab.app.core.SettingsSchema
import com.blacklab.app.core.Time
import com.blacklab.app.ui.components.DropdownField
import com.blacklab.app.ui.components.LabeledSwitchRow
import com.blacklab.app.ui.components.SettingRow
import com.blacklab.app.ui.components.TextInputDialog
import com.blacklab.app.ui.components.TimePickerDialogBox
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.components.settingsState
import org.json.JSONObject
import kotlin.math.roundToInt

/** Renders any setting from [SettingsSchema] with the control that fits its type. */
@Composable
fun SchemaItem(def: SettingsSchema.Def) {
    val app = rememberApp()
    settingsState() // recompose when settings change
    val value = SettingsSchema.read(app.settings.prefs, def)
    fun set(v: Any) { SettingsSchema.apply(app.settings, JSONObject().put(def.key, v)); app.requestReminderSync() }
    var editing by remember { mutableStateOf(false) }

    when (def.ui) {
        "bool" -> LabeledSwitchRow(def.label, def.help, value as Boolean, enabled = !def.readOnly) { set(it) }
        "choice", "weekday" -> Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            DropdownField(def.label, def.options, value.toString(), { v -> set(if (def.store == SettingsSchema.Store.INT) v.toInt() else v) })
            if (def.help.isNotBlank()) Text(def.help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
        }
        "time" -> {
            SettingRow(def.label, Time.time(value as Int) + " · " + def.help) { editing = true }
            if (editing) TimePickerDialogBox(value, { editing = false }) { set(it) }
        }
        "percent", "int" -> {
            val min = def.min ?: 0.0; val max = def.max ?: 100.0
            val steps = ((max - min) / (def.step ?: 1.0)).roundToInt()
            if (steps in 1..200) {
                var local by remember(value) { mutableFloatStateOf((value as Number).toFloat()) }
                Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(def.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text("${local.roundToInt()}${if (def.unit == "%") "%" else " " + def.unit}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(local, { local = it }, valueRange = min.toFloat()..max.toFloat(), steps = (steps - 1).coerceAtLeast(0),
                        onValueChangeFinished = { set(local.roundToInt()) }, enabled = !def.readOnly)
                    if (def.help.isNotBlank()) Text(def.help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                SettingRow(def.label, "$value ${def.unit} · ${def.help}") { if (!def.readOnly) editing = true }
                if (editing) TextInputDialog(def.label, value.toString(), "${min.toInt()}–${max.toInt()} ${def.unit}", keyboardType = KeyboardType.Number, onDismiss = { editing = false }) { t ->
                    t.toIntOrNull()?.let { set(it) }
                }
            }
        }
        "float" -> {
            SettingRow(def.label, "$value ${def.unit} · ${def.help}") { editing = true }
            if (editing) TextInputDialog(def.label, value.toString(), def.unit, keyboardType = KeyboardType.Decimal, onDismiss = { editing = false }) { t -> t.toDoubleOrNull()?.let { set(it) } }
        }
        "set" -> SettingRow(def.label, ((value as? List<*>)?.size ?: 0).toString() + " item(s) · " + def.help) {}
        else -> {
            SettingRow(def.label, (value as? String).orEmpty().ifBlank { "Not set" } + if (def.help.isNotBlank()) " · ${def.help}" else "") { if (!def.readOnly) editing = true }
            if (editing) TextInputDialog(def.label, value?.toString().orEmpty(), onDismiss = { editing = false }) { set(it) }
        }
    }
}

fun matchesQuery(def: SettingsSchema.Def, section: SettingsSchema.Section, q: String): Boolean {
    val words = q.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
    val hay = (def.label + " " + def.help + " " + section.title + " " + def.options.joinToString(" ") { it.second }).lowercase()
    return words.all { hay.contains(it) }
}

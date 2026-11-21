package com.blacklab.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blacklab.app.link.SystemHealth
import com.blacklab.app.ui.components.LocalShell

/** The computer's overnight system check, shown once — the first time BlackLab opens after it arrives. */
@Composable
internal fun SystemHealthSheet() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { SystemHealth.load(ctx) } }
    val report by SystemHealth.latest.collectAsStateWithLifecycle()
    val r = report?.takeIf { !it.seen } ?: return
    val shell = LocalShell.current
    val dismiss = { SystemHealth.markSeen(ctx) }
    val tint = tintFor(r.level)
    AlertDialog(
        onDismissRequest = dismiss,
        icon = { Icon(iconFor(r.level), null, tint = tint) },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Overnight system check")
                Text(r.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    when (r.level) {
                        SystemHealth.Level.OK -> "Healthy"
                        SystemHealth.Level.WARN -> "Needs a look"
                        SystemHealth.Level.FAIL -> "Problems found"
                    },
                    color = tint, fontWeight = FontWeight.SemiBold,
                )
                Text(r.summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                r.issues.take(12).forEach { i ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(iconFor(i.level), null, tint = tintFor(i.level), modifier = Modifier.size(18.dp).padding(top = 2.dp))
                        Column {
                            Text(i.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            if (i.detail.isNotBlank()) Text(i.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (i.fix.isNotBlank()) Text("Fix: ${i.fix}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                if (r.issues.size > 12) Text("…and ${r.issues.size - 12} more in the full report.", style = MaterialTheme.typography.bodySmall)
                r.fixed.forEach { f ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.AutoFixHigh, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp).padding(top = 2.dp))
                        Text("Fixed: $f", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("Got it") } },
        dismissButton = if (r.docId > 0) {
            { TextButton(onClick = { dismiss(); shell.go("doc/${r.docId}") }) { Text("Full report") } }
        } else null,
    )
}

private fun iconFor(l: SystemHealth.Level) = when (l) {
    SystemHealth.Level.OK -> Icons.Rounded.CheckCircle
    SystemHealth.Level.WARN -> Icons.Rounded.Warning
    SystemHealth.Level.FAIL -> Icons.Rounded.Error
}

@Composable
private fun tintFor(l: SystemHealth.Level): Color = when (l) {
    SystemHealth.Level.OK -> MaterialTheme.colorScheme.primary
    SystemHealth.Level.WARN -> Color(0xFFE0A100)
    SystemHealth.Level.FAIL -> MaterialTheme.colorScheme.error
}

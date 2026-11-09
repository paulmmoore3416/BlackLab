package com.blacklab.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.blacklab.app.core.Net

/**
 * The one way BlackLab shows network trouble: an icon for the kind of problem, the same plain words
 * (Net.message), and Retry / Fix buttons when they make sense. Announced by TalkBack when it appears.
 */
@Composable
fun ProblemBanner(problem: Net.Problem, service: String, modifier: Modifier = Modifier, message: String? = null,
                  onRetry: (() -> Unit)? = null, fixLabel: String? = null, onFix: (() -> Unit)? = null) {
    val icon = when (problem) {
        Net.Problem.OFFLINE -> Icons.Rounded.CloudOff
        Net.Problem.TIMEOUT, Net.Problem.RATE_LIMITED -> Icons.Rounded.HourglassTop
        Net.Problem.SIGN_IN -> Icons.Rounded.Key
        else -> Icons.Rounded.ErrorOutline
    }
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(message ?: Net.message(problem, service), style = MaterialTheme.typography.bodySmall)
            }
            if (onFix != null && fixLabel != null) TextButton(onClick = onFix) { Text(fixLabel) }
            if (onRetry != null && Net.retryable(problem)) TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

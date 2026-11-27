package com.blacklab.app.ui.agentops

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

/** One bar per day (oldest first), scaled to the busiest day; a quiet day is a faint stub. Nothing when all are zero. */
@Composable
fun Sparkline(values: List<Int>, modifier: Modifier, color: Color) {
    val top = values.maxOrNull() ?: 0
    if (top <= 0) return
    Canvas(modifier) {
        val gap = size.width * 0.25f / values.size
        val w = (size.width - gap * (values.size - 1)) / values.size
        values.forEachIndexed { i, v ->
            val h = if (v == 0) size.height * 0.08f else size.height * (0.2f + 0.8f * v / top)
            drawRoundRect(
                color = if (v == 0) color.copy(alpha = 0.25f) else color.copy(alpha = if (i == values.lastIndex) 1f else 0.7f),
                topLeft = Offset(i * (w + gap), size.height - h), size = Size(w, h), cornerRadius = CornerRadius(w / 3, w / 3),
            )
        }
    }
}

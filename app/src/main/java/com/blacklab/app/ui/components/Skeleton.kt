package com.blacklab.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.blacklab.app.ui.theme.Motion

/** A soft sweeping highlight for placeholders; still when motion is reduced. */
@Composable
fun shimmerBrush(): Brush {
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val hi = MaterialTheme.colorScheme.surfaceContainerHighest
    if (Motion.reduced) return Brush.linearGradient(listOf(base, base))
    val t by rememberInfiniteTransition(label = "shimmer").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "x",
    )
    val x = -600f + t * 1800f
    return Brush.linearGradient(listOf(base, hi, base), start = Offset(x, 0f), end = Offset(x + 600f, 300f))
}

@Composable
fun SkeletonBox(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(8.dp), brush: Brush = shimmerBrush()) {
    Box(modifier.clip(shape).background(brush))
}

/** Placeholder rows shaped like a list of items (icon/checkbox, title, subtitle) while data loads. */
@Composable
fun SkeletonRows(rows: Int = 4, modifier: Modifier = Modifier, leading: Dp = 22.dp) {
    val brush = shimmerBrush()
    Column(modifier.fillMaxWidth().semantics { contentDescription = "Loading" }, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(rows) { i ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                SkeletonBox(Modifier.size(leading), RoundedCornerShape(6.dp), brush)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    SkeletonBox(Modifier.fillMaxWidth(if (i % 2 == 0) 0.72f else 0.55f).height(14.dp), brush = brush)
                    Spacer(Modifier.height(6.dp))
                    SkeletonBox(Modifier.fillMaxWidth(0.35f).height(10.dp), brush = brush)
                }
            }
        }
    }
}

/** A page-shaped placeholder (documents and PDFs) with text lines. */
@Composable
fun SkeletonPage(modifier: Modifier = Modifier) {
    val brush = shimmerBrush()
    Column(modifier.fillMaxWidth().padding(20.dp).semantics { contentDescription = "Loading" }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SkeletonBox(Modifier.fillMaxWidth(0.6f).height(22.dp), brush = brush)
        Spacer(Modifier.height(6.dp))
        repeat(9) { i -> SkeletonBox(Modifier.fillMaxWidth(if (i % 4 == 3) 0.5f else 1f).height(12.dp), brush = brush) }
        Spacer(Modifier.height(8.dp))
        SkeletonBox(Modifier.fillMaxWidth().height(120.dp), RoundedCornerShape(12.dp), brush)
        repeat(4) { i -> SkeletonBox(Modifier.fillMaxWidth(if (i == 3) 0.4f else 1f).height(12.dp), brush = brush) }
    }
}

@Composable
fun SkeletonAvatar(size: Dp = 40.dp) = SkeletonBox(Modifier.size(size), CircleShape)

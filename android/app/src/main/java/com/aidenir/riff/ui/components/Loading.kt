package com.aidenir.riff.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Riff's loading indicator: equalizer bars bouncing in the app's gradient,
 * in place of a plain spinner.
 */
@Composable
fun EqualizerLoader(modifier: Modifier = Modifier, height: Dp = 40.dp, bars: Int = 5) {
    val transition = rememberInfiniteTransition(label = "equalizer")
    val colors = MaterialTheme.colorScheme
    val gradient = Brush.verticalGradient(listOf(colors.primary, colors.tertiary, colors.secondary))
    Row(
        modifier.height(height),
        horizontalArrangement = Arrangement.spacedBy(height / 8),
        verticalAlignment = Alignment.Bottom,
    ) {
        repeat(bars) { index ->
            val level by transition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(durationMillis = 420 + index * 90 % 260, delayMillis = index * 70, easing = FastOutSlowInEasing),
                    RepeatMode.Reverse,
                ),
                label = "bar$index",
            )
            Box(
                Modifier
                    .width(height / 5)
                    .fillMaxHeight(level)
                    .background(gradient, RoundedCornerShape(50)),
            )
        }
    }
}

/** A light sweep across a placeholder while its content loads. */
fun Modifier.shimmer(): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing)),
        label = "sweep",
    )
    val highlight = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    drawWithContent {
        drawContent()
        val x = size.width * progress
        drawRect(
            Brush.linearGradient(
                listOf(Color.Transparent, highlight, Color.Transparent),
                start = Offset(x - size.width / 2, 0f),
                end = Offset(x + size.width / 2, size.height),
            ),
        )
    }
}

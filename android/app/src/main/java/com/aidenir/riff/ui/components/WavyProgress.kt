package com.aidenir.riff.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * The squiggly progress line of Android's media controls: the played part
 * is a wave that travels while playing and flattens out when paused, the
 * rest is a straight line. With [onSeek] it can be tapped and dragged.
 */
@Composable
fun WavyProgress(
    progress: Float,
    playing: Boolean,
    played: Brush,
    remaining: Color,
    modifier: Modifier = Modifier,
    height: Dp = 16.dp,
    stroke: Dp = 3.dp,
    thumb: Boolean = false,
    onSeek: ((Float) -> Unit)? = null,
) {
    val amplitude by animateFloatAsState(if (playing) 1f else 0f, tween(400), label = "amplitude")
    val phase by rememberInfiniteTransition(label = "wave").animateFloat(
        0f, (2 * PI).toFloat(), infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "phase",
    )
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = (dragging ?: progress).coerceIn(0f, 1f)
    val input = if (onSeek == null) {
        Modifier
    } else {
        Modifier
            .pointerInput(Unit) {
                detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = {
                        dragging?.let(onSeek)
                        dragging = null
                    },
                    onDragCancel = { dragging = null },
                ) { change, _ ->
                    dragging = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            }
    }
    Canvas(modifier.fillMaxWidth().height(height).then(input)) {
        val strokePx = stroke.toPx()
        val mid = size.height / 2
        val wave = (size.height / 2 - strokePx) * 0.55f * amplitude
        val end = size.width * shown
        val wavelength = 28.dp.toPx()
        val gap = if (thumb) 6.dp.toPx() else 4.dp.toPx()
        // The straight remainder.
        if (end + gap < size.width) {
            drawLine(remaining, Offset(end + gap, mid), Offset(size.width, mid), strokePx, StrokeCap.Round)
        }
        // The travelling wave up to the current position.
        if (end > 0f) {
            val path = Path()
            var x = 0f
            path.moveTo(0f, mid + wave * sin(phase))
            while (x < end) {
                x = minOf(x + 2f, end)
                path.lineTo(x, mid + wave * sin(phase + x / wavelength * 2 * PI.toFloat()))
            }
            drawPath(path, played, style = Stroke(strokePx, cap = StrokeCap.Round))
        }
        if (thumb) {
            drawLine(played, Offset(end, mid - size.height / 2 + 1f), Offset(end, mid + size.height / 2 - 1f), strokePx * 1.6f, StrokeCap.Round)
        }
    }
}

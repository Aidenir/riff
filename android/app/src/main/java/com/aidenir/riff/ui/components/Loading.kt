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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.togetherWith
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

/**
 * The page loader: a record spinning with the Riff badge as its label, notes
 * drifting up from it, the equalizer keeping time and a caption that changes
 * every moment. Pops in with a bounce so even a short load feels lively.
 */
@Composable
fun RiffLoader(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val captions = androidx.compose.ui.res.stringArrayResource(com.aidenir.riff.R.array.loading_captions)
    val appear = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        appear.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 300f))
    }
    val transition = rememberInfiniteTransition(label = "record")
    val spin by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "spin")
    val notes by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "notes")
    val captionIndex by transition.animateFloat(
        0f, captions.size.toFloat(), infiniteRepeatable(tween(captions.size * 1500, easing = LinearEasing)), label = "caption",
    )
    val noteColors = listOf(colors.primary, colors.tertiary, colors.secondary)
    Box(modifier, contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayerScale(appear.value),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(260.dp)) {
                // Notes rising and swaying out of the record.
                androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                    repeat(3) { i ->
                        val t = (notes + i / 3f) % 1f
                        val x = size.width * (0.5f + (i - 1) * 0.28f) + kotlin.math.sin(t * 6.28f + i) * 14.dp.toPx()
                        val y = size.height * (0.55f - 0.6f * t)
                        val alpha = (if (t < 0.2f) t / 0.2f else 1f - (t - 0.2f) / 0.8f).coerceIn(0f, 1f)
                        val r = 8.dp.toPx()
                        drawCircle(noteColors[i], r, Offset(x, y), alpha = alpha)
                        drawLine(noteColors[i], Offset(x + r * 0.9f, y), Offset(x + r * 0.9f, y - r * 3.2f), 3.dp.toPx(), alpha = alpha)
                        drawLine(noteColors[i], Offset(x + r * 0.9f, y - r * 3.2f), Offset(x + r * 2.4f, y - r * 2.4f), 3.dp.toPx(), alpha = alpha)
                    }
                }
                // The record: grooves, a sheen that turns with it, and the badge.
                Box(
                    Modifier
                        .size(176.dp)
                        .shadow(28.dp, androidx.compose.foundation.shape.CircleShape, ambientColor = colors.primary, spotColor = colors.primary)
                        .spinning(spin),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                        val radius = size.minDimension / 2
                        drawCircle(Brush.radialGradient(listOf(Color(0xFF2A302E), Color(0xFF0E1110))), radius)
                        drawCircle(
                            Brush.sweepGradient(listOf(colors.primary, colors.tertiary, colors.secondary, colors.primary)),
                            radius - 1.5.dp.toPx(),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()),
                        )
                        for (groove in 0 until 9) {
                            drawCircle(
                                Color.White,
                                radius * (0.48f + groove * 0.055f),
                                alpha = 0.08f + 0.05f * (groove % 2),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()),
                            )
                        }
                        drawArc(
                            Brush.sweepGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.32f), Color.Transparent)),
                            startAngle = -40f, sweepAngle = 80f, useCenter = true,
                        )
                        drawCircle(
                            Brush.linearGradient(listOf(colors.primary, colors.secondary)),
                            radius * 0.4f,
                        )
                    }
                    androidx.compose.foundation.Image(
                        androidx.compose.ui.res.painterResource(com.aidenir.riff.R.mipmap.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier.size(112.dp),
                    )
                }
            }
            EqualizerLoader(Modifier.padding(top = 4.dp), height = 30.dp, bars = 7)
            androidx.compose.animation.AnimatedContent(
                targetState = captionIndex.toInt().coerceIn(0, captions.lastIndex),
                transitionSpec = {
                    (androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn()) togetherWith
                        (androidx.compose.animation.slideOutVertically { -it } + androidx.compose.animation.fadeOut())
                },
                label = "caption",
                modifier = Modifier.padding(top = 14.dp),
            ) { index ->
                androidx.compose.material3.Text(
                    captions[index],
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

private fun Modifier.graphicsLayerScale(scale: Float) = this.then(
    Modifier.graphicsLayer {
        scaleX = 0.6f + 0.4f * scale
        scaleY = 0.6f + 0.4f * scale
        alpha = scale.coerceIn(0f, 1f)
    },
)

private fun Modifier.spinning(degrees: Float) = this.then(Modifier.graphicsLayer { rotationZ = degrees })

/**
 * Whether to keep showing the loader: while [loading], and once it has shown,
 * for at least [minimumMs] so a fast load still gets its moment instead of a
 * flicker. Pages whose data is already there never see it.
 */
@Composable
fun rememberLoaderVisible(loading: Boolean, minimumMs: Long = 1500): Boolean {
    var shownSince by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(if (loading) android.os.SystemClock.elapsedRealtime() else -1L) }
    var holding by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(loading) }
    androidx.compose.runtime.LaunchedEffect(loading) {
        if (loading) {
            if (shownSince < 0) shownSince = android.os.SystemClock.elapsedRealtime()
            holding = true
        } else if (shownSince >= 0) {
            val left = minimumMs - (android.os.SystemClock.elapsedRealtime() - shownSince)
            if (left > 0) kotlinx.coroutines.delay(left)
            holding = false
        } else {
            holding = false
        }
    }
    return loading || holding
}

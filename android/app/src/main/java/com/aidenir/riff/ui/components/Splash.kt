package com.aidenir.riff.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aidenir.riff.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The intro after the system splash: the badge bounces in over pulsing
 * rings and a drifting glow, the name drops in letter by letter, and the
 * equalizer keeps time while the session is restored.
 */
@Composable
fun SplashIntro(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val logoScale = remember { Animatable(0.2f) }
    val logoTurn = remember { Animatable(-90f) }
    val letters = remember { "Riff".map { Animatable(0f) } }
    LaunchedEffect(Unit) {
        launch { logoScale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow)) }
        launch { logoTurn.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessVeryLow)) }
        delay(250)
        letters.forEach { letter ->
            launch { letter.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow)) }
            delay(90)
        }
    }
    val transition = rememberInfiniteTransition(label = "splash")
    val pulse by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "pulse",
    )
    val glow by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(3200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow",
    )
    val breathe by transition.animateFloat(
        0.97f, 1.04f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe",
    )

    Box(modifier.fillMaxSize().background(colors.background), contentAlignment = Alignment.Center) {
        // A glow that drifts between the icon's green and blue.
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width * (0.35f + 0.3f * glow), size.height * (0.4f + 0.15f * glow))
            drawCircle(
                Brush.radialGradient(
                    listOf(colors.primary.copy(alpha = 0.35f), colors.secondary.copy(alpha = 0.15f), colors.background.copy(alpha = 0f)),
                    center = center,
                    radius = size.minDimension * 0.9f,
                ),
                radius = size.minDimension * 0.9f,
                center = center,
            )
        }
        // Rings rippling out from the badge.
        Canvas(Modifier.size(360.dp)) {
            repeat(3) { ring ->
                val phase = (pulse + ring / 3f) % 1f
                drawCircle(
                    Brush.sweepGradient(listOf(colors.primary, colors.tertiary, colors.secondary, colors.primary)),
                    radius = size.minDimension / 2 * (0.3f + 0.7f * phase),
                    alpha = (1f - phase) * 0.6f,
                    style = Stroke(width = (6f * (1f - phase) + 1f).dp.toPx()),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Image(
                painterResource(R.mipmap.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier
                    .size(220.dp)
                    .scale(logoScale.value * breathe)
                    .rotate(logoTurn.value),
            )
            Row {
                "Riff".forEachIndexed { index, char ->
                    val progress = letters[index].value
                    Text(
                        char.toString(),
                        fontSize = 52.sp,
                        fontWeight = FontWeight.Black,
                        style = MaterialTheme.typography.displayMedium.copy(
                            brush = Brush.linearGradient(listOf(colors.primary, colors.secondary)),
                        ),
                        modifier = Modifier
                            .alpha(progress.coerceIn(0f, 1f))
                            .graphicsLayer { translationY = (1f - progress) * 80f },
                    )
                }
            }
            EqualizerLoader(Modifier.padding(top = 8.dp), height = 28.dp)
        }
    }
}

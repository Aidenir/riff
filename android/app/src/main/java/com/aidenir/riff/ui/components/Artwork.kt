package com.aidenir.riff.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.aidenir.riff.core.ImageLoader
import com.aidenir.riff.ffi.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Artwork from the core's image cache, with a placeholder while loading. */
@Composable
fun ArtworkImage(
    images: ImageLoader,
    url: String?,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.small,
    priority: Priority = Priority.VISIBLE,
    placeholder: ImageVector = Icons.Default.MusicNote,
) {
    BoxWithConstraints(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        val px = with(LocalDensity.current) { maxWidth.roundToPx().coerceAtLeast(48) }
        val bitmap by produceState(url?.let { images.cached(it, px) }, url, px) {
            if (url != null && value == null) value = images.load(url, px, priority)
        }
        val image = bitmap
        val iconSize = maxWidth / 3
        Crossfade(image, animationSpec = tween(350), label = "artwork") { shown ->
            if (shown != null) {
                Image(
                    bitmap = remember(shown) { shown.asImageBitmap() },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize().then(if (url != null) Modifier.shimmer() else Modifier)) {
                    Icon(
                        placeholder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.align(Alignment.Center).size(iconSize),
                    )
                }
            }
        }
    }
}

private val tints = mutableMapOf<String, Color>()

/** The average colour of a piece of artwork, like the GTK and macOS art tint. */
suspend fun artTint(images: ImageLoader, url: String): Color? {
    tints[url]?.let { return it }
    val bitmap = images.load(url, 96, Priority.HERO) ?: return null
    val color = withContext(Dispatchers.Default) {
        val pixel = Bitmap.createScaledBitmap(bitmap, 1, 1, true).getPixel(0, 0)
        Color(pixel)
    }
    tints[url] = color
    return color
}

/** A soft gradient from the artwork's colour, behind headers and Now Playing. */
@Composable
fun ArtTintBackground(images: ImageLoader, url: String?, modifier: Modifier = Modifier, strength: Float = 0.45f) {
    var tint by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(url) { tint = url?.let { artTint(images, it) } }
    val color by animateColorAsState(tint?.copy(alpha = strength) ?: Color.Transparent, label = "tint")
    Box(modifier.background(Brush.verticalGradient(listOf(color, Color.Transparent))))
}

val CircleShapeArt: Shape = androidx.compose.foundation.shape.CircleShape
val SquareArt: Shape = RectangleShape

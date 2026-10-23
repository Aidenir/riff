package com.aidenir.riff.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aidenir.riff.R
import com.aidenir.riff.core.AppState
import com.aidenir.riff.ffi.Priority
import com.aidenir.riff.ui.Tab
import com.aidenir.riff.ui.pages.MiniPlayer

private val DockShape = RoundedCornerShape(32.dp)

/** Colours for content on the dock, which is darkened glass while something plays. */
data class DockColors(val content: Color, val secondary: Color, val glass: Boolean)

val LocalDockColors = staticCompositionLocalOf { DockColors(Color.White, Color.White.copy(alpha = 0.7f), false) }

private val tabs = listOf(
    Triple(Tab.Home, Icons.Filled.Home to Icons.Outlined.Home, R.string.home),
    Triple(Tab.Search, Icons.Filled.Search to Icons.Outlined.Search, R.string.search),
    Triple(Tab.Library, Icons.Filled.LibraryMusic to Icons.Outlined.LibraryMusic, R.string.library),
)

/**
 * The floating bottom dock: the mini player and the tabs on one card. While
 * something plays the card is the blurred album art under a dark glass, like
 * the system media controls; one indicator springs between the tabs.
 */
@Composable
fun BottomDock(app: AppState, modifier: Modifier = Modifier) {
    val playback by app.playback.collectAsState()
    val track = playback.snapshot.current
    val colors = MaterialTheme.colorScheme
    val artUrl = track?.art?.small ?: track?.art?.medium
    val art by produceState<Bitmap?>(artUrl?.let { app.images.cached(it, 96) }, artUrl) {
        value = artUrl?.let { app.images.load(it, 96, Priority.VISIBLE) }
    }
    val glass = art != null
    val dockColors = if (glass) {
        DockColors(Color.White, Color.White.copy(alpha = 0.72f), true)
    } else {
        DockColors(colors.onSurface, colors.onSurfaceVariant, false)
    }

    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .shadow(24.dp, DockShape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(DockShape)
                .background(colors.surfaceContainerHighest),
        ) {
            Crossfade(art, animationSpec = tween(600), modifier = Modifier.matchParentSize(), label = "dock art") { bitmap ->
                if (bitmap != null) {
                    Box(Modifier.fillMaxSize()) {
                        Image(
                            remember(bitmap) { bitmap.asImageBitmap() },
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().scale(1.6f).blur(36.dp),
                        )
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.38f), Color.Black.copy(alpha = 0.62f))),
                            ),
                        )
                    }
                }
            }
            // A thin highlight along the top edge, like light on glass.
            Box(
                Modifier
                    .matchParentSize()
                    .border(
                        1.dp,
                        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.02f))),
                        DockShape,
                    ),
            )
            CompositionLocalProvider(LocalDockColors provides dockColors) {
                Column {
                    MiniPlayer(app)
                    DockTabs(app, dockColors)
                }
            }
        }
    }
}

@Composable
private fun DockTabs(app: AppState, dock: DockColors) {
    val colors = MaterialTheme.colorScheme
    val selectedIndex = tabs.indexOfFirst { it.first == app.navigator.tab }
    BoxWithConstraints(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp, vertical = 8.dp)) {
        val slot = maxWidth / tabs.size
        val offset by animateDpAsState(
            slot * selectedIndex,
            spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow),
            label = "indicator",
        )
        // The indicator: a soft glowing capsule that slides between tabs.
        Box(
            Modifier
                .offset(x = offset)
                .width(slot)
                .fillMaxHeight()
                .padding(horizontal = 6.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    if (dock.glass) Color.White.copy(alpha = 0.16f) else colors.primary.copy(alpha = 0.16f),
                )
                .border(
                    1.dp,
                    if (dock.glass) Color.White.copy(alpha = 0.18f) else colors.primary.copy(alpha = 0.3f),
                    RoundedCornerShape(50),
                ),
        )
        Row(Modifier.fillMaxSize()) {
            tabs.forEachIndexed { index, (tab, icons, label) ->
                DockTab(
                    selected = index == selectedIndex,
                    icon = if (index == selectedIndex) icons.first else icons.second,
                    label = stringResource(label),
                    accent = if (dock.glass) Color.White else colors.primary,
                    idle = dock.secondary,
                    modifier = Modifier.width(slot).fillMaxHeight(),
                ) { app.navigator.select(tab) }
            }
        }
    }
}

@Composable
private fun DockTab(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    accent: Color,
    idle: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val tint by animateColorAsState(if (selected) accent else idle, label = "tint")
    val scale by animateFloatAsState(
        if (selected) 1.08f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "scale",
    )
    Row(
        modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Icon(icon, label, Modifier.size(24.dp).scale(scale), tint = tint)
        if (selected) {
            Text(
                label,
                color = tint,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

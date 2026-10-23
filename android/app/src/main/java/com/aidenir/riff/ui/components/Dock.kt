package com.aidenir.riff.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aidenir.riff.R
import com.aidenir.riff.core.AppState
import com.aidenir.riff.ui.Tab
import com.aidenir.riff.ui.pages.MiniPlayer

private val DockShape = RoundedCornerShape(30.dp)

/**
 * The floating bottom dock: the mini player and the tabs on one rounded,
 * art-tinted card. The selected tab grows into a gradient pill with its label.
 */
@Composable
fun BottomDock(app: AppState, modifier: Modifier = Modifier) {
    val playback by app.playback.collectAsState()
    val track = playback.snapshot.current
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .shadow(18.dp, DockShape, ambientColor = colors.primary, spotColor = colors.primary)
                .clip(DockShape)
                .background(colors.surfaceContainerHigh)
                .border(1.dp, Brush.linearGradient(listOf(colors.primary.copy(alpha = 0.5f), colors.secondary.copy(alpha = 0.3f))), DockShape),
        ) {
            // The artwork's colour washes over the dock while something plays.
            ArtTintBackground(app.images, track?.art?.medium, Modifier.matchParentSize(), strength = 0.35f)
            Column {
                MiniPlayer(app)
                Row(
                    Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    DockTab(app, Tab.Home, Icons.Filled.Home, Icons.Outlined.Home, R.string.home)
                    DockTab(app, Tab.Search, Icons.Filled.Search, Icons.Outlined.Search, R.string.search)
                    DockTab(app, Tab.Library, Icons.Filled.LibraryMusic, Icons.Outlined.LibraryMusic, R.string.library)
                }
            }
        }
    }
}

@Composable
private fun RowScope.DockTab(app: AppState, tab: Tab, filled: ImageVector, outlined: ImageVector, label: Int) {
    val selected = app.navigator.tab == tab
    val colors = MaterialTheme.colorScheme
    val scale by animateFloatAsState(
        if (selected) 1f else 0.92f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "scale",
    )
    val content by animateColorAsState(if (selected) colors.onPrimary else colors.onSurfaceVariant, label = "content")
    val pill = Brush.horizontalGradient(listOf(colors.primary, colors.tertiary, colors.secondary))
    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .scale(scale)
                .clip(CircleShape)
                .then(if (selected) Modifier.background(pill) else Modifier)
                .clickable(remember { MutableInteractionSource() }, indication = null) { app.navigator.select(tab) }
                .padding(horizontal = if (selected) 18.dp else 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (selected) filled else outlined, stringResource(label), Modifier.size(24.dp), tint = content)
            AnimatedVisibility(
                visible = selected,
                enter = expandHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                exit = shrinkHorizontally() + fadeOut(),
            ) {
                Text(
                    stringResource(label),
                    color = content,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 8.dp),
                    maxLines = 1,
                )
            }
        }
    }
}

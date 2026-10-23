package com.aidenir.riff.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aidenir.riff.R
import com.aidenir.riff.core.AppState
import com.aidenir.riff.core.Overlay
import com.aidenir.riff.core.artistNames
import com.aidenir.riff.core.formatDuration
import com.aidenir.riff.ffi.PlaySource
import com.aidenir.riff.ffi.Priority
import com.aidenir.riff.ffi.TrackInfo

/** Long-press multi-selection over a track list, by position. */
@Stable
class TrackSelection {
    var selected by mutableStateOf(emptySet<Int>())
        private set
    val active: Boolean get() = selected.isNotEmpty()

    fun toggle(index: Int) {
        selected = if (index in selected) selected - index else selected + index
    }

    fun clear() {
        selected = emptySet()
    }

    fun tracks(all: List<TrackInfo>): List<TrackInfo> = selected.sorted().mapNotNull { all.getOrNull(it) }
}

/** What the rows need to know about playback, read once per list. */
data class RowContext(val currentId: String?, val playing: Boolean, val liked: (TrackInfo) -> Boolean)

/**
 * Track rows for a lazy list. Tap plays the list from that track, long press
 * starts selecting, the menu button opens the action sheet.
 */
fun LazyListScope.trackRows(
    app: AppState,
    tracks: List<TrackInfo>,
    context: RowContext,
    source: PlaySource?,
    total: UInt? = null,
    selection: TrackSelection? = null,
    playlistId: String? = null,
    showArt: Boolean = true,
    showNumbers: Boolean = false,
    keyPrefix: String = "track",
    onLoadMore: (() -> Unit)? = null,
) {
    itemsIndexed(tracks, key = { index, track -> "$keyPrefix:$index:${track.id}" }) { index, track ->
        if (onLoadMore != null && index >= tracks.size - 10) {
            LaunchedEffect(tracks.size) { onLoadMore() }
        }
        TrackRow(
            modifier = Modifier.animateItem(),
            app = app,
            track = track,
            number = if (showNumbers) (track.trackNumber?.toInt() ?: (index + 1)) else null,
            context = context,
            showArt = showArt,
            selecting = selection?.active == true,
            selected = selection?.selected?.contains(index) == true,
            onClick = {
                if (selection?.active == true) {
                    selection.toggle(index)
                } else {
                    app.play(tracks, source, track, total)
                }
            },
            onLongClick = { selection?.toggle(index) ?: run { app.overlay = Overlay.TrackActions(listOf(track), playlistId) } },
            onMore = { app.overlay = Overlay.TrackActions(listOf(track), playlistId) },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    app: AppState,
    track: TrackInfo,
    context: RowContext,
    modifier: Modifier = Modifier,
    number: Int? = null,
    showArt: Boolean = true,
    selecting: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMore: () -> Unit,
) {
    val current = context.currentId == track.id
    val dim = !track.playable
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (selecting) {
            Icon(
                if (selected) Icons.Default.CheckCircle else Icons.Outlined.Circle,
                null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (number != null) {
            if (current) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, null, Modifier.width(28.dp), tint = MaterialTheme.colorScheme.primary)
            } else {
                Text(
                    number.toString(),
                    modifier = Modifier.width(28.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (showArt) {
            ArtworkImage(app.images, track.art.small ?: track.art.medium, Modifier.size(48.dp), priority = Priority.OFFSCREEN)
        }
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    current -> MaterialTheme.colorScheme.primary
                    dim -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (track.explicit) {
                    ExplicitBadge()
                    Text(" ", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    track.artistNames,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (context.liked(track)) {
            Icon(Icons.Default.Favorite, stringResource(R.string.liked), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(
            formatDuration(track.durationMs.toLong()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = onMore, enabled = !selecting) {
            Icon(Icons.Default.MoreVert, stringResource(R.string.more_actions))
        }
    }
}

/** Replaces the page's top bar while tracks are selected. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionBar(app: AppState, selection: TrackSelection, tracks: List<TrackInfo>, playlistId: String? = null) {
    val chosen = selection.tracks(tracks)
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        navigationIcon = {
            IconButton(onClick = selection::clear) { Icon(Icons.Default.Close, stringResource(R.string.cancel)) }
        },
        title = { Text(pluralStringResource(R.plurals.selected_count, chosen.size, chosen.size)) },
        actions = {
            IconButton(onClick = {
                app.enqueue(chosen)
                selection.clear()
            }) { Icon(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.add_to_queue)) }
            val liked = chosen.isNotEmpty() && chosen.all(app::isLiked)
            IconButton(onClick = {
                app.setLiked(chosen, !liked)
                selection.clear()
            }) {
                Icon(
                    if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    stringResource(if (liked) R.string.remove_from_liked else R.string.save_to_liked),
                )
            }
            IconButton(onClick = {
                app.overlay = Overlay.AddToPlaylist(chosen)
                selection.clear()
            }) { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, stringResource(R.string.add_to_playlist)) }
            if (playlistId != null && app.ownsPlaylist(playlistId)) {
                IconButton(onClick = {
                    val ids = chosen.map { it.id }
                    selection.clear()
                    app.launch { app.core.removeFromPlaylist(playlistId, ids) }
                }) { Icon(Icons.Default.RemoveCircleOutline, stringResource(R.string.remove_from_playlist)) }
            }
            IconButton(onClick = { app.overlay = Overlay.TrackActions(chosen, playlistId) }) {
                Icon(Icons.Default.MoreVert, stringResource(R.string.more_actions))
            }
        },
    )
}

package com.aidenir.riff.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.aidenir.riff.R
import com.aidenir.riff.core.AppState
import com.aidenir.riff.core.Overlay
import com.aidenir.riff.core.artistNames
import com.aidenir.riff.core.describe
import com.aidenir.riff.core.formatDuration
import com.aidenir.riff.ffi.LyricsInfo
import com.aidenir.riff.ffi.PlaySource
import com.aidenir.riff.ffi.Priority
import com.aidenir.riff.ffi.QueueEntry
import com.aidenir.riff.ffi.Repeat
import com.aidenir.riff.ui.NowPlayingTab
import com.aidenir.riff.ui.Route
import com.aidenir.riff.ui.components.ArtTintBackground
import com.aidenir.riff.ui.components.ArtworkImage
import com.aidenir.riff.ui.components.EmptyState
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Ticks while playing so positions keep moving. */
@Composable
private fun rememberNow(playing: Boolean, period: Long = 250): Long {
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(playing) {
        while (playing) {
            now = android.os.SystemClock.elapsedRealtime()
            delay(period)
        }
        now = android.os.SystemClock.elapsedRealtime()
    }
    return now
}

/**
 * The player at the top of the bottom dock. Tap or swipe up opens Now
 * Playing. The dock draws the surface, so this one is transparent.
 */
@Composable
fun MiniPlayer(app: AppState, modifier: Modifier = Modifier) {
    val playback by app.playback.collectAsState()
    val snapshot = playback.snapshot
    val track = snapshot.current ?: return
    val now = rememberNow(snapshot.isPlaying, 500)
    val progress = (playback.position(now).toFloat() / track.durationMs.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
    val accent = Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary))
    Column(
        modifier
            .fillMaxWidth()
            .clickable { app.navigator.openNowPlaying() }
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, drag -> if (drag < -12) app.navigator.openNowPlaying() }
            },
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 4.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ArtworkImage(
                app.images,
                track.art.small ?: track.art.medium,
                Modifier.size(46.dp).shadow(6.dp, RoundedCornerShape(12.dp)),
                shape = RoundedCornerShape(12.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                Text(
                    track.artistNames,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledIconButton(onClick = { app.core.togglePlay() }, modifier = Modifier.size(42.dp)) {
                Icon(
                    if (snapshot.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    stringResource(if (snapshot.isPlaying) R.string.pause else R.string.play),
                )
            }
            IconButton(onClick = { app.core.next() }, enabled = snapshot.hasNext) {
                Icon(Icons.Default.SkipNext, stringResource(R.string.next_track))
            }
        }
        // Progress as a glowing gradient line.
        Box(
            Modifier
                .padding(horizontal = 14.dp)
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
        ) {
            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(accent))
        }
    }
}

/** Full-screen Now Playing with Player, Queue and Lyrics tabs. */
@Composable
fun NowPlayingScreen(app: AppState) {
    val playback by app.playback.collectAsState()
    val track = playback.snapshot.current
    var dragged by remember { mutableFloatStateOf(0f) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) {
            ArtTintBackground(app.images, track?.art?.medium, Modifier.fillMaxSize(), strength = 0.55f)
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .draggable(
                            rememberDraggableState { dragged += it },
                            Orientation.Vertical,
                            onDragStopped = {
                                if (dragged > 120) app.navigator.nowPlayingOpen = false
                                dragged = 0f
                            },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { app.navigator.nowPlayingOpen = false }) {
                        Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.close))
                    }
                    PrimaryTabRow(
                        selectedTabIndex = app.navigator.nowPlayingTab.ordinal,
                        containerColor = Color.Transparent,
                        modifier = Modifier.weight(1f).padding(end = 48.dp),
                        divider = {},
                    ) {
                        NowPlayingTab.entries.forEach { tab ->
                            Tab(
                                selected = app.navigator.nowPlayingTab == tab,
                                onClick = { app.navigator.nowPlayingTab = tab },
                                text = {
                                    Text(
                                        stringResource(
                                            when (tab) {
                                                NowPlayingTab.Player -> R.string.now_playing
                                                NowPlayingTab.Queue -> R.string.queue
                                                NowPlayingTab.Lyrics -> R.string.lyrics
                                            },
                                        ),
                                        maxLines = 1,
                                    )
                                },
                            )
                        }
                    }
                }
                when (app.navigator.nowPlayingTab) {
                    NowPlayingTab.Player -> PlayerTab(app)
                    NowPlayingTab.Queue -> QueueTab(app)
                    NowPlayingTab.Lyrics -> LyricsTab(app)
                }
            }
        }
    }
}

@Composable
private fun PlayerTab(app: AppState) {
    val playback by app.playback.collectAsState()
    val settings by app.settings.collectAsState()
    val overrides by app.likedOverrides.collectAsState()
    val snapshot = playback.snapshot
    val track = snapshot.current
    if (track == null) {
        EmptyState(
            Icons.Default.MusicNote,
            stringResource(R.string.nothing_playing),
            Modifier.padding(top = 64.dp),
            stringResource(R.string.nothing_playing_description),
        )
        return
    }
    val now = rememberNow(snapshot.isPlaying)
    var seeking by remember { mutableStateOf<Float?>(null) }
    val duration = track.durationMs.toFloat().coerceAtLeast(1f)
    val position = seeking ?: playback.position(now).toFloat().coerceIn(0f, duration)
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        ArtworkImage(
            app.images,
            track.art.large ?: track.art.medium,
            Modifier.fillMaxWidth().widthIn(max = 420.dp).aspectRatio(1f),
            shape = MaterialTheme.shapes.large,
            priority = Priority.HERO,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        track.artistNames,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable {
                            track.artists.firstOrNull { it.id.isNotEmpty() }?.let { app.navigator.push(Route.Artist(it.id)) }
                        },
                    )
                    track.album?.let { album ->
                        Text(
                            album.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(enabled = album.id.isNotEmpty()) { app.navigator.push(Route.Album(album.id)) },
                        )
                    }
                }
                val liked = overrides[track.id] ?: track.saved ?: false
                IconButton(onClick = { app.setLiked(listOf(track), !liked) }) {
                    Icon(
                        if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        stringResource(if (liked) R.string.remove_from_liked else R.string.save_to_liked),
                        tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Slider(
                value = position,
                onValueChange = { seeking = it },
                onValueChangeFinished = {
                    seeking?.let { app.core.seek(it.toLong().toUInt()) }
                    seeking = null
                },
                valueRange = 0f..duration,
            )
            Row(Modifier.fillMaxWidth()) {
                Text(formatDuration(position.toLong()), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                Text(formatDuration(track.durationMs.toLong()), style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { app.core.toggleShuffle() }) {
                Icon(
                    Icons.Default.Shuffle,
                    stringResource(R.string.shuffle),
                    tint = if (snapshot.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { app.core.previous() }, Modifier.size(56.dp)) {
                Icon(Icons.Default.SkipPrevious, stringResource(R.string.previous_track), Modifier.size(36.dp))
            }
            FilledIconButton(
                onClick = { app.core.togglePlay() },
                modifier = Modifier.size(72.dp),
                colors = IconButtonDefaults.filledIconButtonColors(),
            ) {
                Icon(
                    if (snapshot.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    stringResource(if (snapshot.isPlaying) R.string.pause else R.string.play),
                    Modifier.size(40.dp),
                )
            }
            IconButton(onClick = { app.core.next() }, Modifier.size(56.dp), enabled = snapshot.hasNext) {
                Icon(Icons.Default.SkipNext, stringResource(R.string.next_track), Modifier.size(36.dp))
            }
            IconButton(onClick = { app.core.cycleRepeat() }) {
                Icon(
                    if (snapshot.repeat == Repeat.TRACK) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    stringResource(R.string.repeat),
                    tint = if (snapshot.repeat != Repeat.OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.VolumeDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            var volume by remember(snapshot.volume) { mutableFloatStateOf(snapshot.volume.toFloat()) }
            Slider(
                value = volume,
                onValueChange = { volume = it },
                onValueChangeFinished = { app.core.setVolume(volume.toDouble()) },
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (settings.featureDeviceSelector) {
                IconButton(onClick = { app.overlay = Overlay.Devices }) {
                    Icon(
                        Icons.Default.Devices,
                        stringResource(R.string.play_on),
                        tint = if (snapshot.deviceId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }
            IconButton(onClick = { app.navigator.nowPlayingTab = NowPlayingTab.Lyrics }) {
                Icon(Icons.Default.Lyrics, stringResource(R.string.lyrics))
            }
            IconButton(onClick = { app.navigator.nowPlayingTab = NowPlayingTab.Queue }) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.queue))
            }
            IconButton(onClick = { app.overlay = Overlay.TrackActions(listOf(track)) }) {
                Icon(Icons.Default.MoreVert, stringResource(R.string.more_actions))
            }
        }
    }
}

@Composable
private fun contextTitle(source: PlaySource?) = stringResource(
    when (source) {
        is PlaySource.Album -> R.string.next_from_album
        is PlaySource.Playlist -> R.string.next_from_playlist
        is PlaySource.Artist -> R.string.next_from_artist
        PlaySource.SavedTracks -> R.string.next_from_liked
        is PlaySource.Radio -> R.string.next_on_radio
        is PlaySource.Search -> R.string.next_from_search
        null -> R.string.next_up
    },
)

/** Now playing, "next in queue" and the rest of the context. */
@Composable
private fun QueueTab(app: AppState) {
    val queue by app.queue.collectAsState()
    val playback by app.playback.collectAsState()
    if (queue.current == null && queue.queued.isEmpty() && queue.context.isEmpty()) {
        EmptyState(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.queue_empty), Modifier.padding(top = 64.dp))
        return
    }
    val listState = rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        queue.current?.let { current ->
            item { QueueHeader(stringResource(R.string.now_playing)) }
            item(key = "current") { QueueRow(app, current, playback.snapshot.currentKey, movable = false) }
        }
        if (queue.queued.isNotEmpty()) {
            item {
                QueueHeader(stringResource(R.string.next_in_queue)) {
                    TextButton(onClick = { app.core.clearQueued() }) { Text(stringResource(R.string.clear)) }
                }
            }
            queueEntries(app, queue.queued, playback.snapshot.currentKey, "queued")
        }
        if (queue.context.isNotEmpty()) {
            item { QueueHeader(contextTitle(queue.source)) }
            queueEntries(app, queue.context, playback.snapshot.currentKey, "context")
        }
    }
}

@Composable
private fun QueueHeader(title: String, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        action()
    }
}

/** Swipe to remove, drag the handle to reorder within the section. */
private fun LazyListScope.queueEntries(app: AppState, entries: List<QueueEntry>, currentKey: String?, section: String) {
    itemsIndexed(entries, key = { _, entry -> "$section:${entry.key}" }) { index, entry ->
        val dismiss = rememberSwipeToDismissBoxState()
        LaunchedEffect(dismiss.currentValue) {
            if (dismiss.currentValue != SwipeToDismissBoxValue.Settled) app.core.removeQueueEntries(listOf(entry.key))
        }
        var offset by remember { mutableFloatStateOf(0f) }
        var height by remember { mutableFloatStateOf(1f) }
        SwipeToDismissBox(
            state = dismiss,
            backgroundContent = {
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 24.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) { Icon(Icons.Default.Delete, stringResource(R.string.remove_from_queue)) }
            },
            modifier = Modifier
                .zIndex(if (offset != 0f) 1f else 0f)
                .offset { IntOffset(0, offset.roundToInt()) }
                .onSizeChanged { height = it.height.toFloat().coerceAtLeast(1f) },
        ) {
            QueueRow(
                app,
                entry,
                currentKey,
                movable = true,
                handle = Modifier.pointerInput(entry.key) {
                    detectDragGesturesAfterLongPress(
                        onDrag = { change, amount ->
                            change.consume()
                            offset += amount.y
                        },
                        onDragEnd = {
                            val steps = (offset / height).roundToInt()
                            val target = (index + steps).coerceIn(0, entries.lastIndex)
                            offset = 0f
                            if (target != index) {
                                app.core.moveQueueEntry(entry.key, entries[target].key, target > index)
                            }
                        },
                        onDragCancel = { offset = 0f },
                    )
                },
            )
        }
    }
}

@Composable
private fun QueueRow(app: AppState, entry: QueueEntry, currentKey: String?, movable: Boolean, handle: Modifier = Modifier) {
    val current = entry.key == currentKey
    ListItem(
        headlineContent = {
            Text(
                entry.track.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        },
        supportingContent = { Text(entry.track.artistNames, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { ArtworkImage(app.images, entry.track.art.small, Modifier.size(44.dp), priority = Priority.OFFSCREEN) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { app.overlay = Overlay.TrackActions(listOf(entry.track)) }) {
                    Icon(Icons.Default.MoreVert, stringResource(R.string.more_actions))
                }
                if (movable) Icon(Icons.Default.DragHandle, stringResource(R.string.reorder), handle.padding(8.dp))
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.clickable { app.core.playQueueEntry(entry.key) },
    )
}

/** Synced lyrics that follow playback, or plain lyrics when unsynced. */
@Composable
private fun LyricsTab(app: AppState) {
    val playback by app.playback.collectAsState()
    val track = playback.snapshot.current
    var lyrics by remember { mutableStateOf<LyricsInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(track?.id) {
        lyrics = null
        error = null
        if (track == null) return@LaunchedEffect
        loading = true
        try {
            lyrics = app.core.lyrics(track.id)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = e.describe()
        }
        loading = false
    }
    val current = lyrics
    when {
        track == null -> EmptyState(Icons.Default.MusicNote, stringResource(R.string.nothing_playing), Modifier.padding(top = 64.dp))
        current != null && current.lines.isNotEmpty() -> SyncedLyrics(app, current)
        loading -> com.aidenir.riff.ui.components.LoadState(null) {}
        else -> EmptyState(
            Icons.Default.Lyrics,
            stringResource(R.string.no_lyrics),
            Modifier.padding(top = 64.dp),
            error ?: stringResource(R.string.no_lyrics_description),
        )
    }
}

@Composable
private fun SyncedLyrics(app: AppState, lyrics: LyricsInfo) {
    val playback by app.playback.collectAsState()
    val now = rememberNow(playback.snapshot.isPlaying)
    val position = playback.position(now)
    val active = if (lyrics.synced) lyrics.lines.indexOfLast { it.startMs.toLong() <= position }.takeIf { it >= 0 } else null
    val listState = rememberLazyListState()
    LaunchedEffect(active) {
        if (active != null) listState.animateScrollToItem((active - 2).coerceAtLeast(0))
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp), state = listState) {
        itemsIndexed(lyrics.lines) { index, line ->
            val color = when {
                !lyrics.synced || active == null -> MaterialTheme.colorScheme.onSurface
                index == active -> MaterialTheme.colorScheme.primary
                index < active -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            }
            Text(
                line.words.ifEmpty { "♪" },
                fontSize = 24.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.Bold,
                color = color,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .clickable(enabled = lyrics.synced) { app.core.seek(line.startMs) },
            )
        }
        lyrics.provider?.let { provider ->
            item {
                Text(
                    stringResource(R.string.lyrics_provided_by, provider),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
    }
}

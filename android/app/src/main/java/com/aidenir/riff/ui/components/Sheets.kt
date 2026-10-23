package com.aidenir.riff.ui.components

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aidenir.riff.R
import com.aidenir.riff.core.AppState
import com.aidenir.riff.core.CardItem
import com.aidenir.riff.core.Overlay
import com.aidenir.riff.core.artistNames
import com.aidenir.riff.ffi.DeviceInfo
import com.aidenir.riff.ffi.DeviceType
import com.aidenir.riff.ffi.PlaySource
import com.aidenir.riff.ui.Route
import kotlinx.coroutines.launch

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, null) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun SheetTitle(title: String, subtitle: String?, art: String?, app: AppState) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (art != null) ArtworkImage(app.images, art, Modifier.size(48.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    HorizontalDivider()
}

fun shareLink(context: android.content.Context, url: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Every overlay the app shows, driven by [AppState.overlay]. */
@Composable
fun Overlays(app: AppState) {
    when (val overlay = app.overlay) {
        is Overlay.TrackActions -> TrackActionsSheet(app, overlay)
        is Overlay.AddToPlaylist -> AddToPlaylistSheet(app, overlay)
        is Overlay.PlaylistEditor -> PlaylistEditorDialog(app, overlay)
        is Overlay.CardActions -> CardActionsSheet(app, overlay.item)
        Overlay.Devices -> DevicesSheet(app)
        null -> {}
    }
}

/** Actions for one or more tracks: the GTK track menu and the Mac context menu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackActionsSheet(app: AppState, request: Overlay.TrackActions) {
    val context = LocalContext.current
    val tracks = request.tracks
    val track = tracks.firstOrNull() ?: return
    val single = tracks.size == 1
    val overrides by app.likedOverrides.collectAsState()
    val liked = tracks.all { overrides[it.id] ?: it.saved ?: false }
    LaunchedEffect(Unit) { app.loadOwnedPlaylistsIfNeeded() }
    val owned by app.ownedPlaylists.collectAsState()
    fun done(action: () -> Unit) {
        app.overlay = null
        action()
    }
    ModalBottomSheet(onDismissRequest = { app.overlay = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            if (single) {
                SheetTitle(track.title, track.artistNames, track.art.small ?: track.art.medium, app)
                SheetAction(Icons.Default.PlayArrow, stringResource(R.string.play)) { done { app.play(listOf(track), null, track) } }
            } else {
                SheetTitle(pluralStringResource(R.plurals.track_count, tracks.size, tracks.size), null, null, app)
            }
            SheetAction(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.add_to_queue)) { done { app.enqueue(tracks) } }
            SheetAction(
                if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                stringResource(if (liked) R.string.remove_from_liked else R.string.save_to_liked),
            ) { done { app.setLiked(tracks, !liked) } }
            SheetAction(Icons.AutoMirrored.Filled.PlaylistAdd, stringResource(R.string.add_to_playlist)) {
                app.overlay = Overlay.AddToPlaylist(tracks)
            }
            val playlistId = request.playlistId
            if (playlistId != null && owned.any { it.id == playlistId }) {
                SheetAction(Icons.Default.RemoveCircleOutline, stringResource(R.string.remove_from_playlist)) {
                    done { app.launch { app.core.removeFromPlaylist(playlistId, tracks.map { it.id }) } }
                }
            }
            if (single) {
                track.album?.takeIf { it.id.isNotEmpty() }?.let { album ->
                    SheetAction(Icons.Default.Album, stringResource(R.string.go_to_album)) { done { app.navigator.push(Route.Album(album.id)) } }
                }
                track.artists.filter { it.id.isNotEmpty() }.forEach { artist ->
                    SheetAction(Icons.Default.Person, stringResource(R.string.go_to_artist, artist.name)) {
                        done { app.navigator.push(Route.Artist(artist.id)) }
                    }
                }
                SheetAction(Icons.Default.Radio, stringResource(R.string.start_radio)) { done { app.play(PlaySource.Radio(track.id)) } }
                val url = track.url ?: "https://open.spotify.com/track/${track.id}"
                SheetAction(Icons.Default.Share, stringResource(R.string.share)) { done { shareLink(context, url) } }
                SheetAction(Icons.Default.ContentCopy, stringResource(R.string.copy_link)) { done { app.copyLink(url) } }
            }
        }
    }
}

/** Play, shuffle, pin and share for an album, playlist or artist card. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardActionsSheet(app: AppState, item: CardItem) {
    val context = LocalContext.current
    val settings by app.settings.collectAsState()
    fun done(action: () -> Unit) {
        app.overlay = null
        action()
    }
    ModalBottomSheet(onDismissRequest = { app.overlay = null }) {
        Column(Modifier.navigationBarsPadding()) {
            SheetTitle(item.title, cardSubtitle(item), item.art.small ?: item.art.medium, app)
            SheetAction(Icons.Default.PlayArrow, stringResource(R.string.play)) { done { app.play(item.source) } }
            SheetAction(Icons.Default.Shuffle, stringResource(R.string.shuffle)) { done { app.play(item.source, shuffle = true) } }
            if (settings.featurePinnedObjects) {
                val pinned = settings.pinned.any { it.startsWith("${item.pin.kind.key}:${item.pin.id}") }
                SheetAction(Icons.Default.PushPin, stringResource(if (pinned) R.string.unpin else R.string.pin)) {
                    done { app.togglePin(item.pin) }
                }
            }
            SheetAction(Icons.Default.Share, stringResource(R.string.share)) { done { shareLink(context, item.url) } }
            SheetAction(Icons.Default.ContentCopy, stringResource(R.string.copy_link)) { done { app.copyLink(item.url) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddToPlaylistSheet(app: AppState, request: Overlay.AddToPlaylist) {
    LaunchedEffect(Unit) { app.loadOwnedPlaylistsIfNeeded() }
    val owned by app.ownedPlaylists.collectAsState()
    ModalBottomSheet(onDismissRequest = { app.overlay = null }) {
        Text(
            stringResource(R.string.add_to_playlist),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.navigationBarsPadding()) {
            item {
                SheetAction(Icons.Default.Add, stringResource(R.string.new_playlist)) {
                    app.overlay = Overlay.PlaylistEditor(null, request.tracks)
                }
            }
            items(owned, key = { it.id }) { playlist ->
                ListItem(
                    headlineContent = { Text(playlist.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { ArtworkImage(app.images, playlist.art.small ?: playlist.art.medium, Modifier.size(40.dp)) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.clickable {
                        app.overlay = null
                        app.add(request.tracks, playlist)
                    },
                )
            }
        }
    }
}

/** Spotify Connect device picker (feature flag `DeviceSelector`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevicesSheet(app: AppState) {
    val playback by app.playback.collectAsState()
    var devices by remember { mutableStateOf<List<DeviceInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    fun refresh() {
        loading = true
        scope.launch {
            devices = try {
                app.core.devices()
            } catch (e: Exception) {
                app.report(e)
                emptyList()
            }
            loading = false
        }
    }
    LaunchedEffect(Unit) { refresh() }
    val active = playback.snapshot.deviceId
    ModalBottomSheet(onDismissRequest = { app.overlay = null }) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                stringResource(R.string.play_on),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            DeviceRow(Icons.Default.PhoneAndroid, stringResource(R.string.this_device), active == null) {
                app.core.switchDevice(null)
                app.overlay = null
            }
            HorizontalDivider()
            devices.forEach { device ->
                DeviceRow(device.kind.icon, device.name, active == device.id) {
                    app.core.switchDevice(device.id)
                    app.overlay = null
                }
            }
            ListItem(
                headlineContent = { Text(stringResource(if (loading) R.string.looking_for_devices else R.string.refresh_devices)) },
                leadingContent = {
                    if (loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Refresh, null)
                },
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                modifier = Modifier.clickable(enabled = !loading) { refresh() },
            )
        }
    }
}

private val DeviceType.icon: ImageVector
    get() = when (this) {
        DeviceType.PHONE -> Icons.Default.PhoneAndroid
        DeviceType.COMPUTER -> Icons.Default.Computer
        DeviceType.SPEAKER -> Icons.Default.Speaker
        DeviceType.TV -> Icons.Default.Tv
        DeviceType.OTHER -> Icons.Default.Devices
    }

@Composable
private fun DeviceRow(icon: ImageVector, name: String, active: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(name, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        },
        leadingContent = { Icon(icon, null, tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

package com.aidenir.riff.ui.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aidenir.riff.R
import com.aidenir.riff.core.AppState
import com.aidenir.riff.core.CardItem
import com.aidenir.riff.core.Overlay
import com.aidenir.riff.core.Paged
import com.aidenir.riff.core.artistNames
import com.aidenir.riff.core.describe
import com.aidenir.riff.core.formatCount
import com.aidenir.riff.core.totalMinutes
import com.aidenir.riff.ffi.AlbumInfo
import com.aidenir.riff.ffi.ArtistInfo
import com.aidenir.riff.ffi.NamedLink
import com.aidenir.riff.ffi.PlaySource
import com.aidenir.riff.ffi.PlaylistInfo
import com.aidenir.riff.ffi.TrackInfo
import com.aidenir.riff.ffi.UserInfo
import com.aidenir.riff.ui.NavEntry
import com.aidenir.riff.ui.Route
import com.aidenir.riff.ui.components.ArtworkImage
import com.aidenir.riff.ui.components.CardView
import com.aidenir.riff.ui.components.DetailHeader
import com.aidenir.riff.ui.components.LoadState
import com.aidenir.riff.ui.components.PageScaffold
import com.aidenir.riff.ui.components.PlayButtons
import com.aidenir.riff.ui.components.SectionHeader
import com.aidenir.riff.ui.components.SelectionBar
import com.aidenir.riff.ui.components.TrackSelection
import com.aidenir.riff.ui.components.trackRows
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Loads a page's main record once, with a retry on failure. */
abstract class DetailModel<T>(protected val app: AppState, protected val scope: CoroutineScope) {
    var item by mutableStateOf<T?>(null)
    var error by mutableStateOf<String?>(null)
    private var loadedVersion = -1

    protected abstract suspend fun fetch(): T
    protected open fun afterLoad(item: T) {}

    fun load(version: Int = 0, force: Boolean = false) {
        if (!force && loadedVersion == version) return
        loadedVersion = version
        error = null
        scope.launch {
            try {
                val loaded = fetch()
                item = loaded
                afterLoad(loaded)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.describe()
            }
        }
    }
}

/** Artist names that open the artist page. */
@Composable
fun ArtistLinks(app: AppState, artists: List<NamedLink>) {
    androidx.compose.foundation.layout.FlowRow {
        artists.forEachIndexed { index, artist ->
            Text(
                artist.name + if (index < artists.lastIndex) ", " else "",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(enabled = artist.id.isNotEmpty()) { app.navigator.push(Route.Artist(artist.id)) },
            )
        }
    }
}

@Composable
private fun MoreButton(app: AppState, item: CardItem) {
    IconButton(onClick = { app.overlay = Overlay.CardActions(item) }) {
        Icon(Icons.Default.MoreVert, stringResource(R.string.more_actions))
    }
}

@Composable
private fun ToggleButton(saved: Boolean, savedLabel: String, unsavedLabel: String, onToggle: (Boolean) -> Unit) {
    if (saved) {
        FilledTonalButton(onClick = { onToggle(false) }) {
            Icon(Icons.Default.Check, null)
            Text(savedLabel, Modifier.padding(start = 4.dp))
        }
    } else {
        OutlinedButton(onClick = { onToggle(true) }) {
            Icon(Icons.Default.Add, null)
            Text(unsavedLabel, Modifier.padding(start = 4.dp))
        }
    }
}

// Album

class AlbumModel(app: AppState, scope: CoroutineScope, private val id: String) : DetailModel<AlbumInfo>(app, scope) {
    val tracks = Paged<TrackInfo>(scope) { offset, limit ->
        val page = app.core.albumTracks(id, offset, limit)
        page.items to page.total
    }
    val selection = TrackSelection()

    override suspend fun fetch() = app.core.album(id)
    override fun afterLoad(item: AlbumInfo) = tracks.reload()

    fun setSaved(saved: Boolean) {
        val album = item ?: return
        item = album.copy(saved = saved)
        scope.launch {
            try {
                app.core.setAlbumSaved(id, saved)
            } catch (e: Exception) {
                item = album
                app.report(e)
            }
        }
    }
}

@Composable
fun AlbumScreen(app: AppState, entry: NavEntry, id: String) {
    val model = entry.model("album") { AlbumModel(app, it, id) }
    LaunchedEffect(Unit) { model.load() }
    val album = model.item
    val tracks = model.tracks
    PageScaffold(
        app,
        title = album?.title ?: "",
        topBar = if (model.selection.active) ({ SelectionBar(app, model.selection, tracks.items) }) else null,
        actions = { if (album != null) MoreButton(app, CardItem.Album(album)) },
    ) {
        if (album == null) {
            LoadState(model.error) { model.load(force = true) }
            return@PageScaffold
        }
        val rowContext = rememberRowContext(app)
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                val details = buildList {
                    album.releaseDate?.let(::add)
                    album.totalTracks?.let { add(pluralStringResource(R.plurals.song_count, it.toInt(), it.toInt())) }
                    if (tracks.items.isNotEmpty()) add(stringResource(R.string.minutes, totalMinutes(tracks.items)))
                    album.label?.let(::add)
                }
                DetailHeader(
                    app,
                    kind = album.albumType,
                    title = album.title,
                    art = album.art,
                    subtitle = { ArtistLinks(app, album.artists) },
                    details = details,
                ) {
                    PlayButtons(app, PlaySource.Album(id))
                    ToggleButton(album.saved ?: false, stringResource(R.string.saved), stringResource(R.string.save), model::setSaved)
                }
            }
            trackRows(
                app,
                tracks.items,
                rowContext,
                PlaySource.Album(id),
                total = tracks.total,
                selection = model.selection,
                showArt = false,
                showNumbers = true,
                onLoadMore = tracks::loadMore,
            )
            album.copyright?.let { copyright ->
                item {
                    Text(
                        copyright,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

// Playlist

class PlaylistModel(app: AppState, scope: CoroutineScope, private val id: String) : DetailModel<PlaylistInfo>(app, scope) {
    val tracks = Paged<TrackInfo>(scope, pageSize = 100u) { offset, limit ->
        val page = app.core.playlistTracks(id, offset, limit)
        page.items to page.total
    }
    var suggestions by mutableStateOf(emptyList<TrackInfo>())
    val selection = TrackSelection()

    override suspend fun fetch() = app.core.playlist(id)

    override fun afterLoad(item: PlaylistInfo) {
        scope.launch {
            tracks.reloadNow()
            app.loadOwnedPlaylistsIfNeeded()
            if (app.canEdit(item)) {
                suggestions = runCatching { app.core.playlistSuggestions(id, tracks.items.map { it.id }, 10u) }
                    .getOrDefault(emptyList())
            }
        }
    }

    fun setFollowed(follow: Boolean) {
        val playlist = item ?: return
        item = playlist.copy(following = follow)
        scope.launch {
            try {
                app.core.setPlaylistFollowed(id, follow)
            } catch (e: Exception) {
                item = playlist
                app.report(e)
            }
        }
    }

    fun addSuggestion(track: TrackInfo) {
        suggestions = suggestions - track
        app.launch { app.core.addToPlaylist(id, listOf(track.id)) }
    }
}

@Composable
fun PlaylistScreen(app: AppState, entry: NavEntry, id: String) {
    val model = entry.model("playlist") { PlaylistModel(app, it, id) }
    val version by app.libraryVersion.collectAsState()
    LaunchedEffect(version) { model.load(version) }
    val playlist = model.item
    val tracks = model.tracks
    val user by app.user.collectAsState()
    PageScaffold(
        app,
        title = playlist?.title ?: "",
        topBar = if (model.selection.active) ({ SelectionBar(app, model.selection, tracks.items, id) }) else null,
        actions = { if (playlist != null) MoreButton(app, CardItem.Playlist(playlist)) },
    ) {
        if (playlist == null) {
            LoadState(model.error) { model.load(version, force = true) }
            return@PageScaffold
        }
        val editable = user != null && app.canEdit(playlist)
        val rowContext = rememberRowContext(app)
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                val total = tracks.total ?: playlist.totalTracks
                val details = buildList {
                    total?.let { add(pluralStringResource(R.plurals.song_count, it.toInt(), it.toInt())) }
                    if (tracks.items.isNotEmpty()) add(stringResource(R.string.minutes, totalMinutes(tracks.items)))
                    if (playlist.collaborative == true) add(stringResource(R.string.collaborative))
                    playlist.public?.let { add(stringResource(if (it) R.string.public_label else R.string.private_label)) }
                }
                DetailHeader(
                    app,
                    kind = stringResource(R.string.playlist),
                    title = playlist.title,
                    art = playlist.art,
                    subtitle = playlist.owner?.let { owner ->
                        {
                            Text(
                                owner.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { app.navigator.push(Route.User(owner.id)) },
                            )
                        }
                    },
                    details = details,
                    description = playlist.description,
                ) {
                    PlayButtons(app, PlaySource.Playlist(id))
                    if (editable) {
                        OutlinedButton(onClick = { app.overlay = Overlay.PlaylistEditor(playlist) }) {
                            Icon(Icons.Default.Edit, null)
                            Text(stringResource(R.string.edit), Modifier.padding(start = 4.dp))
                        }
                    } else {
                        ToggleButton(
                            playlist.following ?: false,
                            stringResource(R.string.following),
                            stringResource(R.string.follow),
                            model::setFollowed,
                        )
                    }
                }
            }
            if (!tracks.loaded) {
                item { LoadState(tracks.error, Modifier.size(200.dp), retry = tracks::reload) }
            }
            trackRows(
                app,
                tracks.items,
                rowContext,
                PlaySource.Playlist(id),
                total = tracks.total,
                selection = model.selection,
                playlistId = id,
                onLoadMore = tracks::loadMore,
            )
            if (editable && model.suggestions.isNotEmpty() && !tracks.hasMore) {
                item {
                    SectionHeader(stringResource(R.string.recommended_songs))
                    Text(
                        stringResource(R.string.based_on_playlist),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                items(model.suggestions, key = { "suggestion:${it.id}" }) { track ->
                    ListItem(
                        headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(track.artistNames, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { ArtworkImage(app.images, track.art.small, Modifier.size(48.dp)) },
                        trailingContent = {
                            TextButton(onClick = { model.addSuggestion(track) }) { Text(stringResource(R.string.add)) }
                        },
                        modifier = Modifier.clickable { app.play(listOf(track), null, track) },
                    )
                }
            }
        }
    }
}

// Artist

class ArtistModel(app: AppState, scope: CoroutineScope, private val id: String) : DetailModel<ArtistInfo>(app, scope) {
    var topTracks by mutableStateOf(emptyList<TrackInfo>())
    val albums = Paged<AlbumInfo>(scope) { offset, limit ->
        val page = app.core.artistAlbums(id, offset, limit)
        page.items to page.total
    }
    val selection = TrackSelection()

    override suspend fun fetch() = app.core.artist(id)

    override fun afterLoad(item: ArtistInfo) {
        scope.launch { topTracks = runCatching { app.core.artistTopTracks(id) }.getOrDefault(emptyList()) }
        albums.reload()
    }

    fun setFollowed(follow: Boolean) {
        val artist = item ?: return
        item = artist.copy(following = follow)
        scope.launch {
            try {
                app.core.setArtistFollowed(id, follow)
            } catch (e: Exception) {
                item = artist
                app.report(e)
            }
        }
    }
}

@Composable
fun ArtistScreen(app: AppState, entry: NavEntry, id: String) {
    val model = entry.model("artist") { ArtistModel(app, it, id) }
    LaunchedEffect(Unit) { model.load() }
    val artist = model.item
    PageScaffold(
        app,
        title = artist?.name ?: "",
        topBar = if (model.selection.active) ({ SelectionBar(app, model.selection, model.topTracks) }) else null,
        actions = { if (artist != null) MoreButton(app, CardItem.Artist(artist)) },
    ) {
        if (artist == null) {
            LoadState(model.error) { model.load(force = true) }
            return@PageScaffold
        }
        val rowContext = rememberRowContext(app)
        val groups = albumGroups(model.albums.items)
        LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                val details = buildList {
                    artist.followerCount?.let { add(stringResource(R.string.followers, formatCount(it))) }
                    if (artist.genres.isNotEmpty()) add(artist.genres.take(3).joinToString(", "))
                }
                DetailHeader(
                    app,
                    kind = stringResource(R.string.artist),
                    title = artist.name,
                    art = artist.art,
                    circle = true,
                    details = details,
                    description = artist.bio,
                ) {
                    PlayButtons(app, PlaySource.Artist(id))
                    ToggleButton(
                        artist.following ?: false,
                        stringResource(R.string.following),
                        stringResource(R.string.follow),
                        model::setFollowed,
                    )
                }
            }
            if (model.topTracks.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SectionHeader(stringResource(R.string.popular))
                }
                model.topTracks.take(10).forEachIndexed { index, track ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "top:$index:${track.id}") {
                        com.aidenir.riff.ui.components.TrackRow(
                            app = app,
                            track = track,
                            context = rowContext,
                            selecting = model.selection.active,
                            selected = index in model.selection.selected,
                            onClick = {
                                if (model.selection.active) model.selection.toggle(index)
                                else app.play(model.topTracks, PlaySource.Artist(id), track)
                            },
                            onLongClick = { model.selection.toggle(index) },
                            onMore = { app.overlay = Overlay.TrackActions(listOf(track)) },
                        )
                    }
                }
            }
            groups.forEach { (kind, albums) ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "group:$kind") {
                    SectionHeader(groupTitle(kind))
                }
                itemsIndexed(albums, key = { _, album -> "album:${album.id}" }) { index, album ->
                    if (album == model.albums.items.lastOrNull()) LaunchedEffect(model.albums.items.size) { model.albums.loadMore() }
                    CardView(app, CardItem.Album(album), Modifier.padding(horizontal = 4.dp))
                }
            }
        }
    }
}

private val albumOrder = listOf("Album", "Single", "EP", "Compilation")

private fun albumGroups(albums: List<AlbumInfo>): List<Pair<String, List<AlbumInfo>>> {
    val grouped = albums.groupBy { it.albumType }
    val known = albumOrder.mapNotNull { kind -> grouped[kind]?.let { kind to it } }
    val other = grouped.filterKeys { it !in albumOrder }.toSortedMap().map { it.key to it.value }
    return known + other
}

@Composable
private fun groupTitle(kind: String) = when (kind) {
    "Album" -> stringResource(R.string.albums)
    "Single" -> stringResource(R.string.singles)
    "EP" -> stringResource(R.string.eps)
    "Compilation" -> stringResource(R.string.compilations)
    else -> kind
}

// Profile

class UserModel(app: AppState, scope: CoroutineScope, private val id: String) : DetailModel<UserInfo>(app, scope) {
    val playlists = Paged<PlaylistInfo>(scope) { offset, limit ->
        val page = app.core.userPlaylists(id, offset, limit)
        page.items to page.total
    }

    override suspend fun fetch() = app.core.user(id)
    override fun afterLoad(item: UserInfo) = playlists.reload()
}

@Composable
fun UserScreen(app: AppState, entry: NavEntry, id: String) {
    val model = entry.model("user") { UserModel(app, it, id) }
    LaunchedEffect(Unit) { model.load() }
    val user = model.item
    val me by app.user.collectAsState()
    PageScaffold(app, title = user?.displayName ?: "") {
        if (user == null) {
            LoadState(model.error) { model.load(force = true) }
            return@PageScaffold
        }
        val items = model.playlists.items
        LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                DetailHeader(
                    app,
                    kind = stringResource(R.string.profile),
                    title = user.displayName,
                    art = user.art,
                    circle = true,
                    details = listOfNotNull(user.followerCount?.let { stringResource(R.string.followers, formatCount(it)) }),
                ) {
                    if (me?.id == user.id) {
                        OutlinedButton(onClick = { app.navigator.push(Route.Settings) }) { Text(stringResource(R.string.settings)) }
                    }
                }
            }
            if (items.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader(stringResource(R.string.public_playlists)) }
            }
            itemsIndexed(items, key = { _, p -> p.id }) { index, playlist ->
                if (index >= items.size - 6) LaunchedEffect(items.size) { model.playlists.loadMore() }
                CardView(app, CardItem.Playlist(playlist), Modifier.padding(horizontal = 4.dp))
            }
        }
    }
}

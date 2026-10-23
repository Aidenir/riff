package com.aidenir.riff.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aidenir.riff.R
import com.aidenir.riff.core.AppState
import com.aidenir.riff.core.CardItem
import com.aidenir.riff.core.LibraryNode
import com.aidenir.riff.core.Overlay
import com.aidenir.riff.core.Paged
import com.aidenir.riff.core.PinnedItem
import com.aidenir.riff.core.SortOrder
import com.aidenir.riff.core.artistNames
import com.aidenir.riff.core.describe
import com.aidenir.riff.ffi.AlbumInfo
import com.aidenir.riff.ffi.ArtistInfo
import com.aidenir.riff.ffi.PlaySource
import com.aidenir.riff.ffi.TrackInfo
import com.aidenir.riff.ui.LibrarySection
import com.aidenir.riff.ui.NavEntry
import com.aidenir.riff.ui.Route
import com.aidenir.riff.ui.components.ArtworkImage
import com.aidenir.riff.ui.components.CardView
import com.aidenir.riff.ui.components.EmptyState
import com.aidenir.riff.ui.components.LoadState
import com.aidenir.riff.ui.components.PageScaffold
import com.aidenir.riff.ui.components.PlayButtons
import com.aidenir.riff.ui.components.SelectionBar
import com.aidenir.riff.ui.components.TrackSelection
import com.aidenir.riff.ui.components.cardCreator
import com.aidenir.riff.ui.components.trackRows
import com.aidenir.riff.ui.components.rememberLoaderVisible
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Followed artists, paged with a cursor. */
class ArtistsLoader(private val app: AppState, private val scope: CoroutineScope) {
    var items by mutableStateOf(emptyList<ArtistInfo>())
    var loaded by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    private var cursor: String? = null
    private var done = false
    private var loading = false

    fun reload() {
        items = emptyList()
        cursor = null
        done = false
        loaded = false
        error = null
        loadMore()
    }

    fun loadMore() {
        if (done || loading) return
        loading = true
        scope.launch {
            try {
                val page = app.core.followedArtists(cursor, 50u)
                items = items + page.items
                cursor = page.nextCursor
                done = page.nextCursor == null || page.items.isEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.describe()
                done = true
            } finally {
                loading = false
                loaded = true
            }
        }
    }
}

class LibraryModel(private val app: AppState, scope: CoroutineScope) {
    val albums = Paged<AlbumInfo>(scope) { offset, limit ->
        val page = app.core.savedAlbums(offset, limit)
        page.items to page.total
    }
    val tracks = Paged<TrackInfo>(scope) { offset, limit ->
        val page = app.core.savedTracks(offset, limit)
        page.items to page.total
    }
    val artists = ArtistsLoader(app, scope)
    val selection = TrackSelection()
    var filter by mutableStateOf("")
    var filtering by mutableStateOf(false)
    private val loadedAt = mutableMapOf<LibrarySection, Int>()

    fun load(section: LibrarySection, version: Int, force: Boolean = false) {
        if (!force && loadedAt[section] == version) return
        loadedAt[section] = version
        when (section) {
            LibrarySection.Albums -> albums.reload()
            LibrarySection.Playlists -> if (force) app.playlistLibrary.reload() else app.playlistLibrary.loadIfNeeded()
            LibrarySection.LikedSongs -> tracks.reload()
            LibrarySection.Artists -> artists.reload()
        }
    }
}

@Composable
fun LibraryScreen(app: AppState, entry: NavEntry) {
    val model = entry.model("library") { LibraryModel(app, it) }
    val navigator = app.navigator
    val section = navigator.librarySection
    val version by app.libraryVersion.collectAsState()
    val settings by app.settings.collectAsState()
    LaunchedEffect(section, version) { model.load(section, version) }
    val selection = model.selection
    val filteredTracks = filterTracks(model.tracks.items, model.filter)

    PageScaffold(
        app,
        title = stringResource(R.string.library),
        topBar = if (selection.active) ({ SelectionBar(app, selection, filteredTracks) }) else null,
        actions = {
            IconButton(onClick = {
                model.filtering = !model.filtering
                if (!model.filtering) model.filter = ""
            }) { Icon(Icons.Default.FilterList, stringResource(R.string.filter)) }
            if (section != LibrarySection.LikedSongs) {
                SortMenu(
                    current = when (section) {
                        LibrarySection.Albums -> settings.sortAlbums
                        LibrarySection.Playlists -> settings.sortPlaylists
                        else -> settings.sortArtists
                    },
                    onSelect = { key ->
                        app.updateSettings {
                            when (section) {
                                LibrarySection.Albums -> it.copy(sortAlbums = key)
                                LibrarySection.Playlists -> it.copy(sortPlaylists = key)
                                else -> it.copy(sortArtists = key)
                            }
                        }
                    },
                )
            }
            if (section == LibrarySection.Playlists) {
                IconButton(onClick = { app.overlay = Overlay.PlaylistEditor(null) }) {
                    Icon(Icons.Default.Add, stringResource(R.string.new_playlist))
                }
            }
        },
    ) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(LibrarySection.entries) { item ->
                FilterChip(
                    selected = section == item,
                    onClick = {
                        selection.clear()
                        navigator.librarySection = item
                    },
                    label = { Text(sectionTitle(item)) },
                )
            }
        }
        if (model.filtering) {
            OutlinedTextField(
                model.filter,
                { model.filter = it },
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text(stringResource(R.string.filter)) },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { model.filter = "" }) { Icon(Icons.Default.Clear, stringResource(R.string.clear)) }
                },
            )
        }
        if (settings.featurePinnedObjects) PinnedRow(app, settings.pinned.mapNotNull(PinnedItem::decode))
        PullToRefreshBox(
            isRefreshing = false,
            onRefresh = { model.load(section, version, force = true) },
            modifier = Modifier.fillMaxSize(),
        ) {
            when (section) {
                LibrarySection.Albums -> {
                    val items = model.albums.items.map(CardItem::Album)
                    CardGridPage(
                        app,
                        sortAndFilter(items, settings.sortAlbums, model.filter),
                        loaded = model.albums.loaded,
                        error = model.albums.error,
                        retry = model.albums::reload,
                        loadMore = model.albums::loadMore,
                    )
                }
                LibrarySection.Artists -> {
                    val items = model.artists.items.map(CardItem::Artist)
                    CardGridPage(
                        app,
                        sortAndFilter(items, settings.sortArtists, model.filter),
                        loaded = model.artists.loaded,
                        error = model.artists.error,
                        retry = model.artists::reload,
                        loadMore = model.artists::loadMore,
                    )
                }
                LibrarySection.Playlists -> PlaylistsList(app, null, settings.sortPlaylists, model.filter)
                LibrarySection.LikedSongs -> LikedSongs(app, model, filteredTracks)
            }
        }
    }
}

private fun filterTracks(tracks: List<TrackInfo>, filter: String): List<TrackInfo> {
    if (filter.isBlank()) return tracks
    return tracks.filter {
        it.title.contains(filter, true) || it.artistNames.contains(filter, true) || it.album?.name?.contains(filter, true) == true
    }
}

private fun <T : CardItem> sortAndFilter(items: List<T>, sort: String, filter: String): List<T> {
    val sorted = SortOrder.of(sort).sort(items, ::cardCreator)
    if (filter.isBlank()) return sorted
    return sorted.filter { it.title.contains(filter, true) || cardCreator(it).contains(filter, true) }
}

@Composable
private fun sectionTitle(section: LibrarySection) = stringResource(
    when (section) {
        LibrarySection.Albums -> R.string.albums
        LibrarySection.Playlists -> R.string.playlists
        LibrarySection.LikedSongs -> R.string.liked_songs
        LibrarySection.Artists -> R.string.artists
    },
)

@Composable
private fun SortMenu(current: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.sort)) }
        DropdownMenu(open, { open = false }) {
            SortOrder.entries.forEach { order ->
                DropdownMenuItem(
                    text = { Text(sortTitle(order)) },
                    leadingIcon = { RadioButton(selected = order.key == current, onClick = null) },
                    onClick = {
                        open = false
                        onSelect(order.key)
                    },
                )
            }
        }
    }
}

@Composable
private fun sortTitle(order: SortOrder) = stringResource(
    when (order) {
        SortOrder.RecentlyAdded -> R.string.sort_recently_added
        SortOrder.Alphabetic -> R.string.sort_alphabetical
        SortOrder.Creator -> R.string.sort_creator
        SortOrder.DateReleased -> R.string.sort_release_date
        SortOrder.Popularity -> R.string.sort_popularity
    },
)

/** Pinned albums, playlists and artists (feature flag `PinnedObjects`). */
@Composable
private fun PinnedRow(app: AppState, pinned: List<PinnedItem>) {
    if (pinned.isEmpty()) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(pinned, key = { it.encoded }) { item ->
            AssistChip(
                onClick = {
                    app.navigator.push(
                        when (item.kind) {
                            PinnedItem.Kind.Playlist -> Route.Playlist(item.id)
                            PinnedItem.Kind.Album -> Route.Album(item.id)
                            PinnedItem.Kind.Artist -> Route.Artist(item.id)
                        },
                    )
                },
                label = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Default.PushPin, null, Modifier.size(16.dp)) },
            )
        }
    }
}

@Composable
private fun CardGridPage(
    app: AppState,
    items: List<CardItem>,
    loaded: Boolean,
    error: String?,
    retry: () -> Unit,
    loadMore: () -> Unit,
) {
    val holding = rememberLoaderVisible(!loaded)
    when {
        holding || (error != null && items.isEmpty()) -> LoadState(error, retry = retry)
        items.isEmpty() -> EmptyState(Icons.Default.Album, stringResource(R.string.nothing_here_yet))
        else -> LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp)) {
            itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                if (index >= items.size - 6) LaunchedEffect(items.size) { loadMore() }
                CardView(app, item)
            }
        }
    }
}

/** Playlists in their folders; [folderId] is null for the top level. */
@Composable
fun PlaylistsList(app: AppState, folderId: String?, sort: String, filter: String) {
    val library = app.playlistLibrary
    LaunchedEffect(Unit) { library.loadIfNeeded() }
    if (rememberLoaderVisible(!library.loaded) || !library.loaded) {
        LoadState(library.error, retry = library::reload)
        return
    }
    // A filter or a sort order shows every playlist without folders.
    val flat = filter.isNotBlank() || SortOrder.of(sort) != SortOrder.RecentlyAdded
    val nodes: List<LibraryNode> = if (flat) {
        val cards = library.ordered.map(CardItem::Playlist)
        sortAndFilter(cards, sort, filter).map { LibraryNode(it.playlist.id, it.playlist.title, false) }
    } else if (folderId == null) {
        library.tree
    } else {
        LibraryNode.find(library.tree, folderId)?.children.orEmpty()
    }
    if (nodes.isEmpty()) {
        EmptyState(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.nothing_here_yet))
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(nodes, key = { it.id }) { node ->
            if (node.isFolder) {
                ListItem(
                    headlineContent = { Text(node.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(pluralStringResource(R.plurals.playlist_count, node.children.size, node.children.size))
                    },
                    leadingContent = {
                        Box(
                            Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Default.Folder, null) }
                    },
                    modifier = Modifier.clickable { app.navigator.push(Route.Folder(node.id, node.name)) },
                )
            } else {
                val playlist = library.playlists[node.id]
                ListItem(
                    headlineContent = { Text(node.name.ifEmpty { playlist?.title ?: "" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = playlist?.owner?.let { { Text(it.name, maxLines = 1) } },
                    leadingContent = {
                        ArtworkImage(
                            app.images,
                            playlist?.art?.small ?: playlist?.art?.medium,
                            Modifier.size(56.dp),
                            placeholder = Icons.AutoMirrored.Filled.QueueMusic,
                        )
                    },
                    modifier = Modifier.clickable { app.navigator.push(Route.Playlist(node.id)) },
                )
            }
        }
    }
}

@Composable
private fun LikedSongs(app: AppState, model: LibraryModel, tracks: List<TrackInfo>) {
    val paged = model.tracks
    if (rememberLoaderVisible(!paged.loaded) || (paged.error != null && paged.items.isEmpty())) {
        LoadState(paged.error, retry = paged::reload)
        return
    }
    val rowContext = rememberRowContext(app)
    val filtered = model.filter.isNotBlank()
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier
                        .size(72.dp)
                        .background(
                            Brush.linearGradient(listOf(MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.primary)),
                            MaterialTheme.shapes.medium,
                        ),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Favorite, null, Modifier.size(36.dp), tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.liked_songs), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    val count = (paged.total ?: paged.items.size.toUInt()).toInt()
                    Text(pluralStringResource(R.plurals.song_count, count, count), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayButtons(app, PlaySource.SavedTracks)
            }
        }
        trackRows(
            app,
            tracks,
            rowContext,
            if (filtered) null else PlaySource.SavedTracks,
            total = if (filtered) null else paged.total,
            selection = model.selection,
            onLoadMore = paged::loadMore,
        )
        if (tracks.isEmpty()) {
            item { EmptyState(Icons.Default.Favorite, stringResource(R.string.nothing_here_yet)) }
        }
    }
}

/** A playlist folder opened from the library. */
@Composable
fun FolderScreen(app: AppState, route: Route.Folder) {
    PageScaffold(app, title = route.name) {
        PlaylistsList(app, route.id, SortOrder.RecentlyAdded.key, "")
    }
}

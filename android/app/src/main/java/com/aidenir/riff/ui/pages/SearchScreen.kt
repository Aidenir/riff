package com.aidenir.riff.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.aidenir.riff.R
import com.aidenir.riff.core.AppState
import com.aidenir.riff.core.CardItem
import com.aidenir.riff.core.Paged
import com.aidenir.riff.core.describe
import com.aidenir.riff.ffi.PlaySource
import com.aidenir.riff.ffi.SearchResult
import com.aidenir.riff.ffi.SearchScope
import com.aidenir.riff.ffi.TrackInfo
import com.aidenir.riff.ui.NavEntry
import com.aidenir.riff.ui.components.CardShelf
import com.aidenir.riff.ui.components.CardView
import com.aidenir.riff.ui.components.EmptyState
import com.aidenir.riff.ui.components.LoadState
import com.aidenir.riff.ui.components.PageScaffold
import com.aidenir.riff.ui.components.SectionHeader
import com.aidenir.riff.ui.components.SelectionBar
import com.aidenir.riff.ui.components.TrackSelection
import com.aidenir.riff.ui.components.trackRows
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Search scopes, like the GTK search page's scope buttons. */
enum class SearchKind { All, Songs, Albums, Artists, Playlists }

class SearchModel(private val app: AppState, private val scope: CoroutineScope) {
    var query by mutableStateOf("")
        private set
    var kind by mutableStateOf(SearchKind.All)
        private set
    var results by mutableStateOf<SearchResult?>(null)
    var tracks by mutableStateOf<Paged<TrackInfo>?>(null)
    var cards by mutableStateOf<Paged<CardItem>?>(null)
    var error by mutableStateOf<String?>(null)
    var searching by mutableStateOf(false)
    val selection = TrackSelection()
    private var job: Job? = null

    fun updateQuery(text: String) {
        query = text
        search(debounce = true)
    }

    fun selectKind(kind: SearchKind) {
        this.kind = kind
        search(debounce = false)
    }

    fun search(debounce: Boolean) {
        job?.cancel()
        val text = query.trim()
        if (text.isEmpty()) {
            results = null
            tracks = null
            cards = null
            error = null
            return
        }
        job = scope.launch {
            // Pasted links open the page instead of searching.
            if (app.open(text)) {
                query = ""
                return@launch
            }
            if (debounce) delay(300)
            app.core.bumpEpoch()
            error = null
            searching = true
            val core = app.core
            try {
                when (kind) {
                    SearchKind.All -> results = core.search(text, 20u)
                    SearchKind.Songs -> {
                        val paged = Paged(scope) { offset, limit ->
                            val r = core.searchScoped(text, SearchScope.TRACKS, offset, limit)
                            r.tracks to r.totalTracks
                        }
                        paged.reloadNow()
                        tracks = paged
                    }
                    else -> {
                        val scoped = when (kind) {
                            SearchKind.Albums -> SearchScope.ALBUMS
                            SearchKind.Artists -> SearchScope.ARTISTS
                            else -> SearchScope.PLAYLISTS
                        }
                        val paged = Paged(scope) { offset, limit ->
                            val r = core.searchScoped(text, scoped, offset, limit)
                            when (scoped) {
                                SearchScope.ALBUMS -> r.albums.map(CardItem::Album) to r.totalAlbums
                                SearchScope.ARTISTS -> r.artists.map(CardItem::Artist) to r.totalArtists
                                else -> r.playlists.map(CardItem::Playlist) to r.totalPlaylists
                            }
                        }
                        paged.reloadNow()
                        cards = paged
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.describe()
            } finally {
                searching = false
            }
        }
    }
}

@Composable
fun SearchScreen(app: AppState, entry: NavEntry) {
    val model = entry.model("search") { SearchModel(app, it) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val selection = model.selection
    val shownTracks = when (model.kind) {
        SearchKind.All -> model.results?.tracks?.take(8).orEmpty()
        SearchKind.Songs -> model.tracks?.items.orEmpty()
        else -> emptyList()
    }
    PageScaffold(
        app,
        title = stringResource(R.string.search),
        topBar = if (selection.active) ({ SelectionBar(app, selection, shownTracks) }) else null,
    ) {
        OutlinedTextField(
            value = model.query,
            onValueChange = model::updateQuery,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus),
            placeholder = { Text(stringResource(R.string.search_placeholder)) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (model.query.isNotEmpty()) {
                    IconButton(onClick = { model.updateQuery("") }) { Icon(Icons.Default.Clear, stringResource(R.string.clear)) }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(SearchKind.entries) { kind ->
                FilterChip(
                    selected = model.kind == kind,
                    onClick = { model.selectKind(kind) },
                    label = { Text(kindTitle(kind)) },
                )
            }
        }
        val rowContext = rememberRowContext(app)
        when {
            model.query.isBlank() -> EmptyState(
                Icons.Default.Search,
                stringResource(R.string.search_spotify),
                description = stringResource(R.string.search_description),
            )
            model.error != null -> LoadState(model.error) { model.search(debounce = false) }
            model.kind == SearchKind.All -> {
                val results = model.results
                if (results == null) {
                    LoadState(null) {}
                } else if (results.tracks.isEmpty() && results.albums.isEmpty() && results.artists.isEmpty() && results.playlists.isEmpty()) {
                    EmptyState(Icons.Default.Search, stringResource(R.string.no_results, model.query))
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        if (results.tracks.isNotEmpty()) {
                            item {
                                SectionHeader(stringResource(R.string.songs), action = stringResource(R.string.show_all)) {
                                    model.selectKind(SearchKind.Songs)
                                }
                            }
                            trackRows(app, results.tracks.take(8), rowContext, PlaySource.Search(model.query), selection = selection)
                        }
                        item { CardShelf(app, stringResource(R.string.artists), results.artists.map(CardItem::Artist)) }
                        item { CardShelf(app, stringResource(R.string.albums), results.albums.map(CardItem::Album)) }
                        item { CardShelf(app, stringResource(R.string.playlists), results.playlists.map(CardItem::Playlist)) }
                    }
                }
            }
            model.kind == SearchKind.Songs -> {
                val paged = model.tracks
                if (paged == null || !paged.loaded) {
                    LoadState(paged?.error) { model.search(debounce = false) }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        trackRows(
                            app,
                            paged.items,
                            rowContext,
                            PlaySource.Search(model.query),
                            selection = selection,
                            onLoadMore = paged::loadMore,
                        )
                    }
                }
            }
            else -> {
                val paged = model.cards
                if (paged == null || !paged.loaded) {
                    LoadState(paged?.error) { model.search(debounce = false) }
                } else {
                    LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp)) {
                        itemsIndexed(paged.items, key = { _, item -> item.id }) { index, item ->
                            if (index >= paged.items.size - 6) LaunchedEffect(paged.items.size) { paged.loadMore() }
                            CardView(app, item)
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { if (model.query.isEmpty()) focus.requestFocus() }
}

@Composable
private fun kindTitle(kind: SearchKind) = stringResource(
    when (kind) {
        SearchKind.All -> R.string.all
        SearchKind.Songs -> R.string.songs
        SearchKind.Albums -> R.string.albums
        SearchKind.Artists -> R.string.artists
        SearchKind.Playlists -> R.string.playlists
    },
)

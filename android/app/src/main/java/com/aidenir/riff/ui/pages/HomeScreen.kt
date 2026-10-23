package com.aidenir.riff.ui.pages

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aidenir.riff.R
import com.aidenir.riff.core.AppState
import com.aidenir.riff.core.CardItem
import com.aidenir.riff.core.DayPart
import com.aidenir.riff.core.Mood
import com.aidenir.riff.ffi.PlaylistInfo
import com.aidenir.riff.ffi.RecentItem
import com.aidenir.riff.ffi.RiffCore
import com.aidenir.riff.ui.NavEntry
import com.aidenir.riff.ui.Route
import com.aidenir.riff.ui.components.ArtworkImage
import com.aidenir.riff.ui.components.CardShelf
import com.aidenir.riff.ui.components.EqualizerLoader
import com.aidenir.riff.ui.components.PageScaffold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.Locale

/** Shelves for the home page, loaded together and kept for half an hour. */
class HomeModel(private val app: AppState, private val scope: CoroutineScope) {
    var recent by mutableStateOf(emptyList<CardItem>())
    var topArtists by mutableStateOf(emptyList<CardItem>())
    var moment by mutableStateOf(emptyList<CardItem>())
    var season by mutableStateOf(emptyList<CardItem>())
    var charts by mutableStateOf(emptyList<CardItem>())
    var loading by mutableStateOf(false)
    private var loadedAt = 0L

    fun loadIfStale(momentMood: Mood, seasonMood: Mood, country: String?) {
        if (System.currentTimeMillis() - loadedAt < STALE_AFTER) return
        load(momentMood, seasonMood, country)
    }

    fun load(momentMood: Mood, seasonMood: Mood, country: String?) {
        loadedAt = System.currentTimeMillis()
        loading = true
        val core = app.core
        scope.launch {
            val recentItems = async { runCatching { core.recentContexts(SHELF) }.getOrDefault(emptyList()) }
            val artists = async { runCatching { core.topArtists(SHELF) }.getOrDefault(emptyList()) }
            val momentLists = async { playlists(core, momentMood.query) }
            val seasonLists = async { playlists(core, seasonMood.query) }
            val chartLists = async { charts(core, country) }
            recent = recentItems.await().map {
                when (it) {
                    is RecentItem.Album -> CardItem.Album(it.album)
                    is RecentItem.Playlist -> CardItem.Playlist(it.playlist)
                    is RecentItem.Artist -> CardItem.Artist(it.artist)
                }
            }
            topArtists = artists.await().map(CardItem::Artist)
            moment = momentLists.await().map(CardItem::Playlist)
            season = seasonLists.await().map(CardItem::Playlist)
            charts = chartLists.await().map(CardItem::Playlist)
            loading = false
        }
    }

    companion object {
        private const val SHELF = 10u
        private const val STALE_AFTER = 30 * 60 * 1000L

        /** Playlists for a query, preferring the ones Spotify made. */
        suspend fun playlists(core: RiffCore, query: String): List<PlaylistInfo> {
            val found = runCatching { core.findPlaylists(query, SHELF * 2u) }.getOrDefault(emptyList())
            val (bySpotify, others) = found.partition { it.owner?.id == "spotify" }
            return (bySpotify + others).take(SHELF.toInt())
        }

        suspend fun charts(core: RiffCore, country: String?): List<PlaylistInfo> {
            val lists = runCatching { core.categoryPlaylists("toplists", SHELF) }.getOrDefault(emptyList())
            if (lists.isNotEmpty()) return lists
            val queries = mutableListOf<String>()
            if (country != null) {
                val name = Locale("", country).getDisplayCountry(Locale.US)
                if (name.isNotEmpty()) queries += listOf("Top 50 $name", "Viral 50 $name", "Top Songs $name")
            }
            queries += listOf("Top 50 Global", "Viral 50 Global", "Today's Top Hits")
            val found = mutableListOf<PlaylistInfo>()
            for (query in queries) {
                val candidates = playlists(core, query)
                val best = candidates.firstOrNull { it.title.equals(query, ignoreCase = true) } ?: candidates.firstOrNull()
                if (best != null && found.none { it.id == best.id }) found += best
            }
            return found
        }
    }
}

@Composable
fun HomeScreen(app: AppState, entry: NavEntry) {
    val model = entry.model("home") { HomeModel(app, it) }
    val user by app.user.collectAsState()
    val now = Calendar.getInstance()
    val part = DayPart.of(now.get(Calendar.HOUR_OF_DAY))
    val momentMood = Mood.moment(now.get(Calendar.DAY_OF_WEEK), part)
    val seasonMood = Mood.season(now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH), user?.country)
    LaunchedEffect(user?.country) { model.loadIfStale(momentMood, seasonMood, user?.country) }

    PageScaffold(
        app,
        title = stringResource(R.string.home),
        actions = {
            IconButton(onClick = { app.navigator.push(Route.Settings) }) {
                Icon(Icons.Default.Settings, stringResource(R.string.settings))
            }
            val profile = user
            IconButton(onClick = { profile?.let { app.navigator.push(Route.User(it.id)) } }) {
                if (profile?.art?.small != null) {
                    ArtworkImage(app.images, profile.art.small, Modifier.size(28.dp), shape = CircleShape)
                } else {
                    Icon(Icons.Default.Person, stringResource(R.string.profile))
                }
            }
        },
    ) {
        PullToRefreshBox(
            isRefreshing = model.loading,
            onRefresh = { model.load(momentMood, seasonMood, user?.country) },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Text(
                        greeting(part),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                item { CardShelf(app, stringResource(R.string.recently_played), model.recent) }
                item { CardShelf(app, stringResource(R.string.your_top_artists), model.topArtists) }
                item { CardShelf(app, moodTitle(momentMood), model.moment) }
                item { CardShelf(app, moodTitle(seasonMood), model.season) }
                item { CardShelf(app, chartsTitle(user?.country), model.charts) }
                if (model.loading && model.recent.isEmpty() && model.charts.isEmpty()) {
                    item { EqualizerLoader(Modifier.padding(32.dp)) }
                }
            }
        }
    }
}

@Composable
private fun greeting(part: DayPart): String = stringResource(
    when (part) {
        DayPart.Morning -> R.string.good_morning
        DayPart.Afternoon -> R.string.good_afternoon
        DayPart.Evening, DayPart.Night -> R.string.good_evening
    },
)

@Composable
private fun moodTitle(mood: Mood): String = when (mood) {
    is Mood.Moment -> {
        val day = DateFormatSymbols.getInstance().weekdays[mood.weekday]
        val part = stringResource(
            when (mood.part) {
                DayPart.Morning -> R.string.morning
                DayPart.Afternoon -> R.string.afternoon
                DayPart.Evening -> R.string.evening
                DayPart.Night -> R.string.night
            },
        )
        stringResource(R.string.for_your_moment, day, part)
    }
    is Mood.Season -> stringResource(
        when (mood.kind) {
            Mood.Season.Kind.Halloween -> R.string.halloween_picks
            Mood.Season.Kind.Christmas -> R.string.christmas_songs
            Mood.Season.Kind.Spring -> R.string.spring_vibes
            Mood.Season.Kind.Summer -> R.string.summer_hits
            Mood.Season.Kind.Autumn -> R.string.autumn_vibes
            Mood.Season.Kind.Winter -> R.string.cozy_winter
        },
    )
}

@Composable
private fun chartsTitle(country: String?): String {
    val name = country?.let { Locale("", it).displayCountry }?.takeIf { it.isNotEmpty() }
    return if (name != null) stringResource(R.string.popular_in, name) else stringResource(R.string.charts)
}


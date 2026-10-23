package com.aidenir.riff.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

/** The bottom bar sections. */
enum class Tab { Home, Search, Library }

/** Library chips. */
enum class LibrarySection { Playlists, Albums, LikedSongs, Artists }

/** Now Playing tabs. */
enum class NowPlayingTab { Player, Queue, Lyrics }

/** Pages pushed on top of a tab's root page. */
sealed interface Route {
    data object Home : Route
    data object Search : Route
    data object Library : Route
    data class Album(val id: String) : Route
    data class Artist(val id: String) : Route
    data class Playlist(val id: String) : Route
    data class User(val id: String) : Route
    data class Folder(val id: String, val name: String) : Route
    data object Settings : Route
    data object About : Route
}

/**
 * One page on a back stack. Its page model lives as long as the entry, so
 * switching tabs and coming back keeps scroll data and loaded pages.
 */
class NavEntry(val route: Route) {
    val key: Long = nextKey++
    private val lazyScope = lazy { MainScope() }
    val scope: CoroutineScope by lazyScope
    private val models = mutableMapOf<String, Any>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> model(name: String, create: (CoroutineScope) -> T): T =
        models.getOrPut(name) { create(scope) } as T

    fun dispose() {
        if (lazyScope.isInitialized()) scope.cancel()
    }

    private companion object {
        var nextKey = 1L
    }
}

/**
 * A back stack per bottom bar tab, plus the Now Playing sheet. Only touched
 * from the main thread.
 */
class Navigator {
    var tab by mutableStateOf(Tab.Home)
        private set
    private val stacks = mapOf(
        Tab.Home to mutableStateListOf(NavEntry(Route.Home)),
        Tab.Search to mutableStateListOf(NavEntry(Route.Search)),
        Tab.Library to mutableStateListOf(NavEntry(Route.Library)),
    )

    var librarySection by mutableStateOf(LibrarySection.Playlists)
    var nowPlayingOpen by mutableStateOf(false)
    var nowPlayingTab by mutableStateOf(NowPlayingTab.Player)

    /** Called when a page is pushed, so the core can drop stale requests. */
    var onNavigate: () -> Unit = {}

    val stack: List<NavEntry> get() = stacks.getValue(tab)
    val current: NavEntry get() = stack.last()
    val canGoBack: Boolean get() = nowPlayingOpen || stack.size > 1 || tab != Tab.Home

    fun select(tab: Tab) {
        if (this.tab == tab) {
            popToRoot()
        } else {
            this.tab = tab
        }
        nowPlayingOpen = false
        onNavigate()
    }

    fun push(route: Route) {
        nowPlayingOpen = false
        stacks.getValue(tab).add(NavEntry(route))
        onNavigate()
    }

    fun popToRoot() {
        val stack = stacks.getValue(tab)
        while (stack.size > 1) stack.removeAt(stack.lastIndex).dispose()
    }

    fun openNowPlaying(tab: NowPlayingTab = NowPlayingTab.Player) {
        nowPlayingTab = tab
        nowPlayingOpen = true
    }

    /** Returns false when there is nothing left to go back to. */
    fun back(): Boolean {
        if (nowPlayingOpen) {
            nowPlayingOpen = false
            return true
        }
        val stack = stacks.getValue(tab)
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex).dispose()
            return true
        }
        if (tab != Tab.Home) {
            tab = Tab.Home
            return true
        }
        return false
    }
}

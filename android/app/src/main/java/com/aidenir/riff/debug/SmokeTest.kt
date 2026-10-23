package com.aidenir.riff.debug

import android.util.Log
import com.aidenir.riff.BuildConfig
import com.aidenir.riff.core.AppState
import com.aidenir.riff.core.Overlay
import com.aidenir.riff.core.describe
import com.aidenir.riff.ffi.AlbumInfo
import com.aidenir.riff.ffi.PlaySource
import com.aidenir.riff.ffi.PlaylistInfo
import com.aidenir.riff.ffi.Repeat
import com.aidenir.riff.ffi.SearchScope
import com.aidenir.riff.ffi.TrackInfo
import com.aidenir.riff.ffi.parseSpotifyLink
import com.aidenir.riff.ui.LibrarySection
import com.aidenir.riff.ui.NowPlayingTab
import com.aidenir.riff.ui.Route
import com.aidenir.riff.ui.Tab
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Walks a logged-in debug build through every page and playback action,
 * logging each step (tag `RiffSmoke`), so a crash or a broken call shows up
 * in logcat. Started with `adb shell am start -n <activity> -e smoke 1`;
 * scripts/test-android.sh takes a screenshot at every step.
 */
object SmokeTest {
    private const val TAG = "RiffSmoke"
    private var enabled = false
    private var started = false
    private var failures = 0

    fun enable() {
        if (BuildConfig.DEBUG) enabled = true
    }

    fun startIfEnabled(app: AppState) {
        if (!enabled || started) return
        started = true
        app.scope.launch { run(app) }
    }

    private fun log(message: String) = Log.i(TAG, "SMOKE $message")

    private class Failure(message: String) : Exception(message)

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw Failure(message)
    }

    private suspend fun step(name: String, wait: Long = 3000, body: suspend () -> String) {
        log("step: $name")
        try {
            log("  ok: ${body()}")
        } catch (e: Exception) {
            failures++
            log("  FAIL: ${e.describe()}")
        }
        delay(wait)
    }

    private suspend fun run(app: AppState) {
        val core = app.core
        val nav = app.navigator
        delay(3000)

        step("home") {
            nav.select(Tab.Home)
            "shown"
        }
        var album: AlbumInfo? = null
        step("saved albums") {
            nav.select(Tab.Library)
            nav.librarySection = LibrarySection.Albums
            val page = core.savedAlbums(0u, 20u)
            album = page.items.firstOrNull()
            "${page.items.size} of ${page.total}"
        }
        var playlist: PlaylistInfo? = null
        step("saved playlists") {
            nav.librarySection = LibrarySection.Playlists
            val page = core.savedPlaylists(0u, 20u)
            playlist = page.items.firstOrNull()
            "${page.items.size} of ${page.total}"
        }
        var liked: List<TrackInfo> = emptyList()
        step("liked songs") {
            nav.librarySection = LibrarySection.LikedSongs
            val page = core.savedTracks(0u, 50u)
            liked = page.items
            "${page.items.size} of ${page.total}"
        }
        step("followed artists") {
            nav.librarySection = LibrarySection.Artists
            "${core.followedArtists(null, 20u).items.size}"
        }
        step("library tree") {
            val tree = core.libraryTree()
            "${tree.size} entries, ${tree.count { it.isFolder }} folders"
        }

        album?.let { album ->
            step("open album ${album.title}") {
                nav.push(Route.Album(album.id))
                "${core.albumTracks(album.id, 0u, 50u).items.size} tracks"
            }
            step("play album", wait = 6000) {
                core.playSource(PlaySource.Album(album.id), false)
                "requested"
            }
            step("is playing") {
                val snapshot = app.playback.value.snapshot
                check(snapshot.current != null, "no current track")
                check(snapshot.isPlaying, "not playing")
                "${snapshot.current?.title} at ${snapshot.positionMs} ms"
            }
            step("mini player and now playing") {
                nav.openNowPlaying()
                "shown"
            }
            step("next", wait = 4000) {
                val before = app.playback.value.snapshot.current?.id
                core.next()
                delay(2000)
                val after = app.playback.value.snapshot
                check(after.current?.id != before || !after.hasNext, "track did not change")
                after.current?.title ?: "-"
            }
            step("previous", wait = 4000) {
                core.previous()
                app.playback.value.snapshot.current?.title ?: "-"
            }
            step("pause") {
                core.pause()
                delay(1000)
                check(!app.playback.value.snapshot.isPlaying, "still playing")
                "paused"
            }
            step("resume") {
                core.play()
                delay(1000)
                check(app.playback.value.snapshot.isPlaying, "not resumed")
                "playing"
            }
            step("seek 30s") {
                core.seek(30_000u)
                delay(2000)
                val position = app.playback.value.position()
                check(position >= 29_000, "position $position")
                "$position ms"
            }
            step("volume") {
                val before = app.playback.value.snapshot.volume
                core.setVolume(0.4)
                delay(1000)
                val volume = app.playback.value.snapshot.volume
                core.setVolume(before)
                check(abs(volume - 0.4) < 0.01, "volume $volume")
                "0.4"
            }
            step("shuffle and repeat") {
                core.toggleShuffle()
                delay(1000)
                val shuffled = app.playback.value.snapshot.shuffle
                core.toggleShuffle()
                core.cycleRepeat()
                delay(1000)
                val repeat = app.playback.value.snapshot.repeat
                core.setRepeat(Repeat.OFF)
                "shuffle toggled to $shuffled, repeat cycled to $repeat"
            }
            step("enqueue and queue tab") {
                liked.firstOrNull()?.let { core.enqueue(listOf(it.id)) }
                delay(1000)
                nav.openNowPlaying(NowPlayingTab.Queue)
                val queue = app.queue.value
                "${queue.queued.size} queued, ${queue.context.size} in context"
            }
            step("lyrics", wait = 4000) {
                nav.openNowPlaying(NowPlayingTab.Lyrics)
                val id = app.playback.value.snapshot.current?.id ?: return@step "nothing playing"
                val lyrics = runCatching { core.lyrics(id) }.getOrNull()
                "${lyrics?.lines?.size ?: 0} lines, synced ${lyrics?.synced ?: false}"
            }
            step("track actions") {
                nav.openNowPlaying(NowPlayingTab.Player)
                app.playback.value.snapshot.current?.let { app.overlay = Overlay.TrackActions(listOf(it)) }
                "shown"
            }
            step("close sheets") {
                app.overlay = null
                nav.nowPlayingOpen = false
                "closed"
            }
            album.artists.firstOrNull()?.let { artist ->
                step("artist ${artist.name}") {
                    nav.select(Tab.Home)
                    nav.push(Route.Artist(artist.id))
                    val top = core.artistTopTracks(artist.id)
                    val albums = core.artistAlbums(artist.id, 0u, 20u)
                    "${top.size} top tracks, ${albums.items.size} albums"
                }
            }
        }

        playlist?.let { playlist ->
            step("playlist ${playlist.title}") {
                nav.push(Route.Playlist(playlist.id))
                "${core.playlistTracks(playlist.id, 0u, 50u).items.size} tracks"
            }
            step("play playlist track", wait = 5000) {
                val tracks = core.playlistTracks(playlist.id, 0u, 50u)
                val second = tracks.items.drop(1).firstOrNull() ?: tracks.items.firstOrNull() ?: return@step "empty"
                app.play(tracks.items, PlaySource.Playlist(playlist.id), second, tracks.total)
                delay(3000)
                check(app.playback.value.snapshot.current?.id == second.id, "playing ${app.playback.value.snapshot.current?.title}")
                second.title
            }
        }

        step("search") {
            nav.select(Tab.Search)
            val results = core.search("daft punk", 10u)
            check(results.tracks.isNotEmpty(), "no tracks")
            "${results.tracks.size} tracks, ${results.albums.size} albums, ${results.artists.size} artists, ${results.playlists.size} playlists"
        }
        step("search scoped") {
            "${core.searchScoped("daft punk", SearchScope.ALBUMS, 0u, 20u).albums.size} albums"
        }
        step("home data") {
            nav.select(Tab.Home)
            val recent = core.recentContexts(5u)
            val artists = core.topArtists(5u)
            val charts = runCatching { core.categoryPlaylists("toplists", 5u) }.getOrDefault(emptyList())
            "${recent.size} recent, ${artists.size} top artists, ${charts.size} charts"
        }
        step("profile") {
            val user = core.currentUser()
            nav.push(Route.User(user.id))
            user.displayName
        }
        step("devices") {
            core.devices().joinToString { it.name }
        }
        step("settings round trip") {
            nav.push(Route.Settings)
            app.updateSettings { it.copy(eqBands = listOf(1.0) + List(9) { 0.0 }) }
            delay(1000)
            check(core.settings().eqBands.first() == 1.0, "eq not saved")
            app.updateSettings { it.copy(eqBands = List(10) { 0.0 }) }
            "saved"
        }
        step("link handling") {
            check(parseSpotifyLink("https://open.spotify.com/album/abc") != null, "link not parsed")
            "ok"
        }
        step("stop") {
            core.pause()
            nav.select(Tab.Home)
            "paused"
        }
        log("DONE failures=$failures")
    }
}

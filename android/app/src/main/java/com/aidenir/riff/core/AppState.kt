package com.aidenir.riff.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aidenir.riff.R
import com.aidenir.riff.ffi.AppSettings
import com.aidenir.riff.ffi.CoreConfig
import com.aidenir.riff.ffi.CoreEvent
import com.aidenir.riff.ffi.LinkKind
import com.aidenir.riff.ffi.Listener
import com.aidenir.riff.ffi.PlaySource
import com.aidenir.riff.ffi.PlaybackSnapshot
import com.aidenir.riff.ffi.PlaylistInfo
import com.aidenir.riff.ffi.QueueSnapshot
import com.aidenir.riff.ffi.RiffCore
import com.aidenir.riff.ffi.TrackInfo
import com.aidenir.riff.ffi.UserInfo
import com.aidenir.riff.ffi.parseSpotifyLink
import com.aidenir.riff.ui.Navigator
import com.aidenir.riff.ui.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Where the user is in the login flow. */
sealed interface Session {
    data object Starting : Session
    data object LoggedOut : Session
    data object WaitingForBrowser : Session
    data class LoggedIn(val username: String) : Session
}

/** The playback snapshot and when it was reported, to keep the clock running. */
data class Playback(val snapshot: PlaybackSnapshot, val anchor: Long = SystemClock.elapsedRealtime()) {
    /** Current position, extrapolated while playing. */
    fun position(now: Long = SystemClock.elapsedRealtime()): Long {
        val base = snapshot.positionMs.toLong()
        if (!snapshot.isPlaying) return base
        val duration = snapshot.current?.durationMs?.toLong() ?: Long.MAX_VALUE
        return minOf(base + (now - anchor), duration)
    }
}

/** Sheets and dialogs shown over the whole app. */
sealed interface Overlay {
    data class TrackActions(val tracks: List<TrackInfo>, val playlistId: String? = null) : Overlay
    data class AddToPlaylist(val tracks: List<TrackInfo>) : Overlay
    /** Create a playlist (`playlist == null`), or edit one. */
    data class PlaylistEditor(val playlist: PlaylistInfo?, val tracksToAdd: List<TrackInfo> = emptyList()) : Overlay
    data class CardActions(val item: CardItem) : Overlay
    data object Devices : Overlay
}

/**
 * Single source of truth for the UI and the playback service, fed by events
 * from the Rust core. Mirrors the macOS `AppModel`.
 *
 * Flows may be read from any thread; everything that touches Compose state
 * ([navigator], [overlay]) runs on the main thread.
 */
class AppState(private val context: Context, val core: RiffCore) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var images: ImageLoader
        private set

    private val _session = MutableStateFlow<Session>(Session.Starting)
    val session: StateFlow<Session> = _session
    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError
    private val _user = MutableStateFlow<UserInfo?>(null)
    val user: StateFlow<UserInfo?> = _user

    private val _playback = MutableStateFlow(Playback(core.playback()))
    val playback: StateFlow<Playback> = _playback
    private val _queue = MutableStateFlow(core.queue())
    val queue: StateFlow<QueueSnapshot> = _queue
    private val _settings = MutableStateFlow(core.settings())
    val settings: StateFlow<AppSettings> = _settings

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages
    private val _loginUrls = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** The browser must be opened on these URLs to finish logging in. */
    val loginUrls: SharedFlow<String> = _loginUrls

    private val _connectionLost = MutableStateFlow(false)
    val connectionLost: StateFlow<Boolean> = _connectionLost
    val showDrmBlocked = MutableStateFlow(false)
    val showNotPremium = MutableStateFlow(false)

    /** Bumped whenever the library changes, so pages reload. */
    private val _libraryVersion = MutableStateFlow(0)
    val libraryVersion: StateFlow<Int> = _libraryVersion
    /** Like state changed locally, keyed by track id, until pages reload. */
    private val _likedOverrides = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val likedOverrides: StateFlow<Map<String, Boolean>> = _likedOverrides

    /** Playlists the user can add tracks to. */
    private val _ownedPlaylists = MutableStateFlow<List<PlaylistInfo>>(emptyList())
    val ownedPlaylists: StateFlow<List<PlaylistInfo>> = _ownedPlaylists
    private var ownedPlaylistsVersion = -1

    val navigator = Navigator().apply { onNavigate = { core.bumpEpoch() } }
    /** The playlist folders, shared by the library and folder pages. */
    val playlistLibrary by lazy { PlaylistLibrary(this) }
    var overlay by mutableStateOf<Overlay?>(null)

    private val listener = object : Listener {
        override fun onEvent(event: CoreEvent) {
            scope.launch { handle(event) }
        }
    }

    fun attach(images: ImageLoader) {
        this.images = images
    }

    val coreListener: Listener get() = listener

    fun start() {
        core.restoreSession()
    }

    private fun handle(event: CoreEvent) {
        when (event) {
            is CoreEvent.OpenLoginUrl -> {
                _session.value = Session.WaitingForBrowser
                _loginUrls.tryEmit(event.url)
            }
            is CoreEvent.LoggedIn -> {
                _loginError.value = null
                _session.value = Session.LoggedIn(event.username)
                scope.launch { _user.value = runCatching { core.currentUser() }.getOrNull() }
            }
            CoreEvent.LoggedOut -> {
                _session.value = Session.LoggedOut
                _user.value = null
            }
            CoreEvent.LoginFailed -> {
                _session.value = Session.LoggedOut
                _loginError.value = context.getString(R.string.login_failed)
            }
            CoreEvent.NotPremium -> {
                _session.value = Session.LoggedOut
                showNotPremium.value = true
            }
            CoreEvent.DrmBlocked -> showDrmBlocked.value = true
            is CoreEvent.ConnectionLost -> _connectionLost.value = event.lost
            is CoreEvent.Notification -> show(event.message)
            is CoreEvent.PlaybackChanged -> _playback.value = Playback(event.playback)
            is CoreEvent.QueueChanged -> _queue.value = event.queue
            CoreEvent.LibraryChanged -> {
                _libraryVersion.value += 1
                _likedOverrides.value = emptyMap()
            }
        }
    }

    fun show(message: String) {
        _messages.tryEmit(message)
    }

    /** Reports a failed core call. */
    fun report(error: Throwable) {
        Log.w(TAG, "Core call failed", error)
        show(error.describe())
    }

    /** Runs a core call on the app scope, reporting failures. */
    fun launch(block: suspend CoroutineScope.() -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                report(e)
            }
        }
    }

    // Settings

    /** Saves settings edited in the UI; the core keeps volume and the like. */
    fun updateSettings(change: (AppSettings) -> AppSettings) {
        val updated = change(_settings.value)
        if (updated == _settings.value) return
        _settings.value = updated
        core.updateSettings(updated)
    }

    // Session

    fun login() {
        _loginError.value = null
        core.startLogin()
    }

    fun logout() {
        core.logout()
    }

    // Playback

    fun isLiked(track: TrackInfo): Boolean = _likedOverrides.value[track.id] ?: track.saved ?: false

    fun setLiked(tracks: List<TrackInfo>, liked: Boolean) {
        _likedOverrides.value = _likedOverrides.value + tracks.associate { it.id to liked }
        val ids = tracks.map { it.id }
        scope.launch {
            try {
                core.setTracksSaved(ids, liked)
            } catch (e: Exception) {
                _likedOverrides.value = _likedOverrides.value + ids.associateWith { !liked }
                report(e)
            }
        }
    }

    fun play(tracks: List<TrackInfo>, source: PlaySource?, start: TrackInfo?, total: UInt? = null) {
        if (start != null && !start.playable) return
        core.playTracks(source, tracks.map { it.id }, total, start?.id, null)
    }

    fun play(source: PlaySource, shuffle: Boolean = false) = launch {
        core.playSource(source, shuffle)
    }

    fun enqueue(tracks: List<TrackInfo>) {
        core.enqueue(tracks.map { it.id })
    }

    // Playlists

    fun canEdit(playlist: PlaylistInfo): Boolean {
        val user = _user.value ?: return false
        return playlist.owner?.id == user.id || playlist.collaborative == true
    }

    fun ownsPlaylist(id: String) = _ownedPlaylists.value.any { it.id == id }

    suspend fun loadOwnedPlaylistsIfNeeded() {
        if (ownedPlaylistsVersion == _libraryVersion.value) return
        ownedPlaylistsVersion = _libraryVersion.value
        if (_user.value == null) _user.value = runCatching { core.currentUser() }.getOrNull()
        val playlists = mutableListOf<PlaylistInfo>()
        var offset = 0u
        while (true) {
            val page = runCatching { core.savedPlaylists(offset, 50u) }.getOrNull() ?: break
            playlists += page.items.filter(::canEdit)
            offset += page.items.size.toUInt()
            if (page.items.isEmpty() || offset >= (page.total ?: 0u)) break
        }
        _ownedPlaylists.value = playlists
    }

    fun add(tracks: List<TrackInfo>, playlist: PlaylistInfo) = launch {
        val added = core.addToPlaylist(playlist.id, tracks.map { it.id })
        show(
            if (added == 0u) context.getString(R.string.already_in_playlist, playlist.title)
            else context.getString(R.string.added_to_playlist, playlist.title),
        )
    }

    // Pinning

    fun isPinned(item: PinnedItem) = _settings.value.pinned.any { PinnedItem.decode(it)?.id == item.id }

    fun togglePin(item: PinnedItem) = updateSettings { settings ->
        val pinned = if (isPinned(item)) {
            settings.pinned.filter { PinnedItem.decode(it)?.id != item.id }
        } else {
            settings.pinned + item.encoded
        }
        settings.copy(pinned = pinned)
    }

    // Links

    /** Opens a Spotify link or URI inside the app. Returns whether it was one. */
    fun open(link: String): Boolean {
        val parsed = parseSpotifyLink(link) ?: return false
        when (parsed.kind) {
            LinkKind.ALBUM -> navigator.push(Route.Album(parsed.id))
            LinkKind.ARTIST -> navigator.push(Route.Artist(parsed.id))
            LinkKind.PLAYLIST -> navigator.push(Route.Playlist(parsed.id))
            LinkKind.USER -> navigator.push(Route.User(parsed.id))
            LinkKind.TRACK -> launch {
                val track = core.track(parsed.id)
                track.album?.let { navigator.push(Route.Album(it.id)) }
            }
        }
        return true
    }

    /** Finds the first Spotify link in shared text. */
    fun openShared(text: String): Boolean {
        val candidates = text.split(Regex("\\s+")).filter { it.contains("spotify") }
        return candidates.any { open(it) }
    }

    fun copyLink(url: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Spotify link", url))
    }

    private companion object {
        const val TAG = "RiffState"
    }
}

/** The process-wide state, created by [com.aidenir.riff.RiffApplication]. */
object Riff {
    lateinit var state: AppState
        private set

    fun create(context: Context, config: CoreConfig, secrets: KeystoreSecretStore): AppState {
        // The listener is needed before the core exists, so it forwards to the
        // state once that is built.
        var target: AppState? = null
        val pending = mutableListOf<CoreEvent>()
        val forward = object : Listener {
            override fun onEvent(event: CoreEvent) {
                synchronized(pending) {
                    val state = target
                    if (state == null) {
                        pending += event
                        return
                    }
                    state.coreListener.onEvent(event)
                }
            }
        }
        val core = RiffCore(forward, config, secrets)
        val state = AppState(context, core)
        state.attach(ImageLoader(context, core))
        synchronized(pending) {
            target = state
            pending.forEach { state.coreListener.onEvent(it) }
        }
        this.state = state
        return state
    }
}

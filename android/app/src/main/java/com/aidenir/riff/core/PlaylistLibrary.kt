package com.aidenir.riff.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aidenir.riff.ffi.PlaylistInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * The user's playlists in their folders, as the official clients show them,
 * with the full playlist records for artwork and owners.
 */
class PlaylistLibrary(private val app: AppState) {
    var tree by mutableStateOf(emptyList<LibraryNode>())
        private set
    var playlists by mutableStateOf(emptyMap<String, PlaylistInfo>())
        private set
    /** Saved playlists in library order, for sorting without folders. */
    var ordered by mutableStateOf(emptyList<PlaylistInfo>())
        private set
    var loaded by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var version = -1
    private var job: Job? = null

    fun loadIfNeeded() {
        if (version == app.libraryVersion.value && (loaded || job?.isActive == true)) return
        reload()
    }

    fun reload() {
        version = app.libraryVersion.value
        job?.cancel()
        job = app.scope.launch {
            error = null
            try {
                val entries = async { runCatching { app.core.libraryTree() } }
                val all = loadAll()
                val byId = all.associateBy { it.id }
                ordered = all
                playlists = byId
                tree = entries.await().getOrNull()
                    ?.let { LibraryNode.tree(it, byId.mapValues { (_, p) -> p.title }) }
                    // Without a folder tree, fall back to the flat list.
                    ?: all.map { LibraryNode(it.id, it.title, false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.describe()
            }
            loaded = true
        }
    }

    private suspend fun loadAll(): List<PlaylistInfo> {
        val all = mutableListOf<PlaylistInfo>()
        var offset = 0u
        while (true) {
            val page = app.core.savedPlaylists(offset, 50u)
            all += page.items
            offset += page.items.size.toUInt()
            if (page.items.isEmpty() || offset >= (page.total ?: 0u)) break
        }
        return all
    }
}

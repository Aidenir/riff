package com.aidenir.riff.core

import com.aidenir.riff.ffi.AlbumInfo
import com.aidenir.riff.ffi.ArtistInfo
import com.aidenir.riff.ffi.Artwork
import com.aidenir.riff.ffi.LibraryEntry
import com.aidenir.riff.ffi.PlaySource
import com.aidenir.riff.ffi.PlaylistInfo

/** What a card stands for, and where tapping it goes. */
sealed interface CardItem {
    val id: String
    val title: String
    val art: Artwork
    val source: PlaySource
    val url: String
    val pin: PinnedItem

    data class Album(val album: AlbumInfo) : CardItem {
        override val id get() = "album:${album.id}"
        override val title get() = album.title
        override val art get() = album.art
        override val source get() = PlaySource.Album(album.id)
        override val url get() = album.url ?: "https://open.spotify.com/album/${album.id}"
        override val pin get() = PinnedItem(PinnedItem.Kind.Album, album.id, album.title)
    }

    data class Playlist(val playlist: PlaylistInfo) : CardItem {
        override val id get() = "playlist:${playlist.id}"
        override val title get() = playlist.title
        override val art get() = playlist.art
        override val source get() = PlaySource.Playlist(playlist.id)
        override val url get() = playlist.url ?: "https://open.spotify.com/playlist/${playlist.id}"
        override val pin get() = PinnedItem(PinnedItem.Kind.Playlist, playlist.id, playlist.title)
    }

    data class Artist(val artist: ArtistInfo) : CardItem {
        override val id get() = "artist:${artist.id}"
        override val title get() = artist.name
        override val art get() = artist.art
        override val source get() = PlaySource.Artist(artist.id)
        override val url get() = artist.url ?: "https://open.spotify.com/artist/${artist.id}"
        override val pin get() = PinnedItem(PinnedItem.Kind.Artist, artist.id, artist.name)
    }
}

/**
 * Something pinned to the top of the library (feature flag `PinnedObjects`),
 * stored as `kind:id:title` strings in the settings like on the Mac.
 */
data class PinnedItem(val kind: Kind, val id: String, val title: String) {
    enum class Kind(val key: String) { Playlist("playlist"), Album("album"), Artist("artist") }

    val encoded: String get() = "${kind.key}:$id:$title"

    companion object {
        fun decode(encoded: String): PinnedItem? {
            val parts = encoded.split(":", limit = 3)
            if (parts.size < 2) return null
            val kind = Kind.entries.firstOrNull { it.key == parts[0] } ?: return null
            return PinnedItem(kind, parts[1], parts.getOrElse(2) { parts[1] })
        }
    }
}

/** A playlist folder or playlist in the library tree. */
data class LibraryNode(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val children: List<LibraryNode> = emptyList(),
) {
    companion object {
        /** Rebuilds the folder tree from the core's flattened, depth-first list. */
        fun tree(entries: List<LibraryEntry>, names: Map<String, String>): List<LibraryNode> {
            var index = 0
            fun build(depth: UInt): List<LibraryNode> {
                val nodes = mutableListOf<LibraryNode>()
                while (index < entries.size && entries[index].depth == depth) {
                    val entry = entries[index++]
                    nodes += if (entry.isFolder) {
                        LibraryNode(entry.id, entry.name ?: "", true, build(depth + 1u))
                    } else {
                        LibraryNode(entry.id, entry.name ?: names[entry.id] ?: "", false)
                    }
                }
                return nodes
            }
            return build(0u)
        }

        fun find(nodes: List<LibraryNode>, id: String): LibraryNode? {
            for (node in nodes) {
                if (node.id == id) return node
                find(node.children, id)?.let { return it }
            }
            return null
        }
    }
}

/** Sort orders offered on library pages, stored with the GTK app's keys. */
enum class SortOrder(val key: String) {
    RecentlyAdded("recently-added"),
    Alphabetic("alphabetic"),
    Creator("creator"),
    DateReleased("date-released"),
    Popularity("popularity");

    fun <T : CardItem> sort(items: List<T>, subtitle: (T) -> String): List<T> = when (this) {
        RecentlyAdded -> items
        Alphabetic -> items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        Creator -> items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { subtitle(it) })
        DateReleased -> items.sortedByDescending { (it as? CardItem.Album)?.album?.year ?: 0 }
        Popularity -> items.sortedByDescending {
            when (it) {
                is CardItem.Album -> it.album.popularity ?: 0u
                is CardItem.Artist -> it.artist.popularity ?: 0u
                else -> 0u
            }.toInt()
        }
    }

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: RecentlyAdded
    }
}

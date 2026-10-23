package com.aidenir.riff

import com.aidenir.riff.core.CardItem
import com.aidenir.riff.core.DayPart
import com.aidenir.riff.core.LibraryNode
import com.aidenir.riff.core.Mood
import com.aidenir.riff.core.PinnedItem
import com.aidenir.riff.core.SortOrder
import com.aidenir.riff.core.formatDuration
import com.aidenir.riff.ffi.AlbumInfo
import com.aidenir.riff.ffi.Artwork
import com.aidenir.riff.ffi.LibraryEntry
import com.aidenir.riff.ffi.NamedLink
import com.aidenir.riff.ui.Navigator
import com.aidenir.riff.ui.Route
import com.aidenir.riff.ui.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class FormattingTest {
    @Test
    fun durations() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("3:07", formatDuration(187_000))
        assertEquals("1:02:03", formatDuration(3_723_000))
    }
}

class PinnedItemTest {
    @Test
    fun roundTrips() {
        val item = PinnedItem(PinnedItem.Kind.Playlist, "abc", "My: List")
        assertEquals(item, PinnedItem.decode(item.encoded))
    }

    @Test
    fun titleFallsBackToId() {
        assertEquals(PinnedItem(PinnedItem.Kind.Album, "xyz", "xyz"), PinnedItem.decode("album:xyz"))
        assertNull(PinnedItem.decode("show:xyz"))
    }
}

class LibraryTreeTest {
    private fun entry(id: String, name: String?, folder: Boolean, depth: UInt) = LibraryEntry(id, name, folder, depth)

    @Test
    fun rebuildsFoldersFromFlatList() {
        val entries = listOf(
            entry("a", null, false, 0u),
            entry("f", "Folder", true, 0u),
            entry("b", null, false, 1u),
            entry("g", "Inner", true, 1u),
            entry("c", "Named", false, 2u),
            entry("d", null, false, 0u),
        )
        val tree = LibraryNode.tree(entries, mapOf("a" to "A", "b" to "B", "d" to "D"))
        assertEquals(listOf("A", "Folder", "D"), tree.map { it.name })
        val folder = tree[1]
        assertTrue(folder.isFolder)
        assertEquals(listOf("B", "Inner"), folder.children.map { it.name })
        assertEquals("Named", folder.children[1].children.single().name)
        assertEquals("Inner", LibraryNode.find(tree, "g")?.name)
    }
}

class MoodTest {
    @Test
    fun fridayNight() {
        assertEquals("friday night party", Mood.moment(Calendar.FRIDAY, DayPart.Night).query)
        assertEquals("monday motivation", Mood.moment(Calendar.MONDAY, DayPart.Morning).query)
        assertEquals("sunday chill", Mood.moment(Calendar.SUNDAY, DayPart.Evening).query)
    }

    @Test
    fun southernSeasonsAreFlipped() {
        assertEquals(Mood.Season.Kind.Summer, Mood.season(7, 1, "SE").kind)
        assertEquals(Mood.Season.Kind.Winter, Mood.season(7, 1, "AU").kind)
        assertEquals(Mood.Season.Kind.Halloween, Mood.season(10, 20, "AU").kind)
    }

    @Test
    fun dayParts() {
        assertEquals(DayPart.Morning, DayPart.of(5))
        assertEquals(DayPart.Afternoon, DayPart.of(12))
        assertEquals(DayPart.Evening, DayPart.of(21))
        assertEquals(DayPart.Night, DayPart.of(2))
    }
}

class SortOrderTest {
    private fun album(title: String, artist: String, year: Int?) = CardItem.Album(
        AlbumInfo(
            id = title, title = title, artists = listOf(NamedLink(artist, artist)), art = Artwork(null, null, null),
            albumType = "Album", releaseDate = null, year = year, totalTracks = null, label = null, copyright = null,
            genres = emptyList(), popularity = null, saved = null, url = null,
        ),
    )

    @Test
    fun sortsByKey() {
        val items = listOf(album("b", "Zed", 2001), album("A", "Abba", 2020), album("c", "Moby", null))
        assertEquals(listOf("b", "A", "c"), SortOrder.of("recently-added").sort(items) { it.album.artists[0].name }.map { it.title })
        assertEquals(listOf("A", "b", "c"), SortOrder.of("alphabetic").sort(items) { it.album.artists[0].name }.map { it.title })
        assertEquals(listOf("A", "c", "b"), SortOrder.of("creator").sort(items) { it.album.artists[0].name }.map { it.title })
        assertEquals(listOf("A", "b", "c"), SortOrder.of("date-released").sort(items) { "" }.map { it.title })
        assertEquals(SortOrder.RecentlyAdded, SortOrder.of("unknown"))
    }
}

class NavigatorTest {
    @Test
    fun tabsKeepTheirOwnStacks() {
        val nav = Navigator()
        nav.push(Route.Album("a"))
        nav.select(Tab.Library)
        nav.push(Route.Playlist("p"))
        assertEquals(Route.Playlist("p"), nav.current.route)
        nav.select(Tab.Home)
        assertEquals(Route.Album("a"), nav.current.route)
    }

    @Test
    fun backClosesNowPlayingThenPopsThenGoesHome() {
        val nav = Navigator()
        nav.select(Tab.Search)
        nav.push(Route.Artist("x"))
        nav.openNowPlaying()
        assertTrue(nav.back())
        assertFalse(nav.nowPlayingOpen)
        assertTrue(nav.back())
        assertEquals(Route.Search, nav.current.route)
        assertTrue(nav.back())
        assertEquals(Tab.Home, nav.tab)
        assertFalse(nav.back())
    }

    @Test
    fun reselectingATabPopsToItsRoot() {
        val nav = Navigator()
        nav.push(Route.Album("a"))
        nav.push(Route.Artist("b"))
        nav.select(Tab.Home)
        assertEquals(1, nav.stack.size)
    }
}

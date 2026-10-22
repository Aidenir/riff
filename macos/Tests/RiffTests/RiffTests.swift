import RiffCore
import Testing
@testable import Riff

@MainActor
struct NavigatorTests {
    @Test func backAndForward() {
        let navigator = Navigator()
        navigator.push(.album("a"))
        navigator.push(.artist("b"))
        navigator.back()
        #expect(navigator.path == [.album("a")])
        #expect(navigator.canGoForward)
        navigator.goForward()
        #expect(navigator.path == [.album("a"), .artist("b")])
        #expect(!navigator.canGoForward)
    }

    @Test func newPushClearsForwardHistory() {
        let navigator = Navigator()
        navigator.push(.album("a"))
        navigator.back()
        navigator.push(.playlist("p"))
        #expect(!navigator.canGoForward)
    }

    @Test func changingSectionResetsPath() {
        let navigator = Navigator()
        navigator.push(.album("a"))
        navigator.selection = .search
        #expect(navigator.path.isEmpty)
        #expect(!navigator.canGoForward)
    }
}

struct LibraryTreeTests {
    @Test func rebuildsFoldersFromFlatList() {
        let entries = [
            LibraryEntry(id: "f1", name: "Folder", isFolder: true, depth: 0),
            LibraryEntry(id: "p1", name: nil, isFolder: false, depth: 1),
            LibraryEntry(id: "f2", name: "Inner", isFolder: true, depth: 1),
            LibraryEntry(id: "p2", name: "Two", isFolder: false, depth: 2),
            LibraryEntry(id: "p3", name: "Three", isFolder: false, depth: 0),
        ]
        let tree = LibraryNode.tree(from: entries, names: ["p1": "One"])
        #expect(tree.map(\.id) == ["f1", "p3"])
        #expect(tree[0].children?.map(\.id) == ["p1", "f2"])
        #expect(tree[0].children?[0].name == "One")
        #expect(tree[0].children?[1].children?.map(\.id) == ["p2"])
    }
}

struct FormattingTests {
    @Test func durations() {
        #expect(formatDuration(0) == "0:00")
        #expect(formatDuration(61_000) == "1:01")
        #expect(formatDuration(3_725_000) == "1:02:05")
    }

    @Test func pinnedItemsRoundTrip() {
        let item = PinnedItem(kind: .album, id: "abc", title: "A: B")
        #expect(PinnedItem(encoded: item.encoded) == item)
        #expect(PinnedItem(encoded: "nonsense") == nil)
    }
}

struct MoodTests {
    @Test func southernSeasonsAreFlipped() {
        #expect(Mood.season(month: 7, day: 1, country: "SE").query == "summer hits")
        #expect(Mood.season(month: 7, day: 1, country: "AU").query == "cozy winter")
        #expect(Mood.season(month: 10, day: 20, country: nil).query == "halloween")
    }

    @Test func fridayNight() {
        #expect(Mood.moment(weekday: 6, part: .evening).query == "friday night party")
        #expect(Mood.moment(weekday: 2, part: .morning).query == "monday motivation")
    }
}

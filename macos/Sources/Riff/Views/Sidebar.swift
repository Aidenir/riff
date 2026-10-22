import RiffCore
import SwiftUI

/// A playlist folder or playlist in the sidebar tree.
struct LibraryNode: Identifiable, Hashable {
    let id: String
    let name: String
    let isFolder: Bool
    var children: [LibraryNode]?

    /// Rebuilds the folder tree from the core's flattened, depth-first list.
    static func tree(from entries: [LibraryEntry], names: [String: String]) -> [LibraryNode] {
        var index = 0
        func build(depth: UInt32) -> [LibraryNode] {
            var nodes: [LibraryNode] = []
            while index < entries.count, entries[index].depth == depth {
                let entry = entries[index]
                index += 1
                if entry.isFolder {
                    let children = build(depth: depth + 1)
                    nodes.append(LibraryNode(
                        id: entry.id, name: entry.name ?? "", isFolder: true, children: children
                    ))
                } else {
                    let name = entry.name ?? names[entry.id] ?? ""
                    nodes.append(LibraryNode(id: entry.id, name: name, isFolder: false, children: nil))
                }
            }
            return nodes
        }
        return build(depth: 0)
    }
}

struct Sidebar: View {
    @Environment(AppModel.self) private var model
    @State private var library: [LibraryNode] = []

    var body: some View {
        @Bindable var navigation = model.navigation
        List(selection: $navigation.selection) {
            Section {
                Label("Home", systemImage: "house").tag(SidebarItem.home)
                Label("Search", systemImage: "magnifyingglass").tag(SidebarItem.search)
                Label("Now Playing", systemImage: "play.circle").tag(SidebarItem.nowPlaying)
            }
            Section("Library") {
                Label("Albums", systemImage: "square.stack").tag(SidebarItem.savedAlbums)
                Label("Playlists", systemImage: "music.note.list").tag(SidebarItem.savedPlaylists)
                Label("Liked Songs", systemImage: "heart").tag(SidebarItem.savedTracks)
                Label("Artists", systemImage: "music.microphone").tag(SidebarItem.savedArtists)
            }
            if model.settings.featurePinnedObjects {
                let pinned = model.settings.pinned.compactMap(PinnedItem.init(encoded:))
                if !pinned.isEmpty {
                    Section("Pinned") {
                        ForEach(pinned, id: \.self) { item in
                            Label(item.title, systemImage: "pin")
                                .tag(SidebarItem.pinned(item))
                                .contextMenu {
                                    Button("Unpin") { unpin(item) }
                                }
                        }
                    }
                }
            }
            if !library.isEmpty {
                Section("Playlists") {
                    OutlineGroup(library, children: \.children) { node in
                        if node.isFolder {
                            Label(node.name, systemImage: "folder")
                        } else {
                            Label(node.name, systemImage: "music.note.list")
                                .tag(SidebarItem.playlist(node.id))
                        }
                    }
                }
            }
        }
        .listStyle(.sidebar)
        .task(id: model.libraryVersion) { await loadLibrary() }
    }

    private func unpin(_ item: PinnedItem) {
        model.settings.pinned.removeAll { $0 == item.encoded }
    }

    private func loadLibrary() async {
        do {
            async let entries = model.core.libraryTree()
            async let playlists = loadAllPlaylists()
            let names = await playlists
            library = LibraryNode.tree(from: try await entries, names: names)
        } catch {
            // Without a folder tree, fall back to the flat playlist list.
            let names = await loadAllPlaylists()
            library = names.sorted { $0.value < $1.value }.map {
                LibraryNode(id: $0.key, name: $0.value, isFolder: false, children: nil)
            }
        }
    }

    private func loadAllPlaylists() async -> [String: String] {
        var names: [String: String] = [:]
        var offset: UInt32 = 0
        while let page = try? await model.core.savedPlaylists(offset: offset, limit: 50) {
            for playlist in page.items { names[playlist.id] = playlist.title }
            offset += UInt32(page.items.count)
            if page.items.isEmpty || offset >= (page.total ?? 0) { break }
        }
        return names
    }
}

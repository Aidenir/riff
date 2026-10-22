import RiffCore
import SwiftUI

/// Sort orders offered on library pages, stored with the GTK app's keys.
enum SortOrder: String, CaseIterable, Identifiable {
    case recentlyAdded = "recently-added"
    case alphabetic
    case creator
    case dateReleased = "date-released"
    case popularity

    var id: String { rawValue }

    var title: LocalizedStringKey {
        switch self {
        case .recentlyAdded: "Recently Added"
        case .alphabetic: "Alphabetical"
        case .creator: "Creator"
        case .dateReleased: "Release Date"
        case .popularity: "Popularity"
        }
    }

    func sort(_ items: [CardItem]) -> [CardItem] {
        switch self {
        case .recentlyAdded:
            return items
        case .alphabetic:
            return items.sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
        case .creator:
            return items.sorted { $0.subtitle.localizedStandardCompare($1.subtitle) == .orderedAscending }
        case .dateReleased:
            return items.sorted { year($0) > year($1) }
        case .popularity:
            return items.sorted { popularity($0) > popularity($1) }
        }
    }

    private func year(_ item: CardItem) -> Int32 {
        if case .album(let album) = item { return album.year ?? 0 }
        return 0
    }

    private func popularity(_ item: CardItem) -> UInt32 {
        switch item {
        case .album(let a): a.popularity ?? 0
        case .artist(let a): a.popularity ?? 0
        case .playlist: 0
        }
    }
}

/// Offset-paginated loading shared by the library pages.
@MainActor
@Observable
final class Paged<Item> {
    var items: [Item] = []
    var total: UInt32?
    var error: String?
    var loaded = false
    private var loading = false
    private let pageSize: UInt32
    private let fetch: (UInt32, UInt32) async throws -> ([Item], UInt32?)

    init(pageSize: UInt32 = 50, fetch: @escaping (UInt32, UInt32) async throws -> ([Item], UInt32?)) {
        self.pageSize = pageSize
        self.fetch = fetch
    }

    var hasMore: Bool {
        guard loaded else { return true }
        if let total { return items.count < Int(total) }
        return lastPageFull
    }
    private var lastPageFull = true

    func reload() async {
        items = []
        total = nil
        lastPageFull = true
        loaded = false
        error = nil
        await loadMore()
    }

    func loadMore() async {
        guard !loading, hasMore else { return }
        loading = true
        defer { loading = false }
        do {
            let (page, total) = try await fetch(UInt32(items.count), pageSize)
            items += page
            self.total = total
            lastPageFull = page.count >= Int(pageSize)
            loaded = true
        } catch {
            self.error = String(describing: error)
            lastPageFull = false
            loaded = true
        }
    }
}

struct SortPicker: View {
    @Binding var order: String

    var body: some View {
        Picker("Sort", selection: $order) {
            ForEach(SortOrder.allCases) { order in
                Text(order.title).tag(order.rawValue)
            }
        }
        .pickerStyle(.menu)
        .fixedSize()
    }
}

/// A grid of saved cards with sorting and a filter field.
private struct LibraryGrid: View {
    let title: LocalizedStringKey
    let items: [CardItem]
    let loaded: Bool
    let error: String?
    @Binding var sort: String
    let loadMore: () -> Void
    let reload: () -> Void
    @State private var filter = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if !loaded || (error != nil && items.isEmpty) {
                    LoadState(error: error, retry: reload)
                } else if items.isEmpty {
                    ContentUnavailableView("Nothing here yet", systemImage: "square.stack")
                } else {
                    CardGrid(items: shown, onLoadMore: loadMore)
                }
            }
            .padding(24)
        }
        .navigationTitle(title)
        .searchable(text: $filter, placement: .toolbar, prompt: "Filter")
        .toolbar {
            ToolbarItem { SortPicker(order: $sort) }
            ToolbarItem { CardSizePicker() }
        }
    }

    private var shown: [CardItem] {
        let sorted = (SortOrder(rawValue: sort) ?? .recentlyAdded).sort(items)
        guard !filter.isEmpty else { return sorted }
        return sorted.filter {
            $0.title.localizedCaseInsensitiveContains(filter)
                || $0.subtitle.localizedCaseInsensitiveContains(filter)
        }
    }
}

struct SavedAlbumsView: View {
    @Environment(AppModel.self) private var model
    @State private var paged: Paged<AlbumInfo>?

    var body: some View {
        @Bindable var model = model
        LibraryGrid(
            title: "Albums",
            items: paged?.items.map(CardItem.album) ?? [],
            loaded: paged?.loaded ?? false,
            error: paged?.error,
            sort: $model.settings.sortAlbums,
            loadMore: { Task { await paged?.loadMore() } },
            reload: { Task { await paged?.reload() } }
        )
        .task(id: model.libraryVersion) {
            let core = model.core
            let paged = paged ?? Paged { offset, limit in
                let page = try await core.savedAlbums(offset: offset, limit: limit)
                return (page.items, page.total)
            }
            self.paged = paged
            await paged.reload()
        }
    }
}

struct SavedPlaylistsView: View {
    @Environment(AppModel.self) private var model
    @State private var paged: Paged<PlaylistInfo>?

    var body: some View {
        @Bindable var model = model
        LibraryGrid(
            title: "Playlists",
            items: paged?.items.map(CardItem.playlist) ?? [],
            loaded: paged?.loaded ?? false,
            error: paged?.error,
            sort: $model.settings.sortPlaylists,
            loadMore: { Task { await paged?.loadMore() } },
            reload: { Task { await paged?.reload() } }
        )
        .toolbar {
            ToolbarItem {
                Button { model.playlistEditor = PlaylistEditorRequest(playlist: nil) } label: {
                    Label("New Playlist", systemImage: "plus")
                }
            }
        }
        .task(id: model.libraryVersion) {
            let core = model.core
            let paged = paged ?? Paged { offset, limit in
                let page = try await core.savedPlaylists(offset: offset, limit: limit)
                return (page.items, page.total)
            }
            self.paged = paged
            await paged.reload()
        }
    }
}

struct SavedArtistsView: View {
    @Environment(AppModel.self) private var model
    @State private var artists: [ArtistInfo] = []
    @State private var cursor: String?
    @State private var done = false
    @State private var loaded = false
    @State private var loading = false
    @State private var error: String?

    var body: some View {
        @Bindable var model = model
        LibraryGrid(
            title: "Artists",
            items: artists.map(CardItem.artist),
            loaded: loaded,
            error: error,
            sort: $model.settings.sortArtists,
            loadMore: { Task { await loadMore() } },
            reload: { Task { await reload() } }
        )
        .task(id: model.libraryVersion) { await reload() }
    }

    private func reload() async {
        artists = []
        cursor = nil
        done = false
        loaded = false
        error = nil
        await loadMore()
    }

    private func loadMore() async {
        guard !done, !loading else { return }
        loading = true
        defer { loading = false }
        do {
            let page = try await model.core.followedArtists(after: cursor, limit: 50)
            artists += page.items
            cursor = page.nextCursor
            done = page.nextCursor == nil || page.items.isEmpty
        } catch {
            self.error = String(describing: error)
            done = true
        }
        loaded = true
    }
}

struct SavedTracksView: View {
    @Environment(AppModel.self) private var model
    @State private var paged: Paged<TrackInfo>?
    @State private var filter = ""

    var body: some View {
        Group {
            if let paged, paged.loaded, !(paged.error != nil && paged.items.isEmpty) {
                VStack(spacing: 0) {
                    HStack(spacing: 12) {
                        Image(systemName: "heart.fill")
                            .font(.system(size: 36))
                            .foregroundStyle(.white)
                            .frame(width: 72, height: 72)
                            .background(
                                LinearGradient(colors: [.purple, .blue], startPoint: .topLeading, endPoint: .bottomTrailing),
                                in: RoundedRectangle(cornerRadius: 8)
                            )
                        VStack(alignment: .leading) {
                            Text("Liked Songs").font(.title.bold())
                            Text("\(paged.total ?? UInt32(paged.items.count)) songs")
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        PlayButtons(source: .savedTracks)
                    }
                    .padding(20)
                    TrackList(
                        tracks: shown(paged.items),
                        source: filter.isEmpty ? .savedTracks : nil,
                        total: filter.isEmpty ? paged.total : nil,
                        onLoadMore: { Task { await paged.loadMore() } }
                    )
                }
            } else {
                LoadState(error: paged?.error) { Task { await paged?.reload() } }
            }
        }
        .navigationTitle("Liked Songs")
        .searchable(text: $filter, placement: .toolbar, prompt: "Filter")
        .task(id: model.libraryVersion) {
            let core = model.core
            let paged = paged ?? Paged { offset, limit in
                let page = try await core.savedTracks(offset: offset, limit: limit)
                return (page.items, page.total)
            }
            self.paged = paged
            await paged.reload()
        }
    }

    private func shown(_ tracks: [TrackInfo]) -> [TrackInfo] {
        guard !filter.isEmpty else { return tracks }
        return tracks.filter {
            $0.title.localizedCaseInsensitiveContains(filter)
                || $0.artistNames.localizedCaseInsensitiveContains(filter)
                || ($0.album?.name.localizedCaseInsensitiveContains(filter) ?? false)
        }
    }
}

/// Play and Shuffle buttons for a whole source.
struct PlayButtons: View {
    @Environment(AppModel.self) private var model
    let source: PlaySource

    var body: some View {
        HStack {
            Button { model.play(source: source) } label: {
                Label("Play", systemImage: "play.fill")
            }
            .buttonStyle(.borderedProminent)
            Button { model.play(source: source, shuffle: true) } label: {
                Label("Shuffle", systemImage: "shuffle")
            }
        }
        .controlSize(.large)
    }
}

struct CardSizePicker: View {
    @AppStorage(CardSize.key) private var size = CardSize.large

    var body: some View {
        Picker("Card Size", selection: $size) {
            Label("Small", systemImage: "square.grid.3x3").tag(CardSize.small)
            Label("Large", systemImage: "square.grid.2x2").tag(CardSize.large)
        }
        .pickerStyle(.segmented)
        .labelStyle(.iconOnly)
        .help("Card size")
    }
}

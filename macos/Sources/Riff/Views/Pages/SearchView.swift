import RiffCore
import SwiftUI

/// Search with an overview of all kinds and a scope per kind, like the GTK
/// search page and its scope buttons.
struct SearchView: View {
    @Environment(AppModel.self) private var model
    @State private var query = ""
    @State private var scope: Scope = .all
    @State private var results: SearchResult?
    @State private var scoped: Paged<CardItem>?
    @State private var scopedTracks: Paged<TrackInfo>?
    @State private var error: String?
    @State private var searching = false

    enum Scope: String, CaseIterable, Identifiable {
        case all, tracks, albums, artists, playlists
        var id: String { rawValue }
        var title: LocalizedStringKey {
            switch self {
            case .all: "All"
            case .tracks: "Songs"
            case .albums: "Albums"
            case .artists: "Artists"
            case .playlists: "Playlists"
            }
        }
    }

    var body: some View {
        content
            .navigationTitle("Search")
            .searchable(text: $query, placement: .toolbar, prompt: "Artists, songs, albums or links")
            .searchScopes($scope) {
                ForEach(Scope.allCases) { Text($0.title).tag($0) }
            }
            .task(id: "\(query)|\(scope.rawValue)") { await search() }
    }

    @ViewBuilder
    private var content: some View {
        if query.trimmingCharacters(in: .whitespaces).isEmpty {
            ContentUnavailableView(
                "Search Spotify",
                systemImage: "magnifyingglass",
                description: Text("Find songs, albums, artists and playlists. Paste a Spotify link to open it.")
            )
        } else if let error {
            LoadState(error: error) { Task { await search() } }
        } else if scope == .all, let results {
            overview(results)
        } else if scope == .tracks, let scopedTracks {
            TrackList(
                tracks: scopedTracks.items,
                source: .search(query: query),
                onLoadMore: { Task { await scopedTracks.loadMore() } }
            )
        } else if let scoped, scope != .all, scope != .tracks {
            ScrollView {
                CardGrid(items: scoped.items) { Task { await scoped.loadMore() } }
                    .padding(24)
            }
        } else {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private func overview(_ results: SearchResult) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                if results.tracks.isEmpty && results.albums.isEmpty && results.artists.isEmpty && results.playlists.isEmpty {
                    ContentUnavailableView.search(text: query)
                }
                if !results.tracks.isEmpty {
                    VStack(alignment: .leading) {
                        HStack {
                            Text("Songs").font(.title2.bold())
                            Spacer()
                            Button("Show All") { scope = .tracks }.buttonStyle(.link)
                        }
                        TrackList(tracks: Array(results.tracks.prefix(8)), source: .search(query: query))
                            .frame(height: CGFloat(min(results.tracks.count, 8)) * 32 + 32)
                    }
                }
                CardShelf(title: String(localized: "Artists"), items: results.artists.map(CardItem.artist))
                CardShelf(title: String(localized: "Albums"), items: results.albums.map(CardItem.album))
                CardShelf(title: String(localized: "Playlists"), items: results.playlists.map(CardItem.playlist))
            }
            .padding(24)
        }
    }

    private func search() async {
        let text = query.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else {
            results = nil
            return
        }
        // Pasted links open the page instead of searching.
        if model.open(link: text) {
            query = ""
            return
        }
        // Debounce typing.
        try? await Task.sleep(for: .milliseconds(300))
        if Task.isCancelled { return }
        _ = model.core.bumpEpoch()
        error = nil
        let core = model.core
        do {
            switch scope {
            case .all:
                results = try await core.search(query: text, limit: 20)
            case .tracks:
                let paged = Paged<TrackInfo> { offset, limit in
                    let r = try await core.searchScoped(query: text, scope: .tracks, offset: offset, limit: limit)
                    return (r.tracks, r.totalTracks)
                }
                await paged.reload()
                scopedTracks = paged
            case .albums, .artists, .playlists:
                let kind: SearchScope = scope == .albums ? .albums : scope == .artists ? .artists : .playlists
                let paged = Paged<CardItem> { offset, limit in
                    let r = try await core.searchScoped(query: text, scope: kind, offset: offset, limit: limit)
                    switch kind {
                    case .albums: return (r.albums.map(CardItem.album), r.totalAlbums)
                    case .artists: return (r.artists.map(CardItem.artist), r.totalArtists)
                    default: return (r.playlists.map(CardItem.playlist), r.totalPlaylists)
                    }
                }
                await paged.reload()
                scoped = paged
            }
        } catch {
            if !Task.isCancelled { self.error = String(describing: error) }
        }
    }
}

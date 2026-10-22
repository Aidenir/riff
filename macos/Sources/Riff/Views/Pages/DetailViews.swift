import AppKit
import RiffCore
import SwiftUI

/// Big artwork, title and actions at the top of album, playlist, artist and
/// user pages. Mirrors the GTK details page header.
struct DetailHeader<Actions: View>: View {
    let kind: LocalizedStringKey
    let title: String
    let art: Artwork
    var circle = false
    var subtitle: AnyView?
    var details: [String] = []
    var description: String?
    @ViewBuilder var actions: Actions

    var body: some View {
        HStack(alignment: .bottom, spacing: 24) {
            ArtworkView(url: art.large ?? art.medium, shape: circle ? .circle : .rounded, priority: .hero)
                .frame(width: 200, height: 200)
                .shadow(radius: 8)
            VStack(alignment: .leading, spacing: 8) {
                Text(kind)
                    .font(.caption.weight(.semibold))
                    .textCase(.uppercase)
                    .foregroundStyle(.secondary)
                Text(title)
                    .font(.system(size: 34, weight: .bold))
                    .lineLimit(2)
                    .textSelection(.enabled)
                if let subtitle { subtitle.font(.title3) }
                if let description, !description.isEmpty {
                    Text(stripTags(description))
                        .foregroundStyle(.secondary)
                        .lineLimit(3)
                }
                if !details.isEmpty {
                    Text(details.joined(separator: " · "))
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }
                HStack(spacing: 10) { actions }
                    .padding(.top, 6)
            }
            Spacer(minLength: 0)
        }
        .padding(24)
        .background { ArtTintBackground(url: art.medium ?? art.large) }
    }

    private func stripTags(_ html: String) -> String {
        guard html.contains("<") || html.contains("&"),
              let data = html.data(using: .utf8),
              let attributed = try? NSAttributedString(
                data: data,
                options: [.documentType: NSAttributedString.DocumentType.html, .characterEncoding: String.Encoding.utf8.rawValue],
                documentAttributes: nil
              )
        else { return html }
        return attributed.string
    }
}

// MARK: Album

struct AlbumView: View {
    @Environment(AppModel.self) private var model
    let id: String
    @State private var album: AlbumInfo?
    @State private var tracks: Paged<TrackInfo>?
    @State private var error: String?

    var body: some View {
        Group {
            if let album, let tracks {
                VStack(spacing: 0) {
                    DetailHeader(
                        kind: LocalizedStringKey(album.albumType),
                        title: album.title,
                        art: album.art,
                        subtitle: AnyView(ArtistLinks(artists: album.artists)),
                        details: details(album, tracks.items)
                    ) {
                        PlayButtons(source: .album(id: id))
                        SaveButton(saved: album.saved ?? false) { saved in
                            self.album?.saved = saved
                            Task {
                                do { try await model.core.setAlbumSaved(id: id, saved: saved) } catch {
                                    self.album?.saved = !saved
                                    model.report(error)
                                }
                            }
                        }
                        ShareMenu(url: album.url ?? "https://open.spotify.com/album/\(id)", pin: PinnedItem(kind: .album, id: id, title: album.title))
                    }
                    TrackList(
                        tracks: tracks.items,
                        source: .album(id: id),
                        total: tracks.total,
                        showAlbum: false,
                        showArtwork: false,
                        showNumbers: true,
                        onLoadMore: { Task { await tracks.loadMore() } }
                    )
                    if let copyright = album.copyright {
                        Text(copyright)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(8)
                    }
                }
            } else {
                LoadState(error: error) { Task { await load() } }
            }
        }
        .navigationTitle(album?.title ?? "")
        .task(id: id) { await load() }
    }

    private func details(_ album: AlbumInfo, _ tracks: [TrackInfo]) -> [String] {
        var parts: [String] = []
        if let date = album.releaseDate { parts.append(date) }
        if let count = album.totalTracks { parts.append(String(localized: "\(count) songs")) }
        if !tracks.isEmpty { parts.append(formatTotalDuration(tracks)) }
        if let label = album.label { parts.append(label) }
        return parts
    }

    private func load() async {
        _ = model.core.bumpEpoch()
        error = nil
        do {
            album = try await model.core.album(id: id)
            let core = model.core
            let id = id
            let paged = Paged<TrackInfo> { offset, limit in
                let page = try await core.albumTracks(id: id, offset: offset, limit: limit)
                return (page.items, page.total)
            }
            await paged.reload()
            tracks = paged
        } catch {
            self.error = String(describing: error)
        }
    }
}

// MARK: Playlist

struct PlaylistView: View {
    @Environment(AppModel.self) private var model
    let id: String
    @State private var playlist: PlaylistInfo?
    @State private var tracks: Paged<TrackInfo>?
    @State private var suggestions: [TrackInfo] = []
    @State private var error: String?

    var body: some View {
        Group {
            if let playlist, let tracks {
                VStack(spacing: 0) {
                    DetailHeader(
                        kind: "Playlist",
                        title: playlist.title,
                        art: playlist.art,
                        subtitle: owner(playlist),
                        details: details(playlist, tracks),
                        description: playlist.description
                    ) {
                        PlayButtons(source: .playlist(id: id))
                        if model.canEdit(playlist) {
                            Button {
                                model.playlistEditor = PlaylistEditorRequest(playlist: playlist)
                            } label: {
                                Label("Edit", systemImage: "pencil")
                            }
                            .controlSize(.large)
                        } else {
                            SaveButton(saved: playlist.following ?? false, savedLabel: "Following", unsavedLabel: "Follow") { follow in
                                self.playlist?.following = follow
                                Task {
                                    do { try await model.core.setPlaylistFollowed(id: id, follow: follow) } catch {
                                        self.playlist?.following = !follow
                                        model.report(error)
                                    }
                                }
                            }
                        }
                        ShareMenu(url: playlist.url ?? "https://open.spotify.com/playlist/\(id)", pin: PinnedItem(kind: .playlist, id: id, title: playlist.title))
                    }
                    TrackList(
                        tracks: tracks.items,
                        source: .playlist(id: id),
                        total: tracks.total,
                        playlistId: id,
                        onLoadMore: { Task { await tracks.loadMore() } }
                    )
                    if model.canEdit(playlist), !suggestions.isEmpty, !tracks.hasMore {
                        Recommendations(playlistId: id, tracks: $suggestions)
                    }
                }
            } else {
                LoadState(error: error) { Task { await load() } }
            }
        }
        .navigationTitle(playlist?.title ?? "")
        .task(id: "\(id)-\(model.libraryVersion)") { await load() }
        .task { await model.loadOwnedPlaylistsIfNeeded() }
    }

    private func owner(_ playlist: PlaylistInfo) -> AnyView? {
        guard let owner = playlist.owner else { return nil }
        return AnyView(
            Button(owner.name) { model.navigation.push(.user(owner.id)) }
                .buttonStyle(.plain)
        )
    }

    private func details(_ playlist: PlaylistInfo, _ tracks: Paged<TrackInfo>) -> [String] {
        var parts: [String] = []
        if let total = tracks.total ?? playlist.totalTracks { parts.append(String(localized: "\(total) songs")) }
        if !tracks.items.isEmpty { parts.append(formatTotalDuration(tracks.items)) }
        if playlist.collaborative == true { parts.append(String(localized: "Collaborative")) }
        if let isPublic = playlist.public { parts.append(isPublic ? String(localized: "Public") : String(localized: "Private")) }
        return parts
    }

    private func load() async {
        _ = model.core.bumpEpoch()
        error = nil
        do {
            playlist = try await model.core.playlist(id: id)
            let core = model.core
            let id = id
            let paged = Paged<TrackInfo>(pageSize: 100) { offset, limit in
                let page = try await core.playlistTracks(id: id, offset: offset, limit: limit)
                return (page.items, page.total)
            }
            await paged.reload()
            tracks = paged
            if let playlist, model.canEdit(playlist) {
                suggestions = (try? await core.playlistSuggestions(
                    id: id, skip: paged.items.map(\.id), count: 10
                )) ?? []
            }
        } catch {
            self.error = String(describing: error)
        }
    }
}

/// "Recommended songs" under an editable playlist, with one-click adding.
struct Recommendations: View {
    @Environment(AppModel.self) private var model
    let playlistId: String
    @Binding var tracks: [TrackInfo]

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Recommended Songs").font(.title3.bold())
            Text("Based on what's in this playlist")
                .font(.callout)
                .foregroundStyle(.secondary)
            ForEach(tracks, id: \.id) { track in
                HStack {
                    ArtworkView(url: track.art.small, priority: .offscreen)
                        .frame(width: 32, height: 32)
                    VStack(alignment: .leading) {
                        Text(track.title).lineLimit(1)
                        Text(track.artistNames).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                    Spacer()
                    Button("Add") {
                        tracks.removeAll { $0.id == track.id }
                        Task {
                            do {
                                _ = try await model.core.addToPlaylist(id: playlistId, trackIds: [track.id])
                            } catch { model.report(error) }
                        }
                    }
                }
                .contextMenu { TrackMenu(tracks: [track]) }
                .onTapGesture(count: 2) { model.play([track], from: nil, startingAt: track) }
            }
        }
        .padding(16)
        .frame(maxHeight: 280)
    }
}

// MARK: Artist

struct ArtistView: View {
    @Environment(AppModel.self) private var model
    let id: String
    @State private var artist: ArtistInfo?
    @State private var topTracks: [TrackInfo] = []
    @State private var albums: Paged<AlbumInfo>?
    @State private var error: String?

    var body: some View {
        Group {
            if let artist {
                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        DetailHeader(
                            kind: "Artist",
                            title: artist.name,
                            art: artist.art,
                            circle: true,
                            details: details(artist),
                            description: artist.bio
                        ) {
                            PlayButtons(source: .artist(id: id))
                            SaveButton(saved: artist.following ?? false, savedLabel: "Following", unsavedLabel: "Follow") { follow in
                                self.artist?.following = follow
                                Task {
                                    do { try await model.core.setArtistFollowed(id: id, follow: follow) } catch {
                                        self.artist?.following = !follow
                                        model.report(error)
                                    }
                                }
                            }
                            ShareMenu(url: artist.url ?? "https://open.spotify.com/artist/\(id)", pin: PinnedItem(kind: .artist, id: id, title: artist.name))
                        }
                        if !topTracks.isEmpty {
                            VStack(alignment: .leading) {
                                Text("Popular").font(.title2.bold())
                                TrackList(tracks: topTracks, source: .artist(id: id), showAlbum: true)
                                    .frame(height: CGFloat(min(topTracks.count, 10)) * 32 + 32)
                            }
                            .padding(.horizontal, 24)
                        }
                        if let albums {
                            ForEach(groups(albums.items), id: \.0) { group in
                                VStack(alignment: .leading, spacing: 10) {
                                    Text(group.0).font(.title2.bold())
                                    CardGrid(items: group.1.map(CardItem.album)) {
                                        Task { await albums.loadMore() }
                                    }
                                }
                                .padding(.horizontal, 24)
                            }
                        }
                    }
                    .padding(.bottom, 24)
                }
            } else {
                LoadState(error: error) { Task { await load() } }
            }
        }
        .navigationTitle(artist?.name ?? "")
        .task(id: id) { await load() }
    }

    private func groups(_ albums: [AlbumInfo]) -> [(String, [AlbumInfo])] {
        let order = ["Album", "Single", "EP", "Compilation"]
        let grouped = Dictionary(grouping: albums, by: \.albumType)
        let titles: [String: String] = [
            "Album": String(localized: "Albums"),
            "Single": String(localized: "Singles"),
            "EP": String(localized: "EPs"),
            "Compilation": String(localized: "Compilations"),
        ]
        let known = order.compactMap { kind in grouped[kind].map { (titles[kind] ?? kind, $0) } }
        let other = grouped.filter { !order.contains($0.key) }.sorted { $0.key < $1.key }.map { ($0.key, $0.value) }
        return known + other
    }

    private func details(_ artist: ArtistInfo) -> [String] {
        var parts: [String] = []
        if let followers = artist.followerCount {
            parts.append(String(localized: "\(formatCount(followers)) followers"))
        }
        if !artist.genres.isEmpty { parts.append(artist.genres.prefix(3).joined(separator: ", ")) }
        return parts
    }

    private func load() async {
        _ = model.core.bumpEpoch()
        error = nil
        do {
            artist = try await model.core.artist(id: id)
            topTracks = (try? await model.core.artistTopTracks(id: id)) ?? []
            let core = model.core
            let id = id
            let paged = Paged<AlbumInfo> { offset, limit in
                let page = try await core.artistAlbums(id: id, offset: offset, limit: limit)
                return (page.items, page.total)
            }
            await paged.reload()
            albums = paged
        } catch {
            self.error = String(describing: error)
        }
    }
}

// MARK: User

struct UserView: View {
    @Environment(AppModel.self) private var model
    let id: String
    @State private var user: UserInfo?
    @State private var playlists: Paged<PlaylistInfo>?
    @State private var error: String?

    var body: some View {
        Group {
            if let user {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        DetailHeader(
                            kind: "Profile",
                            title: user.displayName,
                            art: user.art,
                            circle: true,
                            details: user.followerCount.map { [String(localized: "\(formatCount($0)) followers")] } ?? []
                        ) {
                            ShareMenu(url: user.url ?? "https://open.spotify.com/user/\(id)", pin: nil)
                        }
                        if let playlists {
                            Text("Public Playlists").font(.title2.bold()).padding(.horizontal, 24)
                            CardGrid(items: playlists.items.map(CardItem.playlist)) {
                                Task { await playlists.loadMore() }
                            }
                            .padding(.horizontal, 24)
                        }
                    }
                }
            } else {
                LoadState(error: error) { Task { await load() } }
            }
        }
        .navigationTitle(user?.displayName ?? "")
        .task(id: id) { await load() }
    }

    private func load() async {
        _ = model.core.bumpEpoch()
        error = nil
        do {
            user = try await model.core.user(id: id)
            let core = model.core
            let id = id
            let paged = Paged<PlaylistInfo> { offset, limit in
                let page = try await core.userPlaylists(id: id, offset: offset, limit: limit)
                return (page.items, page.total)
            }
            await paged.reload()
            playlists = paged
        } catch {
            self.error = String(describing: error)
        }
    }
}

// MARK: Shared controls

struct SaveButton: View {
    let saved: Bool
    var savedLabel: LocalizedStringKey = "Saved"
    var unsavedLabel: LocalizedStringKey = "Save"
    let toggle: (Bool) -> Void

    var body: some View {
        Button { toggle(!saved) } label: {
            Label(saved ? savedLabel : unsavedLabel, systemImage: saved ? "checkmark" : "plus")
        }
        .controlSize(.large)
    }
}

struct ShareMenu: View {
    @Environment(AppModel.self) private var model
    let url: String
    let pin: PinnedItem?

    var body: some View {
        Menu {
            Button("Copy Link") { model.copyLink(url) }
            if let pin, model.settings.featurePinnedObjects {
                Button(model.isPinned(pin) ? "Unpin from Sidebar" : "Pin to Sidebar") {
                    model.togglePin(pin)
                }
            }
            ShareLink(item: URL(string: url)!)
        } label: {
            Image(systemName: "ellipsis")
        }
        .menuIndicator(.hidden)
        .fixedSize()
        .controlSize(.large)
    }
}

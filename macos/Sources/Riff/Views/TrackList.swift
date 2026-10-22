import RiffCore
import SwiftUI

/// A track at a position in a list; playlists may hold the same track twice.
struct TrackRow: Identifiable, Hashable {
    let id: Int
    let track: TrackInfo
}

/// A sortable-looking table of tracks with multi-selection, double-click to
/// play and the shared track context menu. Mirrors the GTK track list.
struct TrackList: View {
    @Environment(AppModel.self) private var model

    let tracks: [TrackInfo]
    var source: PlaySource?
    var total: UInt32?
    var showAlbum = true
    var showArtwork = true
    var showNumbers = false
    var playlistId: String?
    var onLoadMore: (() -> Void)?

    @State private var selection = Set<TrackRow.ID>()

    private var rows: [TrackRow] {
        tracks.enumerated().map { TrackRow(id: $0.offset, track: $0.element) }
    }

    var body: some View {
        let rows = rows
        Table(rows, selection: $selection) {
            TableColumn("#") { row in
                indexCell(row)
            }
            .width(32)
            TableColumn("Title") { row in
                titleCell(row)
                    .onAppear {
                        if row.id == rows.count - 1 { onLoadMore?() }
                    }
            }
            .width(min: 160, ideal: 280)
            TableColumn("Artist") { row in
                Text(row.track.artistNames)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            .width(min: 100, ideal: 180)
            if showAlbum {
                TableColumn("Album") { row in
                    Text(row.track.album?.name ?? "")
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                .width(min: 100, ideal: 180)
            }
            TableColumn("") { row in
                LikeButton(track: row.track)
            }
            .width(24)
            TableColumn("Time") { row in
                Text(formatDuration(Double(row.track.durationMs)))
                    .monospacedDigit()
                    .foregroundStyle(.secondary)
            }
            .width(48)
        }
        .contextMenu(forSelectionType: TrackRow.ID.self) { ids in
            TrackMenu(tracks: selected(ids), playlistId: playlistId)
        } primaryAction: { ids in
            if let first = ids.min() { play(rows[first].track) }
        }
        .onKeyPress(.return) {
            if let first = selection.min() { play(rows[first].track) }
            return .handled
        }
    }

    private func selected(_ ids: Set<Int>) -> [TrackInfo] {
        ids.sorted().compactMap { $0 < tracks.count ? tracks[$0] : nil }
    }

    private func play(_ track: TrackInfo) {
        guard track.playable else { return }
        model.play(tracks, from: source, startingAt: track, total: total)
    }

    @ViewBuilder
    private func indexCell(_ row: TrackRow) -> some View {
        if model.isCurrent(row.track) {
            Image(systemName: model.playback.isPlaying ? "speaker.wave.2.fill" : "speaker.fill")
                .foregroundStyle(Color.accentColor)
        } else {
            Text("\(showNumbers ? Int(row.track.trackNumber ?? UInt32(row.id + 1)) : row.id + 1)")
                .monospacedDigit()
                .foregroundStyle(.secondary)
        }
    }

    private func titleCell(_ row: TrackRow) -> some View {
        HStack(spacing: 8) {
            if showArtwork {
                ArtworkView(url: row.track.art.small, shape: .rounded, priority: .offscreen)
                    .frame(width: 28, height: 28)
            }
            Text(row.track.title)
                .lineLimit(1)
                .foregroundStyle(
                    model.isCurrent(row.track) ? Color.accentColor
                        : row.track.playable ? Color.primary : Color.secondary
                )
            if row.track.explicit {
                Text("E")
                    .font(.caption2.bold())
                    .padding(.horizontal, 3)
                    .background(.quaternary, in: RoundedRectangle(cornerRadius: 2))
                    .help("Explicit")
            }
        }
    }
}

/// Actions for one or more tracks: the GTK track row menu and selection bar.
struct TrackMenu: View {
    @Environment(AppModel.self) private var model
    let tracks: [TrackInfo]
    var playlistId: String?

    var body: some View {
        if let track = tracks.first {
            if tracks.count == 1 {
                Button("Play") { model.play([track], from: nil, startingAt: track) }
            }
            Button("Add to Queue") { model.enqueue(tracks) }
            Divider()
            let liked = tracks.allSatisfy(model.isLiked)
            Button(liked ? "Remove from Liked Songs" : "Save to Liked Songs") {
                model.setLiked(tracks, !liked)
            }
            AddToPlaylistMenu(tracks: tracks)
            if let playlistId, model.ownsPlaylist(playlistId) {
                Button("Remove from This Playlist") {
                    Task {
                        do {
                            try await model.core.removeFromPlaylist(id: playlistId, trackIds: tracks.map(\.id))
                        } catch { model.report(error) }
                    }
                }
            }
            if tracks.count == 1 {
                Divider()
                if let album = track.album, !album.id.isEmpty {
                    Button("Go to Album") { model.navigation.push(.album(album.id)) }
                }
                ForEach(track.artists.filter { !$0.id.isEmpty }, id: \.id) { artist in
                    Button("Go to \(artist.name)") { model.navigation.push(.artist(artist.id)) }
                }
                Button("Start Radio") { model.play(source: .radio(trackId: track.id)) }
                Divider()
                Button("Copy Link") { model.copyLink(track.url ?? "https://open.spotify.com/track/\(track.id)") }
            }
        }
    }
}

struct AddToPlaylistMenu: View {
    @Environment(AppModel.self) private var model
    let tracks: [TrackInfo]

    var body: some View {
        Menu("Add to Playlist") {
            Button("New Playlist…") {
                model.playlistEditor = PlaylistEditorRequest(playlist: nil, tracksToAdd: tracks)
            }
            Divider()
            ForEach(model.ownedPlaylists, id: \.id) { playlist in
                Button(playlist.title) { model.add(tracks, to: playlist) }
            }
        }
        .task { await model.loadOwnedPlaylistsIfNeeded() }
    }
}

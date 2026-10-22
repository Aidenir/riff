import Foundation
import RiffCore

/// What the playlist editor sheet works on.
struct PlaylistEditorRequest: Identifiable {
    let id = UUID()
    /// The playlist to edit, or nil to create one.
    let playlist: PlaylistInfo?
    /// Tracks to add to the new playlist once created.
    var tracksToAdd: [TrackInfo] = []
}

extension AppModel {
    func ownsPlaylist(_ id: String) -> Bool {
        ownedPlaylists.contains { $0.id == id }
    }

    func canEdit(_ playlist: PlaylistInfo) -> Bool {
        guard let user else { return false }
        return playlist.owner?.id == user.id || playlist.collaborative == true
    }

    func loadOwnedPlaylistsIfNeeded() async {
        guard ownedPlaylistsVersion != libraryVersion else { return }
        ownedPlaylistsVersion = libraryVersion
        if user == nil { user = try? await core.currentUser() }
        var playlists: [PlaylistInfo] = []
        var offset: UInt32 = 0
        while let page = try? await core.savedPlaylists(offset: offset, limit: 50) {
            playlists += page.items.filter(canEdit)
            offset += UInt32(page.items.count)
            if page.items.isEmpty || offset >= (page.total ?? 0) { break }
        }
        ownedPlaylists = playlists
    }

    func add(_ tracks: [TrackInfo], to playlist: PlaylistInfo) {
        Task {
            do {
                let added = try await core.addToPlaylist(id: playlist.id, trackIds: tracks.map(\.id))
                if added == 0 {
                    show(String(localized: "Already in \(playlist.title)"))
                } else {
                    show(String(localized: "Added to \(playlist.title)"))
                }
            } catch {
                report(error)
            }
        }
    }

    // MARK: Pinning

    func isPinned(_ item: PinnedItem) -> Bool {
        settings.pinned.contains { PinnedItem(encoded: $0)?.id == item.id }
    }

    func togglePin(_ item: PinnedItem) {
        if isPinned(item) {
            settings.pinned.removeAll { PinnedItem(encoded: $0)?.id == item.id }
        } else {
            settings.pinned.append(item.encoded)
        }
    }
}

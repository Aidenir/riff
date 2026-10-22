import Foundation
import Observation

/// Top-level sections in the sidebar, matching the GTK navigation panel.
enum SidebarItem: Hashable {
    case home
    case search
    case nowPlaying
    case savedAlbums
    case savedPlaylists
    case savedTracks
    case savedArtists
    case playlist(String)
    case pinned(PinnedItem)
}

/// Something pinned to the sidebar (feature flag `PinnedObjects`), stored as
/// `kind:id:title` strings in the settings.
struct PinnedItem: Hashable {
    enum Kind: String { case playlist, album, artist }
    let kind: Kind
    let id: String
    let title: String

    init(kind: Kind, id: String, title: String) {
        self.kind = kind
        self.id = id
        self.title = title
    }

    init?(encoded: String) {
        let parts = encoded.split(separator: ":", maxSplits: 2).map(String.init)
        guard parts.count >= 2, let kind = Kind(rawValue: parts[0]) else { return nil }
        self.init(kind: kind, id: parts[1], title: parts.count > 2 ? parts[2] : parts[1])
    }

    var encoded: String { "\(kind.rawValue):\(id):\(title)" }

    var route: Route {
        switch kind {
        case .playlist: .playlist(id)
        case .album: .album(id)
        case .artist: .artist(id)
        }
    }
}

/// Pages pushed on top of a sidebar section.
enum Route: Hashable {
    case album(String)
    case artist(String)
    case playlist(String)
    case user(String)
    case lyrics
    case queue
}

/// Navigation history: a sidebar selection plus a stack of pages, with
/// forward history like the GTK navigator in riff-core.
@MainActor
@Observable
final class Navigator {
    var selection: SidebarItem? = .home {
        didSet {
            if selection != oldValue {
                path = []
                forward = []
            }
        }
    }
    var path: [Route] = [] {
        didSet {
            // A push that is not a redo clears the forward history.
            if path.count > oldValue.count {
                if !redoing { forward = [] }
            } else if path.count < oldValue.count, let popped = oldValue.last {
                forward.append(popped)
            }
        }
    }
    private(set) var forward: [Route] = []
    @ObservationIgnored private var redoing = false

    func push(_ route: Route) {
        if case .pinned = selection {} else if selection == nil { selection = .home }
        path.append(route)
    }

    var canGoBack: Bool { !path.isEmpty }
    var canGoForward: Bool { !forward.isEmpty }

    func back() {
        guard !path.isEmpty else { return }
        path.removeLast()
    }

    func goForward() {
        guard let next = forward.popLast() else { return }
        redoing = true
        path.append(next)
        redoing = false
    }
}

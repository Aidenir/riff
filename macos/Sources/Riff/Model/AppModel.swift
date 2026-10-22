import AppKit
import Foundation
import Observation
import RiffCore

/// Where the user is in the login flow.
enum Session: Equatable {
    case starting
    case loggedOut
    case waitingForBrowser
    case loggedIn(username: String)
}

/// A short message shown at the bottom of the window.
struct Toast: Identifiable, Equatable {
    let id = UUID()
    let message: String
}

/// Single source of truth for the UI, fed by events from the Rust core.
///
/// Mirrors the GTK `AppState`: views read it, and send everything that
/// changes playback or the library through the core.
@MainActor
@Observable
final class AppModel {
    let core: RiffCore
    private let bridge: EventBridge

    var session: Session = .starting
    var loginError: String?
    var user: UserInfo?

    var playback: PlaybackSnapshot
    /// When `playback.positionMs` was reported, to keep the clock running.
    var positionAnchor = Date()
    var queue: QueueSnapshot

    var settings: AppSettings {
        didSet {
            if settings != oldValue { core.updateSettings(settings: settings) }
        }
    }

    var toasts: [Toast] = []
    var connectionLost = false
    var showDrmBlocked = false
    var showNotPremium = false

    /// Bumped whenever the library changes, so pages reload.
    var libraryVersion = 0
    /// Like state changed locally, keyed by track id, until pages reload.
    var likedOverrides: [String: Bool] = [:]

    let navigation = Navigator()

    /// Playlists the user can add tracks to, for "Add to Playlist" menus.
    var ownedPlaylists: [PlaylistInfo] = []
    @ObservationIgnored var ownedPlaylistsVersion = -1
    /// Create or edit a playlist, shown as a sheet over the main window.
    var playlistEditor: PlaylistEditorRequest?

    var showQueuePanel: Bool {
        get { settings.queueSidePanel }
        set { settings.queueSidePanel = newValue }
    }

    init() {
        let bridge = EventBridge()
        self.bridge = bridge
        core = RiffCore(listener: bridge)
        playback = core.playback()
        queue = core.queue()
        settings = core.settings()
        bridge.model = self
    }

    func start() {
        core.restoreSession()
    }

    // MARK: Events

    func handle(_ event: CoreEvent) {
        switch event {
        case .openLoginUrl(let url):
            session = .waitingForBrowser
            if let url = URL(string: url) { NSWorkspace.shared.open(url) }
        case .loggedIn(let username):
            loginError = nil
            session = .loggedIn(username: username)
            Task { user = try? await core.currentUser() }
        case .loggedOut:
            session = .loggedOut
            user = nil
        case .loginFailed:
            session = .loggedOut
            loginError = String(localized: "Login failed!")
        case .notPremium:
            session = .loggedOut
            showNotPremium = true
        case .drmBlocked:
            showDrmBlocked = true
        case .connectionLost(let lost):
            connectionLost = lost
        case .notification(let message):
            show(message)
        case .playbackChanged(let snapshot):
            playback = snapshot
            positionAnchor = Date()
        case .queueChanged(let snapshot):
            queue = snapshot
        case .libraryChanged:
            libraryVersion += 1
        }
    }

    func show(_ message: String) {
        let toast = Toast(message: message)
        toasts.append(toast)
        Task {
            try? await Task.sleep(for: .seconds(3))
            toasts.removeAll { $0.id == toast.id }
        }
    }

    /// Reports a failed core call as a toast.
    func report(_ error: Error) {
        show(String(describing: error))
    }

    // MARK: Playback helpers

    /// Current position, extrapolated while playing.
    func position(at date: Date = Date()) -> Double {
        let base = Double(playback.positionMs)
        guard playback.isPlaying else { return base }
        let elapsed = date.timeIntervalSince(positionAnchor) * 1000
        let duration = Double(playback.current?.durationMs ?? UInt32.max)
        return min(base + elapsed, duration)
    }

    func isCurrent(_ track: TrackInfo) -> Bool {
        playback.current?.id == track.id
    }

    func isLiked(_ track: TrackInfo) -> Bool {
        likedOverrides[track.id] ?? track.saved ?? false
    }

    func setLiked(_ tracks: [TrackInfo], _ liked: Bool) {
        for track in tracks { likedOverrides[track.id] = liked }
        let ids = tracks.map(\.id)
        Task {
            do {
                try await core.setTracksSaved(ids: ids, saved: liked)
            } catch {
                for id in ids { likedOverrides[id] = !liked }
                report(error)
            }
        }
    }

    func play(_ tracks: [TrackInfo], from source: PlaySource?, startingAt track: TrackInfo?, total: UInt32? = nil) {
        core.playTracks(
            source: source,
            trackIds: tracks.map(\.id),
            total: total,
            startId: track?.id,
            shuffle: nil
        )
    }

    func play(source: PlaySource, shuffle: Bool = false) {
        Task {
            do { try await core.playSource(source: source, shuffle: shuffle) } catch { report(error) }
        }
    }

    func enqueue(_ tracks: [TrackInfo]) {
        core.enqueue(trackIds: tracks.map(\.id))
    }

    func copyLink(_ url: String?) {
        guard let url else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(url, forType: .string)
        show(String(localized: "Link copied"))
    }

    // MARK: Links

    /// Opens a Spotify link or URI inside the app. Returns whether it was one.
    @discardableResult
    func open(link: String) -> Bool {
        guard let link = parseSpotifyLink(link: link) else { return false }
        switch link.kind {
        case .album: navigation.push(.album(link.id))
        case .artist: navigation.push(.artist(link.id))
        case .playlist: navigation.push(.playlist(link.id))
        case .user: navigation.push(.user(link.id))
        case .track:
            Task {
                do {
                    let track = try await core.track(id: link.id)
                    if let album = track.album {
                        navigation.push(.album(album.id))
                    }
                } catch { report(error) }
            }
        }
        return true
    }
}

/// Receives core events on background threads and forwards them to the model.
final class EventBridge: Listener, @unchecked Sendable {
    weak var model: AppModel?

    func onEvent(event: CoreEvent) {
        Task { @MainActor [weak self] in
            self?.model?.handle(event)
        }
    }
}

import RiffCore
import SwiftUI

struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        Group {
            switch model.session {
            case .loggedIn:
                MainView()
            case .starting:
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            case .loggedOut, .waitingForBrowser:
                LoginView()
            }
        }
        .overlay(alignment: .bottom) { ToastStack() }
        .overlay(alignment: .top) { ClipboardLinkBanner() }
        .alert("Playback Unavailable", isPresented: $model.showDrmBlocked) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("Spotify refuses to stream to this account. Some accounts use Spotify's PlayPlay DRM, which Riff cannot support.")
        }
        .alert("Spotify Premium Required", isPresented: $model.showNotPremium) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("A Spotify Premium subscription is required.")
        }
    }
}

struct MainView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        @Bindable var navigation = model.navigation
        NavigationSplitView {
            Sidebar()
                .navigationSplitViewColumnWidth(min: 180, ideal: 220, max: 320)
        } detail: {
            NavigationStack(path: $navigation.path) {
                SectionView(item: navigation.selection ?? .home)
                    .navigationDestination(for: Route.self) { route in
                        RouteView(route: route)
                    }
            }
            .safeAreaInset(edge: .top, spacing: 0) {
                if model.connectionLost { ConnectionBanner() }
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            PlaybackBar()
        }
        .inspector(isPresented: $model.showQueuePanel) {
            QueueList(compact: true)
                .inspectorColumnWidth(min: 260, ideal: 320, max: 480)
        }
        .toolbar {
            ToolbarItem(placement: .navigation) {
                ControlGroup {
                    Button { model.navigation.back() } label: {
                        Label("Back", systemImage: "chevron.left")
                    }
                    .disabled(!model.navigation.canGoBack)
                    Button { model.navigation.goForward() } label: {
                        Label("Forward", systemImage: "chevron.right")
                    }
                    .disabled(!model.navigation.canGoForward)
                }
            }
            ToolbarItem(placement: .primaryAction) {
                Button { model.showQueuePanel.toggle() } label: {
                    Label("Queue", systemImage: "list.bullet")
                }
                .help("Show the queue at the side of the window")
            }
            ToolbarItem(placement: .primaryAction) { UserMenu() }
        }
        .sheet(item: $model.playlistEditor) { request in
            PlaylistEditor(request: request)
        }
        .onKeyPress(.space) {
            model.core.togglePlay()
            return .handled
        }
    }
}

/// The page for a sidebar section.
struct SectionView: View {
    let item: SidebarItem

    var body: some View {
        switch item {
        case .home: HomeView()
        case .search: SearchView()
        case .nowPlaying: NowPlayingView()
        case .savedAlbums: SavedAlbumsView()
        case .savedPlaylists: SavedPlaylistsView()
        case .savedTracks: SavedTracksView()
        case .savedArtists: SavedArtistsView()
        case .playlist(let id): PlaylistView(id: id).id(id)
        case .pinned(let pinned): RouteView(route: pinned.route).id(pinned)
        }
    }
}

/// A page pushed on the navigation stack.
struct RouteView: View {
    let route: Route

    var body: some View {
        switch route {
        case .album(let id): AlbumView(id: id)
        case .artist(let id): ArtistView(id: id)
        case .playlist(let id): PlaylistView(id: id)
        case .user(let id): UserView(id: id)
        case .lyrics: LyricsView()
        case .queue: QueueList(compact: false).navigationTitle("Queue")
        }
    }
}

struct ConnectionBanner: View {
    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "wifi.exclamationmark")
            Text("Connection lost. Reconnecting…")
        }
        .font(.callout)
        .frame(maxWidth: .infinity)
        .padding(6)
        .background(.orange.opacity(0.2))
    }
}

struct ToastStack: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(spacing: 6) {
            ForEach(model.toasts) { toast in
                Text(toast.message)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .glassEffect()
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .padding(.bottom, 90)
        .animation(.default, value: model.toasts)
        .allowsHitTesting(false)
    }
}

struct UserMenu: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openSettings) private var openSettings
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        Menu {
            if let user = model.user {
                Button(user.displayName) { model.navigation.push(.user(user.id)) }
                Divider()
            }
            Button("Settings…") { openSettings() }
            Button("Keyboard Shortcuts") { openWindow(id: "shortcuts") }
            Button("About Riff") { NSApp.orderFrontStandardAboutPanel(nil) }
            Divider()
            Button("Log Out") { model.core.logout() }
        } label: {
            Label("Account", systemImage: "person.crop.circle")
        }
    }
}

/// Offers to open a Spotify link found on the clipboard when Riff becomes
/// active, like the GTK clipboard import.
struct ClipboardLinkBanner: View {
    @Environment(AppModel.self) private var model
    @State private var link: String?
    @State private var lastChange = NSPasteboard.general.changeCount

    var body: some View {
        Group {
            if let link, case .loggedIn = model.session {
                HStack(spacing: 12) {
                    Image(systemName: "link")
                    Text("Open the Spotify link from your clipboard?")
                    Button("Open") {
                        model.open(link: link)
                        self.link = nil
                    }
                    .buttonStyle(.borderedProminent)
                    Button("Dismiss") { self.link = nil }
                }
                .padding(10)
                .glassEffect()
                .padding(.top, 8)
                .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .animation(.default, value: link)
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
            check()
        }
    }

    private func check() {
        let pasteboard = NSPasteboard.general
        guard pasteboard.changeCount != lastChange else { return }
        lastChange = pasteboard.changeCount
        guard let text = pasteboard.string(forType: .string),
              parseSpotifyLink(link: text) != nil else { return }
        link = text
    }
}

import RiffCore
import SwiftUI

/// What a card stands for, and where clicking it goes.
enum CardItem: Identifiable, Hashable {
    case album(AlbumInfo)
    case playlist(PlaylistInfo)
    case artist(ArtistInfo)

    var id: String {
        switch self {
        case .album(let a): "album:\(a.id)"
        case .playlist(let p): "playlist:\(p.id)"
        case .artist(let a): "artist:\(a.id)"
        }
    }

    var title: String {
        switch self {
        case .album(let a): a.title
        case .playlist(let p): p.title
        case .artist(let a): a.name
        }
    }

    var subtitle: String {
        switch self {
        case .album(let a):
            [a.artistNames, a.year.map(String.init)].compactMap { $0 }.joined(separator: " · ")
        case .playlist(let p): p.owner?.name ?? ""
        case .artist: String(localized: "Artist")
        }
    }

    var art: Artwork {
        switch self {
        case .album(let a): a.art
        case .playlist(let p): p.art
        case .artist(let a): a.art
        }
    }

    var route: Route {
        switch self {
        case .album(let a): .album(a.id)
        case .playlist(let p): .playlist(p.id)
        case .artist(let a): .artist(a.id)
        }
    }

    var source: PlaySource {
        switch self {
        case .album(let a): .album(id: a.id)
        case .playlist(let p): .playlist(id: p.id)
        case .artist(let a): .artist(id: a.id)
        }
    }

    var pin: PinnedItem {
        switch self {
        case .album(let a): PinnedItem(kind: .album, id: a.id, title: a.title)
        case .playlist(let p): PinnedItem(kind: .playlist, id: p.id, title: p.title)
        case .artist(let a): PinnedItem(kind: .artist, id: a.id, title: a.name)
        }
    }

    var url: String? {
        switch self {
        case .album(let a): a.url ?? "https://open.spotify.com/album/\(a.id)"
        case .playlist(let p): p.url ?? "https://open.spotify.com/playlist/\(p.id)"
        case .artist(let a): a.url ?? "https://open.spotify.com/artist/\(a.id)"
        }
    }
}

struct CardView: View {
    @Environment(AppModel.self) private var model
    let item: CardItem
    @State private var hovering = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ZStack(alignment: .bottomTrailing) {
                ArtworkView(
                    url: item.art.medium,
                    shape: isArtist ? .circle : .rounded,
                    placeholder: placeholder,
                    priority: .offscreen
                )
                .shadow(radius: hovering ? 6 : 2)
                if hovering {
                    Button { model.play(source: item.source) } label: {
                        Image(systemName: "play.fill")
                            .font(.title3)
                            .frame(width: 36, height: 36)
                    }
                    .buttonStyle(.glassProminent)
                    .buttonBorderShape(.circle)
                    .padding(8)
                    .help("Play")
                }
            }
            Text(item.title)
                .font(.headline)
                .lineLimit(1)
            Text(item.subtitle)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
        .contentShape(Rectangle())
        .onHover { hovering = $0 }
        .onTapGesture { model.navigation.push(item.route) }
        .contextMenu { CardMenu(item: item) }
    }

    private var isArtist: Bool {
        if case .artist = item { return true }
        return false
    }

    private var placeholder: String {
        switch item {
        case .album: "square.stack"
        case .playlist: "music.note.list"
        case .artist: "music.microphone"
        }
    }
}

struct CardMenu: View {
    @Environment(AppModel.self) private var model
    let item: CardItem

    var body: some View {
        Button("Open") { model.navigation.push(item.route) }
        Button("Play") { model.play(source: item.source) }
        Button("Shuffle") { model.play(source: item.source, shuffle: true) }
        if model.settings.featurePinnedObjects {
            Divider()
            Button(model.isPinned(item.pin) ? "Unpin from Sidebar" : "Pin to Sidebar") {
                model.togglePin(item.pin)
            }
        }
        Divider()
        Button("Copy Link") { model.copyLink(item.url) }
    }
}

/// Adaptive grid of cards with optional infinite scrolling.
struct CardGrid: View {
    let items: [CardItem]
    var onLoadMore: (() -> Void)?

    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 150, maximum: 220), spacing: 20)], spacing: 24) {
            ForEach(items) { item in
                CardView(item: item)
                    .onAppear {
                        if item == items.last { onLoadMore?() }
                    }
            }
        }
    }
}

/// A titled horizontal row of cards, used on home and artist pages.
struct CardShelf: View {
    let title: String
    let items: [CardItem]

    var body: some View {
        if !items.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                Text(title).font(.title2.bold())
                ScrollView(.horizontal) {
                    LazyHStack(alignment: .top, spacing: 18) {
                        ForEach(items) { item in
                            CardView(item: item).frame(width: 160)
                        }
                    }
                    .padding(.bottom, 6)
                }
                .scrollIndicators(.never)
            }
        }
    }
}

/// Shown while a page loads, or when it failed.
struct LoadState: View {
    let error: String?
    let retry: () -> Void

    var body: some View {
        if let error {
            ContentUnavailableView {
                Label("Could not load", systemImage: "exclamationmark.triangle")
            } description: {
                Text(error)
            } actions: {
                Button("Try Again", action: retry)
            }
        } else {
            ProgressView().frame(maxWidth: .infinity, minHeight: 200)
        }
    }
}

import AppKit
import RiffCore
import SwiftUI

/// Decoded artwork, shared by every view. The core caches the encoded bytes
/// in memory and on disk; this keeps the decoded images for the session.
@MainActor
final class ArtworkCache {
    static let shared = ArtworkCache()

    private let images = NSCache<NSString, NSImage>()
    private var inflight: [String: Task<NSImage?, Never>] = [:]

    private init() {
        images.countLimit = 600
    }

    func cached(_ url: String) -> NSImage? {
        images.object(forKey: url as NSString)
    }

    func image(for url: String, size: UInt32, priority: Priority, core: RiffCore) async -> NSImage? {
        if let image = cached(url) { return image }
        if let task = inflight[url] { return await task.value }
        let task = Task<NSImage?, Never> {
            guard let data = await core.image(url: url, size: size, priority: priority) else {
                return nil
            }
            return NSImage(data: data)
        }
        inflight[url] = task
        let image = await task.value
        inflight[url] = nil
        if let image { images.setObject(image, forKey: url as NSString) }
        return image
    }
}

enum ArtworkShape {
    case square
    case rounded
    case circle
}

/// Artwork for an album, playlist, artist or track, with a placeholder.
struct ArtworkView: View {
    @Environment(AppModel.self) private var model
    let url: String?
    var shape: ArtworkShape = .rounded
    var placeholder: String = "music.note"
    var priority: Priority = .visible

    @State private var image: NSImage?

    var body: some View {
        GeometryReader { geometry in
            content
                .frame(width: geometry.size.width, height: geometry.size.height)
        }
        .aspectRatio(1, contentMode: .fit)
        .clipShape(clip)
        .task(id: url) { await load() }
    }

    @ViewBuilder
    private var content: some View {
        if let image {
            Image(nsImage: image)
                .resizable()
                .aspectRatio(contentMode: .fill)
        } else {
            ZStack {
                Rectangle().fill(.quaternary)
                Image(systemName: placeholder)
                    .font(.title)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var clip: AnyShape {
        switch shape {
        case .square: AnyShape(Rectangle())
        case .rounded: AnyShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
        case .circle: AnyShape(Circle())
        }
    }

    private func load() async {
        guard let url else {
            image = nil
            return
        }
        if let cached = ArtworkCache.shared.cached(url) {
            image = cached
            return
        }
        image = nil
        image = await ArtworkCache.shared.image(for: url, size: 300, priority: priority, core: model.core)
    }
}

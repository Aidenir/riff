import AppKit
import RiffCore
import SwiftUI
import UniformTypeIdentifiers

/// Create a playlist, or edit the name, description, visibility and cover of
/// one, like the GTK playlist dialog.
struct PlaylistEditor: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let request: PlaylistEditorRequest

    @State private var name = ""
    @State private var description = ""
    @State private var isPublic = false
    @State private var cover: NSImage?
    @State private var saving = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(request.playlist == nil ? "New Playlist" : "Edit Playlist")
                .font(.title2.bold())
            HStack(alignment: .top, spacing: 16) {
                coverPicker
                Form {
                    TextField("Name", text: $name)
                    TextField("Description", text: $description, axis: .vertical)
                        .lineLimit(3...5)
                    Toggle("Public", isOn: $isPublic)
                }
                .formStyle(.columns)
            }
            HStack {
                Spacer()
                Button("Cancel", role: .cancel) { dismiss() }
                    .keyboardShortcut(.cancelAction)
                Button(request.playlist == nil ? "Create" : "Save") { save() }
                    .keyboardShortcut(.defaultAction)
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty || saving)
            }
        }
        .padding(20)
        .frame(width: 520)
        .onAppear {
            if let playlist = request.playlist {
                name = playlist.title
                description = playlist.description ?? ""
                isPublic = playlist.public ?? false
            }
        }
    }

    private var coverPicker: some View {
        Button(action: chooseCover) {
            ZStack {
                if let cover {
                    Image(nsImage: cover).resizable().aspectRatio(contentMode: .fill)
                } else {
                    ArtworkView(url: request.playlist?.art.medium, placeholder: "music.note.list")
                }
            }
            .frame(width: 140, height: 140)
            .clipShape(RoundedRectangle(cornerRadius: 6))
            .overlay(alignment: .bottom) {
                Text("Choose Image")
                    .font(.caption)
                    .padding(4)
                    .frame(maxWidth: .infinity)
                    .background(.black.opacity(0.5))
                    .foregroundStyle(.white)
            }
            .clipShape(RoundedRectangle(cornerRadius: 6))
        }
        .buttonStyle(.plain)
        .help("Choose a cover image")
    }

    private func chooseCover() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.image]
        panel.allowsMultipleSelection = false
        if panel.runModal() == .OK, let url = panel.url {
            cover = NSImage(contentsOf: url)
        }
    }

    private func save() {
        saving = true
        let name = name.trimmingCharacters(in: .whitespaces)
        let description = description.isEmpty ? nil : description
        Task {
            defer { saving = false }
            do {
                let id: String
                if let playlist = request.playlist {
                    try await model.core.updatePlaylist(id: playlist.id, name: name, description: description, public: isPublic)
                    id = playlist.id
                } else {
                    let created = try await model.core.createPlaylist(name: name, description: description, public: isPublic)
                    id = created.id
                    if !request.tracksToAdd.isEmpty {
                        _ = try await model.core.addToPlaylist(id: id, trackIds: request.tracksToAdd.map(\.id))
                    }
                    model.navigation.push(.playlist(id))
                }
                if let cover, let jpeg = squareJPEG(cover) {
                    try await model.core.uploadPlaylistCover(id: id, jpeg: jpeg)
                }
                dismiss()
            } catch {
                model.report(error)
            }
        }
    }

    /// Crops to a centred square, scales to at most 640 px and encodes JPEG
    /// small enough for Spotify's 256 KB limit.
    private func squareJPEG(_ image: NSImage) -> Data? {
        guard let cg = image.cgImage(forProposedRect: nil, context: nil, hints: nil) else { return nil }
        let side = min(cg.width, cg.height)
        let crop = CGRect(x: (cg.width - side) / 2, y: (cg.height - side) / 2, width: side, height: side)
        guard let square = cg.cropping(to: crop) else { return nil }
        let size = min(side, 640)
        guard let context = CGContext(
            data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
        ) else { return nil }
        context.setFillColor(.white)
        context.fill(CGRect(x: 0, y: 0, width: size, height: size))
        context.interpolationQuality = .high
        context.draw(square, in: CGRect(x: 0, y: 0, width: size, height: size))
        guard let scaled = context.makeImage() else { return nil }
        let rep = NSBitmapImageRep(cgImage: scaled)
        for quality in [0.9, 0.75, 0.6, 0.45, 0.3] {
            if let data = rep.representation(using: .jpeg, properties: [.compressionFactor: quality]),
               data.count * 4 / 3 <= 256 * 1024 {
                return data
            }
        }
        return nil
    }
}

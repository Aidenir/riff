import RiffCore
import SwiftUI

/// The play queue: now playing, "next in queue" and the rest of the context.
/// Rows can be played, removed and reordered by dragging.
struct QueueList: View {
    @Environment(AppModel.self) private var model
    var compact: Bool

    var body: some View {
        let queue = model.queue
        List {
            if let current = queue.current {
                Section("Now Playing") { row(current, movable: false) }
            }
            if !queue.queued.isEmpty {
                Section {
                    ForEach(queue.queued, id: \.key) { row($0, movable: true) }
                        .onMove { move(queue.queued, $0, $1) }
                } header: {
                    HStack {
                        Text("Next in Queue")
                        Spacer()
                        Button("Clear") { model.core.clearQueued() }
                            .buttonStyle(.link)
                    }
                }
            }
            if !queue.context.isEmpty {
                Section(contextTitle(queue.source)) {
                    ForEach(queue.context, id: \.key) { row($0, movable: true) }
                        .onMove { move(queue.context, $0, $1) }
                }
            }
        }
        .overlay {
            if queue.current == nil && queue.queued.isEmpty && queue.context.isEmpty {
                ContentUnavailableView("The queue is empty", systemImage: "list.bullet")
            }
        }
    }

    private func contextTitle(_ source: PlaySource?) -> LocalizedStringKey {
        switch source {
        case .album: "Next from the Album"
        case .playlist: "Next from the Playlist"
        case .artist: "Next from the Artist"
        case .savedTracks: "Next from Liked Songs"
        case .radio: "Next on the Radio"
        case .search: "Next from the Search"
        case nil: "Next Up"
        }
    }

    private func row(_ entry: QueueEntry, movable: Bool) -> some View {
        HStack(spacing: 10) {
            ArtworkView(url: entry.track.art.small, priority: .offscreen)
                .frame(width: compact ? 32 : 40, height: compact ? 32 : 40)
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.track.title)
                    .lineLimit(1)
                    .foregroundStyle(entry.key == model.playback.currentKey ? Color.accentColor : .primary)
                Text(entry.track.artistNames)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer()
            if !compact {
                Text(formatDuration(Double(entry.track.durationMs)))
                    .monospacedDigit()
                    .foregroundStyle(.secondary)
            }
        }
        .contentShape(Rectangle())
        .onTapGesture(count: 2) { model.core.playQueueEntry(key: entry.key) }
        .contextMenu {
            Button("Play") { model.core.playQueueEntry(key: entry.key) }
            if movable {
                Button("Remove from Queue") { model.core.removeQueueEntries(keys: [entry.key]) }
            }
            Divider()
            TrackMenu(tracks: [entry.track])
        }
    }

    private func move(_ entries: [QueueEntry], _ from: IndexSet, _ to: Int) {
        guard let index = from.first, index < entries.count else { return }
        let key = entries[index].key
        if to >= entries.count {
            if let last = entries.last, last.key != key {
                model.core.moveQueueEntry(key: key, target: last.key, after: true)
            }
        } else if entries[to].key != key {
            model.core.moveQueueEntry(key: key, target: entries[to].key, after: false)
        }
    }
}

/// Large artwork and controls for the current track.
struct NowPlayingView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        HStack(spacing: 0) {
            if let track = model.playback.current {
                VStack(spacing: 18) {
                    ArtworkView(url: track.art.large ?? track.art.medium, priority: .hero)
                        .frame(maxWidth: 420, maxHeight: 420)
                        .shadow(radius: 16)
                    VStack(spacing: 4) {
                        Text(track.title)
                            .font(.title.bold())
                            .multilineTextAlignment(.center)
                        ArtistLinks(artists: track.artists)
                            .font(.title3)
                            .foregroundStyle(.secondary)
                        if let album = track.album {
                            Button(album.name) { model.navigation.push(.album(album.id)) }
                                .buttonStyle(.plain)
                                .foregroundStyle(.secondary)
                        }
                    }
                    HStack {
                        LikeButton(track: track)
                        Button { model.navigation.push(.lyrics) } label: {
                            Label("Lyrics", systemImage: "quote.bubble")
                        }
                        Menu {
                            TrackMenu(tracks: [track])
                        } label: {
                            Image(systemName: "ellipsis")
                        }
                        .menuIndicator(.hidden)
                        .fixedSize()
                    }
                    .buttonStyle(.borderless)
                }
                .padding(32)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                Divider()
                QueueList(compact: true)
                    .frame(width: 340)
            } else {
                ContentUnavailableView(
                    "Nothing Playing",
                    systemImage: "music.note",
                    description: Text("Pick an album, playlist or song to start listening.")
                )
            }
        }
        .navigationTitle("Now Playing")
    }
}

/// Synced lyrics that follow playback, or plain lyrics when unsynced.
struct LyricsView: View {
    @Environment(AppModel.self) private var model
    @State private var lyrics: LyricsInfo?
    @State private var error: String?
    @State private var loading = false

    var body: some View {
        let track = model.playback.current
        Group {
            if track == nil {
                ContentUnavailableView("Nothing Playing", systemImage: "music.note")
            } else if let lyrics, !lyrics.lines.isEmpty {
                lyricsBody(lyrics)
            } else if loading {
                ProgressView()
            } else {
                ContentUnavailableView(
                    "No Lyrics",
                    systemImage: "quote.bubble",
                    description: Text(error ?? String(localized: "No lyrics were found for this song."))
                )
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .navigationTitle(track.map { "\($0.title) — \($0.artistNames)" } ?? String(localized: "Lyrics"))
        .task(id: track?.id) { await load(track) }
    }

    private func lyricsBody(_ lyrics: LyricsInfo) -> some View {
        ScrollViewReader { proxy in
            TimelineView(.periodic(from: .now, by: 0.25)) { context in
                let position = UInt32(model.position(at: context.date))
                let active = lyrics.synced ? activeLine(lyrics, position) : nil
                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        ForEach(Array(lyrics.lines.enumerated()), id: \.offset) { index, line in
                            Text(line.words.isEmpty ? "♪" : line.words)
                                .font(.system(size: 26, weight: .bold))
                                .foregroundStyle(lineColor(index, active, synced: lyrics.synced))
                                .id(index)
                                .onTapGesture {
                                    if lyrics.synced { model.core.seek(positionMs: line.startMs) }
                                }
                        }
                        if let provider = lyrics.provider {
                            Text("Lyrics provided by \(provider)")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .padding(.top, 20)
                        }
                    }
                    .padding(40)
                    .frame(maxWidth: 760, alignment: .leading)
                    .frame(maxWidth: .infinity)
                }
                .onChange(of: active) { _, line in
                    if let line { withAnimation { proxy.scrollTo(line, anchor: .center) } }
                }
            }
        }
    }

    private func lineColor(_ index: Int, _ active: Int?, synced: Bool) -> Color {
        guard synced, let active else { return .primary }
        if index == active { return .primary }
        return index < active ? .secondary.opacity(0.6) : .secondary
    }

    private func activeLine(_ lyrics: LyricsInfo, _ position: UInt32) -> Int? {
        lyrics.lines.lastIndex { $0.startMs <= position }
    }

    private func load(_ track: TrackInfo?) async {
        lyrics = nil
        error = nil
        guard let track else { return }
        loading = true
        defer { loading = false }
        do {
            lyrics = try await model.core.lyrics(trackId: track.id)
        } catch {
            self.error = String(describing: error)
        }
    }
}

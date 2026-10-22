import RiffCore
import SwiftUI

/// The bar at the bottom of the window: current track, transport controls,
/// seek bar and volume.
struct PlaybackBar: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        HStack(spacing: 16) {
            NowPlayingInfo()
                .frame(minWidth: 180, maxWidth: 300, alignment: .leading)
            Spacer(minLength: 0)
            VStack(spacing: 4) {
                TransportControls()
                SeekBar()
                    .frame(maxWidth: 520)
            }
            .frame(minWidth: 280)
            Spacer(minLength: 0)
            HStack(spacing: 10) {
                if model.playback.current != nil {
                    Button { model.navigation.push(.lyrics) } label: {
                        Image(systemName: "quote.bubble")
                    }
                    .help("Lyrics")
                }
                if model.settings.featureDeviceSelector {
                    DeviceMenu()
                }
                VolumeControl()
            }
            .frame(minWidth: 160, maxWidth: 220, alignment: .trailing)
        }
        .buttonStyle(.borderless)
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(.bar)
        .overlay(alignment: .top) { Divider() }
    }
}

struct NowPlayingInfo: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        if let track = model.playback.current {
            HStack(spacing: 10) {
                ArtworkView(url: track.art.small ?? track.art.medium, priority: .hero)
                    .frame(width: 48, height: 48)
                VStack(alignment: .leading, spacing: 2) {
                    Text(track.title)
                        .font(.headline)
                        .lineLimit(1)
                    ArtistLinks(artists: track.artists)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                LikeButton(track: track)
            }
            .contextMenu { TrackMenu(tracks: [track]) }
        } else {
            Text("Nothing playing")
                .foregroundStyle(.secondary)
        }
    }
}

struct TransportControls: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let playback = model.playback
        HStack(spacing: 18) {
            Button { model.core.toggleShuffle() } label: {
                Image(systemName: "shuffle")
                    .foregroundStyle(playback.shuffle ? Color.accentColor : .secondary)
            }
            .help("Shuffle")
            Button { model.core.previous() } label: {
                Image(systemName: "backward.fill")
            }
            .help("Previous Track")
            Button { model.core.togglePlay() } label: {
                Image(systemName: playback.isPlaying ? "pause.circle.fill" : "play.circle.fill")
                    .font(.system(size: 30))
            }
            .help(playback.isPlaying ? "Pause" : "Play")
            Button { model.core.next() } label: {
                Image(systemName: "forward.fill")
            }
            .disabled(!playback.hasNext)
            .help("Next Track")
            Button { model.core.cycleRepeat() } label: {
                Image(systemName: playback.repeat == .track ? "repeat.1" : "repeat")
                    .foregroundStyle(playback.repeat == .off ? .secondary : Color.accentColor)
            }
            .help("Repeat")
        }
        .font(.title3)
    }
}

struct SeekBar: View {
    @Environment(AppModel.self) private var model
    @State private var dragging: Double?

    var body: some View {
        let duration = Double(model.playback.current?.durationMs ?? 0)
        TimelineView(.periodic(from: .now, by: 0.5)) { context in
            let position = dragging ?? model.position(at: context.date)
            HStack(spacing: 8) {
                Text(formatDuration(position))
                    .monospacedDigit()
                    .frame(width: 44, alignment: .trailing)
                Slider(
                    value: Binding(
                        get: { min(position, max(duration, 1)) },
                        set: { dragging = $0 }
                    ),
                    in: 0...max(duration, 1)
                ) { editing in
                    if !editing, let target = dragging {
                        model.core.seek(positionMs: UInt32(target))
                        dragging = nil
                    }
                }
                .controlSize(.small)
                .disabled(duration == 0)
                Text(formatDuration(duration))
                    .monospacedDigit()
                    .frame(width: 44, alignment: .leading)
            }
            .font(.caption)
            .foregroundStyle(.secondary)
        }
    }
}

struct VolumeControl: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let volume = model.playback.volume
        HStack(spacing: 6) {
            Button { model.core.toggleMute() } label: {
                Image(systemName: volume == 0 ? "speaker.slash.fill" : volume < 0.5 ? "speaker.wave.1.fill" : "speaker.wave.3.fill")
                    .frame(width: 18)
            }
            .help("Mute")
            Slider(value: Binding(
                get: { model.playback.volume },
                set: { model.core.setVolume(volume: $0) }
            ), in: 0...1)
            .controlSize(.small)
            .frame(width: 100)
        }
    }
}

struct LikeButton: View {
    @Environment(AppModel.self) private var model
    let track: TrackInfo

    var body: some View {
        let liked = model.isLiked(track)
        Button { model.setLiked([track], !liked) } label: {
            Image(systemName: liked ? "heart.fill" : "heart")
                .foregroundStyle(liked ? Color.accentColor : .secondary)
        }
        .buttonStyle(.borderless)
        .help(liked ? "Remove from Liked Songs" : "Save to Liked Songs")
    }
}

/// Comma separated artist names, each opening the artist page.
struct ArtistLinks: View {
    @Environment(AppModel.self) private var model
    let artists: [NamedLink]

    var body: some View {
        HStack(spacing: 0) {
            ForEach(Array(artists.enumerated()), id: \.offset) { index, artist in
                if index > 0 { Text(", ") }
                Button(artist.name) {
                    if !artist.id.isEmpty { model.navigation.push(.artist(artist.id)) }
                }
                .buttonStyle(.plain)
                .onHover { inside in
                    if inside { NSCursor.pointingHand.push() } else { NSCursor.pop() }
                }
            }
        }
    }
}

/// Spotify Connect device picker (feature flag `DeviceSelector`).
struct DeviceMenu: View {
    @Environment(AppModel.self) private var model
    @State private var devices: [DeviceInfo] = []
    @State private var loading = false

    var body: some View {
        let active = model.playback.deviceId
        Menu {
            Button {
                model.core.switchDevice(deviceId: nil)
            } label: {
                Label("This Mac", systemImage: active == nil ? "checkmark" : "laptopcomputer")
            }
            if !devices.isEmpty { Divider() }
            ForEach(devices, id: \.id) { device in
                Button {
                    model.core.switchDevice(deviceId: device.id)
                } label: {
                    Label(device.name, systemImage: active == device.id ? "checkmark" : icon(device.kind))
                }
            }
            Divider()
            Button(loading ? "Looking for Devices…" : "Refresh Devices") { refresh() }
                .disabled(loading)
        } label: {
            Image(systemName: active == nil ? "hifispeaker" : "hifispeaker.fill")
                .foregroundStyle(active == nil ? Color.primary : Color.accentColor)
        }
        .menuIndicator(.hidden)
        .fixedSize()
        .help("Play on another device")
        .onAppear { refresh() }
    }

    private func refresh() {
        loading = true
        Task {
            defer { loading = false }
            do { devices = try await model.core.devices() } catch { model.report(error) }
        }
    }

    private func icon(_ kind: DeviceType) -> String {
        switch kind {
        case .phone: "iphone"
        case .computer: "desktopcomputer"
        case .speaker: "hifispeaker"
        case .tv: "tv"
        case .other: "dot.radiowaves.left.and.right"
        }
    }
}

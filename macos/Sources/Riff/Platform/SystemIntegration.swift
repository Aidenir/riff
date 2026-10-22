import AppKit
import IOKit.pwr_mgt
import MediaPlayer
import RiffCore

/// Media keys, Control Center and the lock screen, replacing MPRIS on Linux.
@MainActor
final class NowPlayingController {
    private weak var model: AppModel?
    private var artworkURL: String?
    private var artwork: MPMediaItemArtwork?

    init(model: AppModel) {
        self.model = model
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.addTarget(handler: command { $0.core.play() })
        center.pauseCommand.addTarget(handler: command { $0.core.pause() })
        center.togglePlayPauseCommand.addTarget(handler: command { $0.core.togglePlay() })
        center.nextTrackCommand.addTarget(handler: command { $0.core.next() })
        center.previousTrackCommand.addTarget(handler: command { $0.core.previous() })
        center.stopCommand.addTarget(handler: command { $0.core.stop() })
        center.changePlaybackPositionCommand.addTarget(handler: command { model, event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else { return }
            model.core.seek(positionMs: UInt32(max(0, event.positionTime * 1000)))
        })
        center.changeShuffleModeCommand.addTarget(handler: command { model, event in
            guard let event = event as? MPChangeShuffleModeCommandEvent else { return }
            model.core.setShuffle(shuffle: event.shuffleType != .off)
        })
        center.changeRepeatModeCommand.addTarget(handler: command { model, event in
            guard let event = event as? MPChangeRepeatModeCommandEvent else { return }
            let repeatMode: Repeat = switch event.repeatType {
            case .one: .track
            case .all: .context
            default: .off
            }
            model.core.setRepeat(repeat: repeatMode)
        })
    }

    /// Remote command handlers arrive on the main thread.
    private func command(
        _ action: @escaping @MainActor (AppModel, MPRemoteCommandEvent) -> Void
    ) -> (MPRemoteCommandEvent) -> MPRemoteCommandHandlerStatus {
        { [weak self] event in
            MainActor.assumeIsolated {
                guard let model = self?.model else { return .commandFailed }
                action(model, event)
                return .success
            }
        }
    }

    private func command(
        _ action: @escaping @MainActor (AppModel) -> Void
    ) -> (MPRemoteCommandEvent) -> MPRemoteCommandHandlerStatus {
        command { model, _ in action(model) }
    }

    func update(_ playback: PlaybackSnapshot) {
        let info = MPNowPlayingInfoCenter.default()
        guard let track = playback.current else {
            info.nowPlayingInfo = nil
            info.playbackState = .stopped
            return
        }
        var values: [String: Any] = [
            MPMediaItemPropertyTitle: track.title,
            MPMediaItemPropertyArtist: track.artists.map(\.name).joined(separator: ", "),
            MPMediaItemPropertyPlaybackDuration: Double(track.durationMs) / 1000,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: Double(playback.positionMs) / 1000,
            MPNowPlayingInfoPropertyPlaybackRate: playback.isPlaying ? 1.0 : 0.0,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
        ]
        if let album = track.album { values[MPMediaItemPropertyAlbumTitle] = album.name }
        let url = track.art.large ?? track.art.medium
        if url == artworkURL, let artwork {
            values[MPMediaItemPropertyArtwork] = artwork
        } else {
            artworkURL = url
            artwork = nil
            loadArtwork(url)
        }
        info.nowPlayingInfo = values
        info.playbackState = playback.isPlaying ? .playing : .paused

        let center = MPRemoteCommandCenter.shared()
        center.nextTrackCommand.isEnabled = playback.hasNext
        center.changeShuffleModeCommand.currentShuffleType = playback.shuffle ? .items : .off
        center.changeRepeatModeCommand.currentRepeatType = switch playback.repeat {
        case .off: .off
        case .track: .one
        case .context: .all
        }
    }

    private func loadArtwork(_ url: String?) {
        guard let url, let model else { return }
        Task {
            guard let image = await ArtworkCache.shared.image(
                for: url, size: 640, priority: .background, core: model.core
            ), url == artworkURL else { return }
            let artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
            self.artwork = artwork
            var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
            info[MPMediaItemPropertyArtwork] = artwork
            MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        }
    }
}

/// Keeps the Mac awake while music plays, like the logind inhibitor on Linux.
@MainActor
final class SleepBlocker {
    private var assertion: IOPMAssertionID = 0
    private var held = false

    func update(playing: Bool, enabled: Bool) {
        let wanted = playing && enabled
        if wanted == held { return }
        if wanted {
            let result = IOPMAssertionCreateWithName(
                kIOPMAssertionTypePreventUserIdleSystemSleep as CFString,
                IOPMAssertionLevel(kIOPMAssertionLevelOn),
                "Riff is playing music" as CFString,
                &assertion
            )
            held = result == kIOReturnSuccess
        } else {
            IOPMAssertionRelease(assertion)
            held = false
        }
    }
}

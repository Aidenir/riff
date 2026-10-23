#if DEBUG
import Foundation
import RiffCore

/// Walks a logged-in debug build through every page and playback action,
/// logging each step, so a crash or a broken call shows up in the log.
/// Started with `RIFF_SMOKE_TEST=1`.
@MainActor
enum SmokeTest {
    static var enabled: Bool {
        ProcessInfo.processInfo.environment["RIFF_SMOKE_TEST"] == "1"
    }

    private static var started = false
    private static var failures = 0

    static func startIfEnabled(_ model: AppModel) {
        guard enabled, !started else { return }
        started = true
        Task { await run(model) }
    }

    private static func log(_ message: String) {
        FileHandle.standardError.write("SMOKE \(message)\n".data(using: .utf8)!)
    }

    private static func step(_ name: String, wait seconds: Double = 3, _ body: () async throws -> String) async {
        log("step: \(name)")
        do {
            let result = try await body()
            log("  ok: \(result)")
        } catch {
            failures += 1
            log("  FAIL: \(error)")
        }
        try? await Task.sleep(for: .seconds(seconds))
    }

    private static func check(_ condition: Bool, _ message: String) throws {
        if !condition { throw SmokeError(message: message) }
    }

    private struct SmokeError: Error, CustomStringConvertible {
        let message: String
        var description: String { message }
    }

    private static func run(_ model: AppModel) async {
        let core = model.core
        let nav = model.navigation
        try? await Task.sleep(for: .seconds(3))

        await step("home") {
            nav.selection = .home
            return "shown"
        }
        var album: AlbumInfo?
        await step("saved albums") {
            nav.selection = .savedAlbums
            let page = try await core.savedAlbums(offset: 0, limit: 20)
            album = page.items.first
            return "\(page.items.count) of \(page.total ?? 0)"
        }
        var playlist: PlaylistInfo?
        await step("saved playlists") {
            nav.selection = .savedPlaylists
            let page = try await core.savedPlaylists(offset: 0, limit: 20)
            playlist = page.items.first
            return "\(page.items.count) of \(page.total ?? 0)"
        }
        var liked: [TrackInfo] = []
        await step("liked songs") {
            nav.selection = .savedTracks
            let page = try await core.savedTracks(offset: 0, limit: 50)
            liked = page.items
            return "\(page.items.count) of \(page.total ?? 0)"
        }
        await step("followed artists") {
            nav.selection = .savedArtists
            let page = try await core.followedArtists(after: nil, limit: 20)
            return "\(page.items.count)"
        }
        await step("library tree") {
            let tree = try await core.libraryTree()
            return "\(tree.count) entries, \(tree.filter(\.isFolder).count) folders"
        }

        // Playback of an album
        if let album {
            await step("open album \(album.title)") {
                nav.selection = .savedAlbums
                nav.push(.album(album.id))
                let tracks = try await core.albumTracks(id: album.id, offset: 0, limit: 50)
                return "\(tracks.items.count) tracks"
            }
            await step("play album", wait: 6) {
                try await core.playSource(source: .album(id: album.id), shuffle: false)
                return "requested"
            }
            await step("is playing") {
                try check(model.playback.current != nil, "no current track")
                try check(model.playback.isPlaying, "not playing")
                return "\(model.playback.current?.title ?? "") at \(model.playback.positionMs) ms"
            }
            await step("next", wait: 4) {
                let before = model.playback.current?.id
                core.next()
                try await Task.sleep(for: .seconds(2))
                try check(model.playback.current?.id != before || !model.playback.hasNext, "track did not change")
                return model.playback.current?.title ?? "-"
            }
            await step("previous", wait: 4) {
                core.previous()
                return model.playback.current?.title ?? "-"
            }
            await step("pause") {
                core.pause()
                try await Task.sleep(for: .seconds(1))
                try check(!model.playback.isPlaying, "still playing")
                return "paused"
            }
            await step("resume") {
                core.play()
                try await Task.sleep(for: .seconds(1))
                try check(model.playback.isPlaying, "not resumed")
                return "playing"
            }
            await step("seek 30s", wait: 3) {
                core.seek(positionMs: 30_000)
                try await Task.sleep(for: .seconds(2))
                let position = model.position()
                try check(position >= 29_000, "position \(position)")
                return "\(Int(position)) ms"
            }
            await step("volume") {
                core.setVolume(volume: 0.4)
                try await Task.sleep(for: .seconds(1))
                try check(abs(model.playback.volume - 0.4) < 0.01, "volume \(model.playback.volume)")
                return "0.4"
            }
            await step("shuffle and repeat") {
                core.toggleShuffle()
                try await Task.sleep(for: .seconds(1))
                let shuffled = model.playback.shuffle
                core.toggleShuffle()
                core.cycleRepeat()
                try await Task.sleep(for: .seconds(1))
                let repeatMode = model.playback.repeat
                core.setRepeat(repeat: .off)
                return "shuffle toggled to \(shuffled), repeat cycled to \(repeatMode)"
            }
            await step("enqueue and queue page") {
                if let track = liked.first { core.enqueue(trackIds: [track.id]) }
                try await Task.sleep(for: .seconds(1))
                nav.push(.queue)
                return "\(model.queue.queued.count) queued, \(model.queue.context.count) in context"
            }
            await step("queue panel") {
                model.showQueuePanel = true
                return "shown"
            }
            await step("lyrics", wait: 4) {
                nav.push(.lyrics)
                guard let id = model.playback.current?.id else { return "nothing playing" }
                let lyrics = try? await core.lyrics(trackId: id)
                return "\(lyrics?.lines.count ?? 0) lines, synced \(lyrics?.synced ?? false)"
            }
            await step("now playing") {
                model.showQueuePanel = false
                nav.selection = .nowPlaying
                return "shown"
            }
            if let artist = album.artists.first {
                await step("artist \(artist.name)") {
                    nav.selection = .home
                    nav.push(.artist(artist.id))
                    let top = try await core.artistTopTracks(id: artist.id)
                    let albums = try await core.artistAlbums(id: artist.id, offset: 0, limit: 20)
                    return "\(top.count) top tracks, \(albums.items.count) albums"
                }
            }
        }

        if let playlist {
            await step("playlist \(playlist.title)") {
                nav.push(.playlist(playlist.id))
                let tracks = try await core.playlistTracks(id: playlist.id, offset: 0, limit: 50)
                return "\(tracks.items.count) tracks"
            }
            await step("play playlist track", wait: 5) {
                let tracks = try await core.playlistTracks(id: playlist.id, offset: 0, limit: 50)
                guard let second = tracks.items.dropFirst().first ?? tracks.items.first else { return "empty" }
                model.play(tracks.items, from: .playlist(id: playlist.id), startingAt: second, total: tracks.total)
                try await Task.sleep(for: .seconds(3))
                try check(model.playback.current?.id == second.id, "playing \(model.playback.current?.title ?? "nothing")")
                return second.title
            }
        }

        await step("search") {
            nav.selection = .search
            let results = try await core.search(query: "daft punk", limit: 10)
            try check(!results.tracks.isEmpty, "no tracks")
            return "\(results.tracks.count) tracks, \(results.albums.count) albums, \(results.artists.count) artists, \(results.playlists.count) playlists"
        }
        await step("search scoped") {
            let results = try await core.searchScoped(query: "daft punk", scope: .albums, offset: 0, limit: 20)
            return "\(results.albums.count) albums"
        }
        await step("home data") {
            let recent = try await core.recentContexts(limit: 5)
            let artists = try await core.topArtists(limit: 5)
            let charts = (try? await core.categoryPlaylists(category: "toplists", limit: 5)) ?? []
            return "\(recent.count) recent, \(artists.count) top artists, \(charts.count) charts"
        }
        await step("profile") {
            let user = try await core.currentUser()
            nav.push(.user(user.id))
            return user.displayName
        }
        await step("devices") {
            let devices = try await core.devices()
            return devices.map(\.name).joined(separator: ", ")
        }
        await step("settings round trip") {
            var settings = model.settings
            settings.eqBands = [1, 0, 0, 0, 0, 0, 0, 0, 0, 0]
            model.settings = settings
            try await Task.sleep(for: .seconds(1))
            try check(core.settings().eqBands.first == 1, "eq not saved")
            model.settings.eqBands = Array(repeating: 0, count: 10)
            return "saved"
        }
        await step("link handling") {
            try check(parseSpotifyLink(link: "https://open.spotify.com/album/abc") != nil, "link not parsed")
            return "ok"
        }
        await step("stop") {
            core.pause()
            nav.selection = .home
            return "paused"
        }
        log("DONE failures=\(failures)")
    }
}
#endif

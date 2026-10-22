import AppKit
import RiffCore
import SwiftUI

/// Menu bar, carrying the keyboard shortcuts of the GTK app (see
/// doc/macOS-Plan.md for the mapping).
struct RiffCommands: Commands {
    let model: AppModel
    @Environment(\.openWindow) private var openWindow

    private var loggedIn: Bool {
        if case .loggedIn = model.session { return true }
        return false
    }

    var body: some Commands {
        SidebarCommands()
        InspectorCommands()

        CommandGroup(after: .newItem) {
            Button("Open Link from Clipboard") {
                let text = NSPasteboard.general.string(forType: .string) ?? ""
                if !model.open(link: text) {
                    model.show(String(localized: "The clipboard has no Spotify link"))
                }
            }
            .keyboardShortcut("v", modifiers: [.command, .shift])
            .disabled(!loggedIn)

            Divider()

            Button("Log Out") { model.core.logout() }
                .disabled(!loggedIn)
        }

        CommandGroup(after: .sidebar) {
            Button(model.showQueuePanel ? "Hide Queue Panel" : "Show Queue Panel") {
                model.showQueuePanel.toggle()
            }
            .keyboardShortcut("q", modifiers: [.command, .option])
            Divider()
        }

        CommandMenu("Go") {
            Button("Back") { model.navigation.back() }
                .keyboardShortcut("[", modifiers: .command)
                .disabled(!model.navigation.canGoBack)
            Button("Forward") { model.navigation.goForward() }
                .keyboardShortcut("]", modifiers: .command)
                .disabled(!model.navigation.canGoForward)
            Divider()
            Button("Home") { model.navigation.selection = .home }
                .keyboardShortcut("0", modifiers: .command)
            Button("Now Playing") { model.navigation.selection = .nowPlaying }
                .keyboardShortcut("1", modifiers: .command)
            Button("Artists") { model.navigation.selection = .savedArtists }
                .keyboardShortcut("2", modifiers: .command)
            Button("Albums") { model.navigation.selection = .savedAlbums }
                .keyboardShortcut("3", modifiers: .command)
            Button("Playlists") { model.navigation.selection = .savedPlaylists }
                .keyboardShortcut("4", modifiers: .command)
            Button("Tracks") { model.navigation.selection = .savedTracks }
                .keyboardShortcut("5", modifiers: .command)
            Divider()
            Button("Search") { model.navigation.selection = .search }
                .keyboardShortcut("f", modifiers: .command)
            Button("Lyrics") { model.navigation.push(.lyrics) }
                .keyboardShortcut("l", modifiers: [.command, .option])
                .disabled(model.playback.current == nil)
        }

        CommandMenu("Controls") {
            // Space plays and pauses too, handled by the main window so it
            // does not steal spaces typed into text fields.
            Button(model.playback.isPlaying ? "Pause" : "Play") { model.core.togglePlay() }
            Button("Next Track") { model.core.next() }
                .keyboardShortcut(.rightArrow, modifiers: [.command, .option])
            Button("Previous Track") { model.core.previous() }
                .keyboardShortcut(.leftArrow, modifiers: [.command, .option])
            Divider()
            Button("Seek Forward") { model.core.seekBy(deltaMs: 10_000) }
                .keyboardShortcut(.rightArrow, modifiers: .command)
            Button("Seek Backward") { model.core.seekBy(deltaMs: -10_000) }
                .keyboardShortcut(.leftArrow, modifiers: .command)
            Divider()
            Button("Volume Up") { model.core.adjustVolume(delta: 0.05) }
                .keyboardShortcut(.upArrow, modifiers: .command)
            Button("Volume Down") { model.core.adjustVolume(delta: -0.05) }
                .keyboardShortcut(.downArrow, modifiers: .command)
            Button("Mute") { model.core.toggleMute() }
                .keyboardShortcut("m", modifiers: [.command, .shift])
            Divider()
            Toggle("Shuffle", isOn: Binding(
                get: { model.playback.shuffle },
                set: { model.core.setShuffle(shuffle: $0) }
            ))
            .keyboardShortcut("s", modifiers: .command)
            Picker("Repeat", selection: Binding(
                get: { model.playback.repeat },
                set: { model.core.setRepeat(repeat: $0) }
            )) {
                Text("Off").tag(Repeat.off)
                Text("Repeat All").tag(Repeat.context)
                Text("Repeat One").tag(Repeat.track)
            }
            Button("Cycle Repeat") { model.core.cycleRepeat() }
                .keyboardShortcut("r", modifiers: .command)
        }

        CommandGroup(replacing: .help) {
            Button("Keyboard Shortcuts") { openWindow(id: "shortcuts") }
                .keyboardShortcut("/", modifiers: .command)
        }

        #if DEBUG
        DebugCommands(model: model)
        #endif
    }
}

#if DEBUG
/// Debug-only menu, the counterpart of the GTK dev tools.
struct DebugCommands: Commands {
    let model: AppModel

    var body: some Commands {
        CommandMenu("Debug") {
            Section("Feature Flags") {
                Toggle("Device Selector", isOn: Binding(
                    get: { model.settings.featureDeviceSelector },
                    set: { model.settings.featureDeviceSelector = $0 }
                ))
                Toggle("Normalisation", isOn: Binding(
                    get: { model.settings.featureNormalisation },
                    set: { model.settings.featureNormalisation = $0 }
                ))
                Toggle("Pinned Objects", isOn: Binding(
                    get: { model.settings.featurePinnedObjects },
                    set: { model.settings.featurePinnedObjects = $0 }
                ))
            }
            Divider()
            Button("Restore Session") { model.core.restoreSession() }
            Button("Bump Navigation Epoch") { _ = model.core.bumpEpoch() }
            Button("Clear Cache") { Task { await model.core.clearCache() } }
            Button("Show Test Notification") { model.show("Test notification") }
            Button("Reset DRM Verification") { model.settings.drmVerifiedUser = "" }
        }
    }
}
#endif

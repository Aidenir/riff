import SwiftUI

/// Keyboard shortcuts, the Mac version of the GTK shortcuts window.
struct ShortcutsView: View {
    private struct Shortcut: Identifiable {
        let id = UUID()
        let title: LocalizedStringKey
        let keys: String
    }

    private let sections: [(LocalizedStringKey, [Shortcut])] = [
        ("Playback", [
            Shortcut(title: "Play/Pause", keys: "Space"),
            Shortcut(title: "Previous Track", keys: "⌥⌘←"),
            Shortcut(title: "Next Track", keys: "⌥⌘→"),
            Shortcut(title: "Seek Backward", keys: "⌘←"),
            Shortcut(title: "Seek Forward", keys: "⌘→"),
            Shortcut(title: "Volume Down", keys: "⌘↓"),
            Shortcut(title: "Volume Up", keys: "⌘↑"),
            Shortcut(title: "Mute", keys: "⇧⌘M"),
            Shortcut(title: "Shuffle", keys: "⌘S"),
            Shortcut(title: "Repeat", keys: "⌘R"),
        ]),
        ("Navigation", [
            Shortcut(title: "Search", keys: "⌘F"),
            Shortcut(title: "Go Back", keys: "⌘["),
            Shortcut(title: "Go Forward", keys: "⌘]"),
            Shortcut(title: "Home", keys: "⌘0"),
            Shortcut(title: "Now Playing", keys: "⌘1"),
            Shortcut(title: "Artists", keys: "⌘2"),
            Shortcut(title: "Albums", keys: "⌘3"),
            Shortcut(title: "Playlists", keys: "⌘4"),
            Shortcut(title: "Tracks", keys: "⌘5"),
            Shortcut(title: "Lyrics", keys: "⌥⌘L"),
        ]),
        ("General", [
            Shortcut(title: "Toggle Sidebar", keys: "⌃⌘S"),
            Shortcut(title: "Toggle Queue Panel", keys: "⌥⌘Q"),
            Shortcut(title: "Open Link from Clipboard", keys: "⇧⌘V"),
            Shortcut(title: "Preferences", keys: "⌘,"),
            Shortcut(title: "Keyboard Shortcuts", keys: "⌘/"),
            Shortcut(title: "Quit", keys: "⌘Q"),
        ]),
    ]

    var body: some View {
        HStack(alignment: .top, spacing: 32) {
            ForEach(Array(sections.enumerated()), id: \.offset) { _, section in
                VStack(alignment: .leading, spacing: 8) {
                    Text(section.0).font(.headline)
                    Grid(alignment: .leading, horizontalSpacing: 16, verticalSpacing: 6) {
                        ForEach(section.1) { shortcut in
                            GridRow {
                                Text(shortcut.title)
                                Text(shortcut.keys)
                                    .font(.body.monospaced())
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
        }
        .padding(24)
        .fixedSize()
    }
}

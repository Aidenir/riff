import AppKit
import RiffCore
import SwiftUI

@main
struct RiffApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @State private var model: AppModel

    init() {
        let model = AppModel()
        _model = State(initialValue: model)
        AppDelegate.model = model
    }

    var body: some Scene {
        Window("Riff", id: "main") {
            RootView()
                .environment(model)
                .frame(minWidth: 720, minHeight: 480)
                .onOpenURL { url in model.open(link: url.absoluteString) }
        }
        .defaultSize(width: 1080, height: 720)
        .commands { RiffCommands(model: model) }

        Settings {
            SettingsView()
                .environment(model)
        }

        Window("Keyboard Shortcuts", id: "shortcuts") {
            ShortcutsView()
        }
        .windowResizability(.contentSize)
    }
}

/// Window and lifecycle behaviour that SwiftUI scenes do not cover.
final class AppDelegate: NSObject, NSApplicationDelegate {
    @MainActor static var model: AppModel?
    @MainActor private var nowPlaying: NowPlayingController?
    @MainActor private let sleepBlocker = SleepBlocker()

    @MainActor
    func applicationDidFinishLaunching(_ notification: Notification) {
        guard let model = Self.model else { return }
        nowPlaying = NowPlayingController(model: model)
        applyTheme(model.settings.theme)
        model.start()
        observe(model)
    }

    /// Re-registers on every change, the Observation way of watching a model
    /// from outside SwiftUI.
    @MainActor
    private func observe(_ model: AppModel) {
        withObservationTracking {
            nowPlaying?.update(model.playback)
            sleepBlocker.update(playing: model.playback.isPlaying, enabled: model.settings.inhibitSuspend)
            applyTheme(model.settings.theme)
        } onChange: { [weak self] in
            Task { @MainActor in self?.observe(model) }
        }
    }

    @MainActor
    private func applyTheme(_ theme: ThemePreference) {
        NSApp.appearance = switch theme {
        case .light: NSAppearance(named: .aqua)
        case .dark: NSAppearance(named: .darkAqua)
        case .system: nil
        }
    }

    @MainActor
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        guard let model = Self.model else { return true }
        guard model.playback.isPlaying else { return true }
        switch model.settings.closeWindowBehavior {
        case .quit: return true
        case .keepPlaying: return false
        case .ask:
            let alert = NSAlert()
            alert.messageText = String(localized: "Keep playing in the background?")
            alert.informativeText = String(localized: "Riff can keep playing music after its window is closed.")
            alert.addButton(withTitle: String(localized: "Keep Playing"))
            alert.addButton(withTitle: String(localized: "Quit"))
            alert.showsSuppressionButton = true
            let keep = alert.runModal() == .alertFirstButtonReturn
            if alert.suppressionButton?.state == .on {
                model.settings.closeWindowBehavior = keep ? .keepPlaying : .quit
            }
            return !keep
        }
    }

    @MainActor
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        if !flag {
            for window in sender.windows where window.identifier?.rawValue.hasPrefix("main") == true {
                window.makeKeyAndOrderFront(nil)
                return false
            }
        }
        return true
    }

    @MainActor
    func applicationWillTerminate(_ notification: Notification) {
        Self.model?.core.shutdown()
    }
}

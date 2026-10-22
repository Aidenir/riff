import RiffCore
import SwiftUI

/// The Settings window, with the same options as the GTK preferences dialog.
struct SettingsView: View {
    var body: some View {
        TabView {
            Tab("General", systemImage: "gearshape") { GeneralSettings() }
            Tab("Audio", systemImage: "speaker.wave.2") { AudioSettings() }
            Tab("Equalizer", systemImage: "slider.vertical.3") { EqualizerSettings() }
            Tab("Network", systemImage: "network") { NetworkSettings() }
        }
        .frame(width: 560)
        .scenePadding()
    }
}

private struct GeneralSettings: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        Form {
            Picker("Theme", selection: $model.settings.theme) {
                Text("Follow System").tag(ThemePreference.system)
                Text("Light").tag(ThemePreference.light)
                Text("Dark").tag(ThemePreference.dark)
            }
            Toggle(isOn: $model.settings.queueSidePanel) {
                Text("Queue Side Panel")
                Text("Show the queue in a panel at the side of the window")
            }
            Picker(selection: $model.settings.closeWindowBehavior) {
                Text("Ask").tag(CloseWindowBehavior.ask)
                Text("Keep Playing").tag(CloseWindowBehavior.keepPlaying)
                Text("Quit").tag(CloseWindowBehavior.quit)
            } label: {
                Text("Close Window Behavior")
                Text("What should Riff do if you close it while audio is playing?")
            }
            Toggle(isOn: $model.settings.inhibitSuspend) {
                Text("Keep Device Awake While Playing")
                Text("Prevent the system from automatically suspending while audio is playing")
            }
            Toggle(isOn: Binding(
                get: { model.settings.skipExplicit || model.playback.explicitFilterLocked },
                set: { model.settings.skipExplicit = $0 }
            )) {
                Text("Skip Explicit Tracks")
                Text(model.playback.explicitFilterLocked
                    ? "Your account's explicit content filter is locked on"
                    : "Automatically skip tracks marked as explicit")
            }
            .disabled(model.playback.explicitFilterLocked)
            Stepper(value: $model.settings.shuffleSeparation, in: 1...10) {
                Text("Shuffle Separation: \(model.settings.shuffleSeparation)")
                Text("Keep songs by the same artist this many tracks apart when shuffling")
            }
        }
        .formStyle(.grouped)
    }
}

private struct AudioSettings: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        Form {
            Section {
                LabeledContent("Audio Backend", value: "CoreAudio")
                Picker(selection: $model.settings.bitrate) {
                    Text("Low (96 kbps)").tag(StreamBitrate.kbps96)
                    Text("Normal (160 kbps)").tag(StreamBitrate.kbps160)
                    Text("High (320 kbps)").tag(StreamBitrate.kbps320)
                } label: {
                    Text("Audio Quality")
                    Text("Streaming bitrate from Spotify (higher uses more bandwidth)")
                }
                Picker(selection: $model.settings.audioFormat) {
                    Text("16-bit").tag(SampleFormat.s16)
                    Text("24-bit").tag(SampleFormat.s24)
                    Text("24-bit (packed)").tag(SampleFormat.s24x3)
                    Text("32-bit").tag(SampleFormat.s32)
                    Text("32-bit float").tag(SampleFormat.f32)
                    Text("64-bit float").tag(SampleFormat.f64)
                } label: {
                    Text("Audio Format")
                    Text("Sample bit depth for audio output")
                }
                Toggle(isOn: $model.settings.gaplessPlayback) {
                    Text("Gapless Playback")
                    Text("Eliminates silence between consecutive tracks")
                }
                Picker(selection: $model.settings.volumeCurve) {
                    Text("Logarithmic").tag(VolumeCurve.log)
                    Text("Linear").tag(VolumeCurve.linear)
                    Text("Cubic").tag(VolumeCurve.cubic)
                } label: {
                    Text("Volume Curve")
                    Text("Controls how the volume slider responds")
                }
                Toggle(isOn: $model.settings.monoAudio) {
                    Text("Mono Audio")
                    Text("Mix left and right channels together so both speakers play the same audio")
                }
            }
            Section("Stereo Pan") {
                Slider(value: $model.settings.pan, in: -1...1) {
                    Text("Balance")
                } minimumValueLabel: {
                    Text("L")
                } maximumValueLabel: {
                    Text("R")
                }
                Button("Center") { model.settings.pan = 0 }
            }
            Section("Pitch Shift") {
                Slider(value: $model.settings.pitchCents, in: -100...100, step: 1) {
                    Text("\(Int(model.settings.pitchCents)) cents")
                }
                Button("Reset") { model.settings.pitchCents = 0 }
            }
            if model.settings.featureNormalisation {
                NormalisationSettings()
            }
        }
        .formStyle(.grouped)
    }
}

private struct NormalisationSettings: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        Section("Normalisation") {
            Toggle(isOn: $model.settings.normalisation) {
                Text("Enable Normalisation")
                Text("Automatically adjust volume between tracks")
            }
            Group {
                Picker(selection: $model.settings.normalisationType) {
                    Text("Automatic").tag(NormalisationKind.auto)
                    Text("Track").tag(NormalisationKind.track)
                    Text("Album").tag(NormalisationKind.album)
                } label: {
                    Text("Normalisation Type")
                    Text("How gain values are selected")
                }
                Picker(selection: $model.settings.normalisationMethod) {
                    Text("Dynamic").tag(NormalisationMode.dynamic)
                    Text("Basic").tag(NormalisationMode.basic)
                } label: {
                    Text("Normalisation Method")
                    Text("Basic clips peaks, Dynamic uses a limiter")
                }
                numberField("Pre-gain (dB)", "Additional gain applied before normalisation", $model.settings.normalisationPregainDb)
                numberField("Threshold (dBFS)", "Ceiling for dynamic normalisation", $model.settings.normalisationThresholdDbfs)
                numberField("Attack (ms)", "How quickly the limiter reacts to peaks", $model.settings.normalisationAttackMs)
                numberField("Release (ms)", "How quickly the limiter recovers after peaks", $model.settings.normalisationReleaseMs)
                numberField("Knee (dB)", "Width of the soft knee for the limiter", $model.settings.normalisationKneeDb)
            }
            .disabled(!model.settings.normalisation)
        }
    }

    private func numberField(_ title: LocalizedStringKey, _ subtitle: LocalizedStringKey, _ value: Binding<Double>) -> some View {
        LabeledContent {
            TextField("", value: value, format: .number.precision(.fractionLength(0...1)))
                .frame(width: 80)
                .multilineTextAlignment(.trailing)
        } label: {
            Text(title)
            Text(subtitle)
        }
    }
}

/// The 10-band equalizer, applied live by the audio engine.
struct EqualizerSettings: View {
    @Environment(AppModel.self) private var model
    private let labels = ["31", "62", "125", "250", "500", "1K", "2K", "4K", "8K", "16K"]

    private static let presets: [(String, [Double])] = [
        ("Flat", [0, 0, 0, 0, 0, 0, 0, 0, 0, 0]),
        ("Bass Boost", [6, 5, 4, 2, 0, 0, 0, 0, 0, 0]),
        ("Treble Boost", [0, 0, 0, 0, 0, 1, 2, 4, 5, 6]),
        ("Vocal", [-2, -2, -1, 1, 3, 3, 2, 1, 0, -1]),
        ("Rock", [4, 3, 2, 0, -1, -1, 1, 2, 3, 4]),
        ("Electronic", [5, 4, 1, 0, -2, 1, 0, 1, 4, 5]),
        ("Acoustic", [3, 3, 2, 1, 1, 1, 2, 2, 2, 1]),
    ]

    var body: some View {
        VStack(spacing: 16) {
            HStack(alignment: .bottom, spacing: 14) {
                ForEach(0..<10, id: \.self) { band in
                    VStack(spacing: 6) {
                        Text(String(format: "%+.0f", value(band)))
                            .font(.caption2)
                            .monospacedDigit()
                        Slider(value: binding(band), in: -12...12, step: 0.5)
                            .rotationEffect(.degrees(-90))
                            .frame(width: 160, height: 24)
                            .frame(width: 28, height: 160)
                        Text(labels[band])
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            HStack {
                Menu("Presets") {
                    ForEach(Self.presets, id: \.0) { preset in
                        Button(LocalizedStringKey(preset.0)) { model.settings.eqBands = preset.1 }
                    }
                }
                .fixedSize()
                Spacer()
                Button("Reset") { model.settings.eqBands = Array(repeating: 0, count: 10) }
            }
        }
        .padding()
    }

    private func value(_ band: Int) -> Double {
        band < model.settings.eqBands.count ? model.settings.eqBands[band] : 0
    }

    private func binding(_ band: Int) -> Binding<Double> {
        Binding(
            get: { value(band) },
            set: { newValue in
                var bands = model.settings.eqBands
                while bands.count < 10 { bands.append(0) }
                bands[band] = newValue
                model.settings.eqBands = bands
            }
        )
    }
}

private struct NetworkSettings: View {
    @Environment(AppModel.self) private var model
    @State private var clearing = false

    var body: some View {
        @Bindable var model = model
        Form {
            Section("Network") {
                LabeledContent {
                    TextField("", value: $model.settings.apPort, format: .number.grouping(.never))
                        .frame(width: 80)
                        .multilineTextAlignment(.trailing)
                } label: {
                    Text("Access Point Port")
                    Text("Port used for connections to Spotify’s Access Point. Set to 0 if any port is fine.")
                }
            }
            Section("Caching") {
                Stepper(value: $model.settings.memoryCacheSizeMb, in: 1...1024, step: 8) {
                    Text("Memory Cache Size (MB): \(model.settings.memoryCacheSizeMb)")
                    Text("Maximum memory allocated for caching data while Riff is running.")
                }
                Stepper(value: $model.settings.diskCacheSizeMb, in: 16...4096, step: 16) {
                    Text("Disk Cache Size (MB): \(model.settings.diskCacheSizeMb)")
                    Text("Maximum disk space allocated for caching data between sessions.")
                }
                Stepper(value: $model.settings.audioCacheSizeMb, in: 64...16384, step: 64) {
                    Text("Audio Cache Size (MB): \(model.settings.audioCacheSizeMb)")
                    Text("Maximum disk space allocated for caching downloaded audio.")
                }
                LabeledContent {
                    Button("Clear Cache") {
                        clearing = true
                        Task {
                            await model.core.clearCache()
                            clearing = false
                            model.show(String(localized: "Cache cleared"))
                        }
                    }
                    .disabled(clearing)
                } label: {
                    Text("Clear Cache")
                    Text("Remove all cached data and images stored on disk")
                }
            }
        }
        .formStyle(.grouped)
    }
}

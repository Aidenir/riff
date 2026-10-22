import Foundation
import RiffCore

/// `m:ss`, or `h:mm:ss` past an hour.
func formatDuration(_ milliseconds: Double) -> String {
    let total = Int(max(0, milliseconds) / 1000)
    let hours = total / 3600
    let minutes = (total % 3600) / 60
    let seconds = total % 60
    if hours > 0 {
        return String(format: "%d:%02d:%02d", hours, minutes, seconds)
    }
    return String(format: "%d:%02d", minutes, seconds)
}

/// "1 hr 5 min" style total for a list of tracks.
func formatTotalDuration(_ tracks: [TrackInfo]) -> String {
    let seconds = tracks.reduce(0) { $0 + Double($1.durationMs) } / 1000
    let formatter = DateComponentsFormatter()
    formatter.allowedUnits = seconds >= 3600 ? [.hour, .minute] : [.minute]
    formatter.unitsStyle = .short
    return formatter.string(from: seconds) ?? ""
}

func formatCount(_ count: UInt64) -> String {
    count.formatted(.number.notation(.compactName))
}

extension TrackInfo {
    var artistNames: String { artists.map(\.name).joined(separator: ", ") }
}

extension AlbumInfo {
    var artistNames: String { artists.map(\.name).joined(separator: ", ") }
}

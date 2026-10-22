import AppKit
import CoreImage
import RiffCore
import SwiftUI

/// The average colour of a piece of artwork, used to tint page headers like
/// the GTK art tint (src/app/components/art_tint.rs).
@MainActor
enum ArtTint {
    private static var cache: [String: Color] = [:]
    private static let context = CIContext(options: [.workingColorSpace: NSNull()])

    static func color(for url: String, core: RiffCore) async -> Color? {
        if let color = cache[url] { return color }
        guard let image = await ArtworkCache.shared.image(for: url, size: 300, priority: .hero, core: core),
              let cg = image.cgImage(forProposedRect: nil, context: nil, hints: nil)
        else { return nil }
        let input = CIImage(cgImage: cg)
        guard let filter = CIFilter(name: "CIAreaAverage", parameters: [
            kCIInputImageKey: input,
            kCIInputExtentKey: CIVector(cgRect: input.extent),
        ]), let output = filter.outputImage else { return nil }
        var pixel = [UInt8](repeating: 0, count: 4)
        context.render(output, toBitmap: &pixel, rowBytes: 4, bounds: CGRect(x: 0, y: 0, width: 1, height: 1), format: .RGBA8, colorSpace: nil)
        let color = Color(
            red: Double(pixel[0]) / 255,
            green: Double(pixel[1]) / 255,
            blue: Double(pixel[2]) / 255
        )
        cache[url] = color
        return color
    }
}

/// A soft gradient from the artwork's colour, behind headers.
struct ArtTintBackground: View {
    @Environment(AppModel.self) private var model
    let url: String?
    @State private var tint: Color?

    var body: some View {
        LinearGradient(
            colors: [(tint ?? .clear).opacity(0.45), .clear],
            startPoint: .top,
            endPoint: .bottom
        )
        .animation(.easeInOut, value: tint)
        .task(id: url) {
            guard let url else {
                tint = nil
                return
            }
            tint = await ArtTint.color(for: url, core: model.core)
        }
    }
}

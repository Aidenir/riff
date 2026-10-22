// Renders an SVG onto a full-bleed square tile for a macOS app icon. macOS
// 26 masks the tile itself; transparent icons get boxed in a grey tile.
// Usage: swift scripts/render-svg.swift <input.svg> <output.png> <size>

import AppKit

let args = CommandLine.arguments
guard args.count == 4, let size = Int(args[3]),
      let image = NSImage(contentsOfFile: args[1]) else {
    FileHandle.standardError.write("usage: render-svg.swift <in.svg> <out.png> <size>\n".data(using: .utf8)!)
    exit(1)
}
guard let rep = NSBitmapImageRep(
    bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size,
    bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
    colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0
) else { exit(1) }
rep.size = NSSize(width: size, height: size)
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
let bounds = NSRect(x: 0, y: 0, width: size, height: size)
NSGradient(
    starting: NSColor(calibratedWhite: 0.99, alpha: 1),
    ending: NSColor(calibratedWhite: 0.88, alpha: 1)
)!.draw(in: bounds, angle: -90)
let inset = CGFloat(size) * 0.12
image.draw(in: NSRect(x: inset, y: inset, width: CGFloat(size) - 2 * inset, height: CGFloat(size) - 2 * inset))
NSGraphicsContext.restoreGraphicsState()
try rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: args[2]))

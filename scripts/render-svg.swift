// Renders an SVG to a square, transparent PNG.
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
// macOS icons keep a margin around the artwork.
let inset = CGFloat(size) * 0.1
image.draw(in: NSRect(x: inset, y: inset, width: CGFloat(size) - 2 * inset, height: CGFloat(size) - 2 * inset))
NSGraphicsContext.restoreGraphicsState()
try rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: args[2]))

// Renders the app icon SVG as an Android adaptive icon layer: the artwork on
// a transparent square, scaled into the 66 dp safe zone of the 108 dp layer.
// With "mono" it renders a single-colour silhouette for themed icons.
// Usage: swift scripts/render-android-icon.swift <input.svg> <output.png> <size> [mono]

import AppKit

let args = CommandLine.arguments
guard args.count >= 4, let size = Int(args[3]),
      let image = NSImage(contentsOfFile: args[1]) else {
    FileHandle.standardError.write("usage: render-android-icon.swift <in.svg> <out.png> <size> [mono]\n".data(using: .utf8)!)
    exit(1)
}
let mono = args.count > 4 && args[4] == "mono"
guard let rep = NSBitmapImageRep(
    bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size,
    bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
    colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0
) else { exit(1) }
rep.size = NSSize(width: size, height: size)
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
let side = CGFloat(size) * 66 / 108
let origin = (CGFloat(size) - side) / 2
let rect = NSRect(x: origin, y: origin, width: side, height: side)
image.draw(in: rect)
NSGraphicsContext.restoreGraphicsState()
if mono, let pixels = rep.bitmapData {
    // Keep the badge, cut out the white play symbol.
    for i in stride(from: 0, to: rep.bytesPerRow * size, by: 4) {
        let white = pixels[i] > 225 && pixels[i + 1] > 225 && pixels[i + 2] > 225
        let alpha = white ? 0 : pixels[i + 3]
        pixels[i] = 0
        pixels[i + 1] = 0
        pixels[i + 2] = 0
        pixels[i + 3] = alpha
    }
}
try rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: args[2]))

// render_image.swift: draws an image NSImage can load (SVG, PDF, PNG) into a
// PNG of an exact pixel size, optionally inset over a solid background. Used
// by icon.bb; needs nothing beyond macOS and the Swift toolchain.
//   swift scripts/render_image.swift in.svg out.png 36 36 [--scale 2]
//   swift scripts/render_image.swift in.png out.png 512 512 --inset 40 --background e8e8ed
import AppKit

func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data((message + "\n").utf8))
    exit(1)
}

let args = CommandLine.arguments.dropFirst()
let positional = Array(args.prefix { !$0.hasPrefix("--") })
guard positional.count == 4, let width = Int(positional[2]), let height = Int(positional[3]) else {
    fail("usage: render_image.swift INPUT OUTPUT WIDTH HEIGHT [--scale N] [--inset PX] [--background RRGGBB]")
}
func option(_ name: String) -> String? {
    guard let i = args.firstIndex(of: name), args.index(after: i) < args.endIndex else { return nil }
    return args[args.index(after: i)]
}
let scale = option("--scale").flatMap(Double.init) ?? 1
let inset = option("--inset").flatMap(Double.init) ?? 0
let background = option("--background").flatMap { UInt32($0, radix: 16) }

guard let image = NSImage(contentsOf: URL(fileURLWithPath: positional[0])) else {
    fail("can't load \(positional[0])")
}
guard let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: width, pixelsHigh: height,
                                 bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                                 colorSpaceName: .calibratedRGB, bytesPerRow: 0, bitsPerPixel: 0)?
          .retagging(with: .sRGB),
      let context = NSGraphicsContext(bitmapImageRep: rep)
else { fail("can't create a \(width)×\(height) bitmap") }

let rect = NSRect(x: 0, y: 0, width: width, height: height)
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = context
context.imageInterpolation = .high
if let rgb = background {
    NSColor(srgbRed: CGFloat((rgb >> 16) & 0xff) / 255, green: CGFloat((rgb >> 8) & 0xff) / 255,
            blue: CGFloat(rgb & 0xff) / 255, alpha: 1).setFill()
    rect.fill()
}
image.draw(in: rect.insetBy(dx: inset, dy: inset), from: .zero, operation: .sourceOver, fraction: 1)
NSGraphicsContext.restoreGraphicsState()

// Points, so a @2x file says 144 dpi.
rep.size = NSSize(width: Double(width) / scale, height: Double(height) / scale)
guard let png = rep.representation(using: .png, properties: [:]) else { fail("can't encode PNG") }
do {
    try png.write(to: URL(fileURLWithPath: positional[1]))
} catch {
    fail("can't write \(positional[1]): \(error.localizedDescription)")
}

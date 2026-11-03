// Draws PixelLink's app icon (a phone with a link badge on a blue tile) and writes
// Resources/AppIcon.icns. Run from mac/: swift scripts/make-icon.swift
// The geometry matches the Android adaptive icon (res/drawable/ic_launcher_foreground.xml).
import AppKit

let canvas: CGFloat = 1024
let tile = CGRect(x: 100, y: 100, width: 824, height: 824)
// Android draws on a 108-unit canvas whose safe zone is 21…87; map that zone onto the tile.
let scale = tile.width / 66
func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: tile.minX + (x - 21) * scale, y: tile.maxY - (y - 21) * scale) }
func r(_ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat) -> CGRect {
    CGRect(x: tile.minX + (x - 21) * scale, y: tile.maxY - (y - 21 + h) * scale, width: w * scale, height: h * scale)
}

func draw(size: Int) -> Data {
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size, bitsPerSample: 8,
                               samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB,
                               bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    let ctx = NSGraphicsContext.current!.cgContext
    ctx.scaleBy(x: CGFloat(size) / canvas, y: CGFloat(size) / canvas)

    // Tile with a soft drop shadow and a top-to-bottom blue gradient.
    let shape = CGPath(roundedRect: tile, cornerWidth: 185, cornerHeight: 185, transform: nil)
    ctx.saveGState()
    ctx.setShadow(offset: CGSize(width: 0, height: -12), blur: 28, color: NSColor.black.withAlphaComponent(0.25).cgColor)
    ctx.addPath(shape)
    ctx.setFillColor(NSColor(srgbRed: 0.10, green: 0.45, blue: 0.91, alpha: 1).cgColor)
    ctx.fillPath()
    ctx.restoreGState()
    ctx.saveGState()
    ctx.addPath(shape)
    ctx.clip()
    let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: [
        NSColor(srgbRed: 0.30, green: 0.58, blue: 0.98, alpha: 1).cgColor,
        NSColor(srgbRed: 0.05, green: 0.36, blue: 0.84, alpha: 1).cgColor,
    ] as CFArray, locations: [0, 1])!
    ctx.drawLinearGradient(gradient, start: CGPoint(x: 0, y: tile.maxY), end: CGPoint(x: 0, y: tile.minY), options: [])
    ctx.restoreGState()

    // Phone outline.
    let white = NSColor.white.cgColor
    ctx.setFillColor(white)
    let body = CGPath(roundedRect: r(38, 29, 26, 46), cornerWidth: 5 * scale, cornerHeight: 5 * scale, transform: nil)
    let screen = CGPath(roundedRect: r(42, 34, 18, 34), cornerWidth: 1.5 * scale, cornerHeight: 1.5 * scale, transform: nil)
    ctx.addPath(body)
    ctx.addPath(screen)
    ctx.fillPath(using: .evenOdd)
    ctx.fillEllipse(in: r(49.5, 70, 3, 3))

    // Link badge: a white disc with a blue ring cut out of the phone's corner, and a link symbol.
    let badge = r(55, 54, 26, 26)
    ctx.setFillColor(NSColor(srgbRed: 0.08, green: 0.40, blue: 0.87, alpha: 1).cgColor)
    ctx.fillEllipse(in: badge.insetBy(dx: -2.5 * scale, dy: -2.5 * scale))
    ctx.setFillColor(white)
    ctx.fillEllipse(in: badge)
    let config = NSImage.SymbolConfiguration(pointSize: 13 * scale, weight: .bold)
        .applying(.init(paletteColors: [NSColor(srgbRed: 0.08, green: 0.40, blue: 0.87, alpha: 1)]))
    if let link = NSImage(systemSymbolName: "link", accessibilityDescription: nil)?.withSymbolConfiguration(config) {
        let s = link.size
        link.draw(in: CGRect(x: badge.midX - s.width / 2, y: badge.midY - s.height / 2, width: s.width, height: s.height))
    }

    NSGraphicsContext.restoreGraphicsState()
    return rep.representation(using: .png, properties: [:])!
}

let iconset = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("AppIcon.iconset")
try? FileManager.default.removeItem(at: iconset)
try! FileManager.default.createDirectory(at: iconset, withIntermediateDirectories: true)
for base in [16, 32, 128, 256, 512] {
    try! draw(size: base).write(to: iconset.appendingPathComponent("icon_\(base)x\(base).png"))
    try! draw(size: base * 2).write(to: iconset.appendingPathComponent("icon_\(base)x\(base)@2x.png"))
}
let iconutil = Process()
iconutil.executableURL = URL(fileURLWithPath: "/usr/bin/iconutil")
iconutil.arguments = ["-c", "icns", iconset.path, "-o", "Resources/AppIcon.icns"]
try! iconutil.run()
iconutil.waitUntilExit()
try! draw(size: 512).write(to: URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("AppIcon-preview.png"))
print(iconutil.terminationStatus == 0 ? "Wrote Resources/AppIcon.icns" : "iconutil failed")

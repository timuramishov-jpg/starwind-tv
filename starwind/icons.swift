// Рисует значки SF Symbols из macOS в PNG для лаунчера. Только для личного телевизора.
import AppKit
let out = CommandLine.arguments[1]
let map: [(String, String)] = [
    ("top_input", "tv.fill"), ("top_wifi", "wifi"), ("top_settings", "gearshape.fill"),
    ("top_grid", "square.grid.2x2.fill"), ("top_clean", "sparkles"),
    ("top_search", "magnifyingglass"), ("top_remote", "iphone"),
]
for (name, symbol) in map {
    let cfg = NSImage.SymbolConfiguration(pointSize: 100, weight: .semibold)
    guard let img = NSImage(systemSymbolName: symbol, accessibilityDescription: nil)?.withSymbolConfiguration(cfg) else {
        print("нет значка \(symbol)"); continue
    }
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 128, pixelsHigh: 128, bitsPerSample: 8, samplesPerPixel: 4,
                               hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    let s = img.size, k = min(112 / s.width, 112 / s.height)
    let r = NSRect(x: (128 - s.width * k) / 2, y: (128 - s.height * k) / 2, width: s.width * k, height: s.height * k)
    img.draw(in: r)
    NSColor.white.set()
    r.fill(using: .sourceAtop)
    NSGraphicsContext.restoreGraphicsState()
    try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: "\(out)/\(name).png"))
    print("ок \(name) ← \(symbol)")
}

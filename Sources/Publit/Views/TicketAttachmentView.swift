import SwiftUI
#if canImport(UIKit)
import UIKit
#endif
#if canImport(ImageIO)
import ImageIO
#endif

private final class CachedTicketImage: NSObject, @unchecked Sendable {
    let image: UIImage
    init(_ image: UIImage) { self.image = image }
}

#if os(Android)
/// Android: images decoded (upright, bounded size) by Kotlin, off the UI thread.
enum TicketImageLoader {
    static func preview(_ data: Data) async -> UIImage? {
        await Task.detached(priority: .userInitiated) {
            TicketRecognizer.image(TicketPage(data: data, pdf: false, page: 0), maxPixels: 1024)
        }.value
    }

    static func load(_ url: URL, original: Bool) async -> UIImage? {
        await Task.detached(priority: .userInitiated) { () -> UIImage? in
            guard let data = try? Data(contentsOf: url) else { return nil }
            return TicketRecognizer.image(TicketPage(data: data, pdf: false, page: 0), maxPixels: original ? 4096 : 2048)
        }.value
    }
}
#else
enum TicketImageLoader {
    private static let cache: NSCache<NSString, CachedTicketImage> = {
        let cache = NSCache<NSString, CachedTicketImage>()
        cache.countLimit = 6
        cache.totalCostLimit = 48 * 1024 * 1024
        return cache
    }()

    /// Decode only a bounded import preview off the UI thread; keep source bytes untouched.
    static func preview(_ data: Data) async -> UIImage? {
        let loaded = await Task.detached(priority: .userInitiated) { () -> CachedTicketImage? in
            guard let source = CGImageSourceCreateWithData(data as CFData, nil),
                  let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                    kCGImageSourceCreateThumbnailFromImageAlways: true,
                    kCGImageSourceCreateThumbnailWithTransform: true,
                    kCGImageSourceThumbnailMaxPixelSize: 1024,
                    kCGImageSourceShouldCacheImmediately: true,
                  ] as CFDictionary) else { return nil }
            return CachedTicketImage(UIImage(cgImage: cg))
        }.value
        return loaded?.image
    }

    static func load(_ url: URL, original: Bool) async -> UIImage? {
        let loaded = await Task.detached(priority: .userInitiated) { () -> CachedTicketImage? in
            let trace = PerformanceTrace.begin("Load ticket image")
            defer { PerformanceTrace.end(trace, "Load ticket image") }
            let modified = (try? url.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate?.timeIntervalSince1970 ?? 0
            let key = "\(url.path)|\(modified)|\(original)" as NSString
            if let hit = cache.object(forKey: key) { return hit }
            let image: UIImage?
            if original {
                image = UIImage(contentsOfFile: url.path)?.preparingForDisplay()
            } else if let source = CGImageSourceCreateWithURL(url as CFURL, nil),
                      let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                        kCGImageSourceCreateThumbnailFromImageAlways: true,
                        kCGImageSourceCreateThumbnailWithTransform: true,
                        kCGImageSourceThumbnailMaxPixelSize: 2048,
                        kCGImageSourceShouldCacheImmediately: true,
                      ] as CFDictionary) {
                image = UIImage(cgImage: cg)
            } else { image = nil }
            guard let image else { return nil }
            let box = CachedTicketImage(image)
            let cost = image.cgImage.map { $0.bytesPerRow * $0.height } ?? 0
            cache.setObject(box, forKey: key, cost: cost)
            return box
        }.value
        return loaded?.image
    }
}
#endif

/// No disk access or full-resolution image decode during a SwiftUI body evaluation.
/// The stored file is unchanged; Original bypasses the display thumbnail.
struct TicketAttachmentView: View {
    let url: URL
    let label: String
    var original = false
    @State var image: UIImage?
    @State var loading = true

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFit().accessibilityLabel(label)
            } else if loading { LogoLoader().frame(minHeight: 140) }
            else { Text("Ticket image unavailable").foregroundStyle(.secondary) }
        }
        .task(id: url) {
            loading = true
            let result = await TicketImageLoader.load(url, original: original)
            guard !Task.isCancelled else { return }
            image = result
            loading = false
        }
    }
}

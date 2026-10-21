#if canImport(Vision) && canImport(UIKit)
import Foundation
#if canImport(ImageIO)
import ImageIO
#endif
#if canImport(PDFKit)
import PDFKit
#endif
#if canImport(UIKit)
import UIKit
#endif
#if canImport(Vision)
import Vision
#endif

/// What one page of a ticket contains, read on the device. Nothing is uploaded.
struct TicketScan: @unchecked Sendable {
    var lines: [String]
    var barcodes: [TicketPass.Barcode]
    /// The most prominent colour of the page, offered as "From ticket".
    var suggestedColor: String?
}

struct TicketTextPiece: Identifiable, Sendable {
    let id: Int
    let text: String
    /// Normalized to the upright page, origin top left (same convention as barcode regions).
    let rect: CGRect
}

enum TicketRecognizer {
    enum Failure: LocalizedError {
        case unreadable
        var errorDescription: String? { L10n.t("This file could not be read.") }
    }

    static func pageCount(pdf data: Data) -> Int { PDFDocument(data: data)?.pageCount ?? 0 }

    /// A PDF page as an image, sharp enough for small print and dense codes.
    static func renderPage(pdf data: Data, page: Int, maxPixels: CGFloat = 2600) -> UIImage? {
        guard let document = PDFDocument(data: data), let p = document.page(at: page) else { return nil }
        let box = p.bounds(for: .mediaBox)
        guard box.width > 0, box.height > 0 else { return nil }
        let scale = min(maxPixels / max(box.width, box.height), 6)
        return p.thumbnail(of: CGSize(width: box.width * scale, height: box.height * scale), for: .mediaBox)
    }

    /// Text embedded in the PDF is exact, so it replaces OCR when the page has enough of it.
    static func scan(pdf data: Data, page: Int) async throws -> TicketScan {
        let (image, text) = try await Task.detached(priority: .userInitiated) { () throws -> (CGImage, String?) in
            guard let cg = TicketRecognizer.renderPage(pdf: data, page: page)?.cgImage else { throw Failure.unreadable }
            return (cg, PDFDocument(data: data)?.page(at: page)?.string)
        }.value
        try Task.checkCancellation()
        let embedded = (text ?? "").components(separatedBy: .newlines).map(TicketTextParser.clean).filter { !$0.isEmpty }
        // Embedded text is exact and usable: still detect codes, but avoid expensive redundant OCR.
        // If Vision cannot run (simulator without inference context, transient Neural Engine errors),
        // keep a text-only result so PDF imports do not hard-fail when the page already has enough text.
        var result: TicketScan
        do {
            result = try await scan(image: image, recognizeText: embedded.count < 3)
        } catch {
            guard embedded.count >= 3 else { throw error }
            result = TicketScan(lines: embedded, barcodes: [], suggestedColor: nil)
        }
        try Task.checkCancellation()
        if embedded.count >= 3 { result.lines = embedded }
        return result
    }

    static func scan(imageData data: Data) async throws -> TicketScan {
        let image = try await Task.detached(priority: .userInitiated) { () throws -> CGImage in
            guard let cg = TicketRecognizer.loadImage(data) else { throw Failure.unreadable }
            return cg
        }.value
        try Task.checkCancellation()
        return try await scan(image: image)
    }

    /// Auto-select only a unique recognizable ticket page. Scan sequentially, releasing each
    /// raster before the next page; never allocate a full PDF worth of rendered images.
    static func uniqueTicket(pdf data: Data, count: Int) async throws -> (page: Int, scan: TicketScan)? {
        guard count > 1, count <= TicketImportPolicy.maxAutomaticPages else { return nil }
        var candidate: (page: Int, scan: TicketScan)?
        for page in 0..<count {
            try Task.checkCancellation()
            let scanned = try await scan(pdf: data, page: page)
            let draft = TicketTextParser.parse(lines: scanned.lines, barcodes: scanned.barcodes, strictValidity: true)
            if TicketImportPolicy.isRecognizable(draft, barcodes: scanned.barcodes) {
                guard candidate == nil else { return nil }
                candidate = (page, scanned)
            }
        }
        return candidate
    }

    /// The image upright, as read for recognition (code regions refer to this orientation).
    static func loadImage(_ data: Data, maxPixels: Int = 3200) -> CGImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        return CGImageSourceCreateThumbnailAtIndex(source, 0, [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixels,
        ] as CFDictionary)
    }

    static func scan(image: CGImage, recognizeText: Bool = true) async throws -> TicketScan {
        try await Task.detached(priority: .userInitiated) { () throws -> TicketScan in
            let trace = PerformanceTrace.begin("Recognise ticket")
            defer { PerformanceTrace.end(trace, "Recognise ticket") }
            let codes = VNDetectBarcodesRequest()
            codes.symbologies = [.qr, .aztec, .pdf417, .code128, .dataMatrix, .ean13, .ean8, .upce,
                                 .code39, .code39Checksum, .code39FullASCII, .code39FullASCIIChecksum,
                                 .code93, .code93i, .itf14, .i2of5, .i2of5Checksum, .codabar]
            let text = VNRecognizeTextRequest()
            configure(text)
            try Task.checkCancellation()
            try VNImageRequestHandler(cgImage: image, orientation: .up).perform(recognizeText ? [codes, text] : [codes])
            try Task.checkCancellation()
            var seen = Set<String>()
            let barcodes = (codes.results ?? []).compactMap(TicketRecognizer.barcode).filter { seen.insert($0.dedupeKey).inserted }
            return TicketScan(lines: TicketRecognizer.lines(text.results ?? []), barcodes: barcodes,
                              suggestedColor: TicketRecognizer.dominantColor(image))
        }.value
    }

    /// Exact embedded PDF text with page-relative hit boxes. Raster-only/rotated PDFs use OCR.
    /// Coordinates match renderPage's media-box thumbnail (upright, top-left origin).
    static func pieces(pdf data: Data, page number: Int) -> [TicketTextPiece] {
        guard let page = PDFDocument(data: data)?.page(at: number), page.rotation % 360 == 0 else { return [] }
        let box = page.bounds(for: .mediaBox)
        guard box.width > 0, box.height > 0,
              let lines = page.selection(for: box)?.selectionsByLine() else { return [] }
        let unit = CGRect(x: 0, y: 0, width: 1, height: 1)
        let values = lines.compactMap { line -> (String, CGRect)? in
            let text = TicketTextParser.clean(line.string ?? "")
            let b = line.bounds(for: page)
            guard !text.isEmpty, !b.isNull, b.width > 0, b.height > 0 else { return nil }
            let rect = CGRect(x: (b.minX - box.minX) / box.width,
                              y: 1 - (b.maxY - box.minY) / box.height,
                              width: b.width / box.width, height: b.height / box.height).intersection(unit)
            guard !rect.isNull, !rect.isEmpty else { return nil }
            return (text, rect)
        }.sorted { a, b in
            a.1.minY == b.1.minY ? a.1.minX < b.1.minX : a.1.minY < b.1.minY
        }
        return values.enumerated().map { TicketTextPiece(id: $0.offset, text: $0.element.0, rect: $0.element.1) }
    }

    /// Individually selectable OCR regions for the review screen; nothing leaves the device.
    static func pieces(image: CGImage) async throws -> [TicketTextPiece] {
        try await Task.detached(priority: .userInitiated) { () throws -> [TicketTextPiece] in
            let request = VNRecognizeTextRequest()
            configure(request)
            try Task.checkCancellation()
            try VNImageRequestHandler(cgImage: image, orientation: .up).perform([request])
            try Task.checkCancellation()
            return (request.results ?? []).enumerated().compactMap { index, observation in
                guard let text = observation.topCandidates(1).first?.string, !text.isEmpty else { return nil }
                let box = observation.boundingBox
                return TicketTextPiece(id: index, text: text,
                                       rect: CGRect(x: box.minX, y: 1 - box.maxY, width: box.width, height: box.height))
            }
        }.value
    }

    private static func configure(_ text: VNRecognizeTextRequest) {
        text.recognitionLevel = .accurate
        // Names, numbers and codes: language correction changes more than it fixes.
        text.usesLanguageCorrection = false
        text.automaticallyDetectsLanguage = true
        if let supported = try? text.supportedRecognitionLanguages() {
            let wanted = ["de-DE", "en-US", "fr-FR", "it-IT", "es-ES", "pt-BR", "nl-NL"].filter(supported.contains)
            if !wanted.isEmpty { text.recognitionLanguages = wanted }
        }
    }

    /// Vision returns text pieces; pieces on the same row become one line ("Von:" + "Wien Hbf").
    static func lines(_ observations: [VNRecognizedTextObservation]) -> [String] {
        let pieces = observations.compactMap { o -> (box: CGRect, text: String)? in
            guard let text = o.topCandidates(1).first?.string else { return nil }
            return (o.boundingBox, text)
        }.sorted { $0.box.midY > $1.box.midY }
        var rows: [[(box: CGRect, text: String)]] = []
        for piece in pieces {
            if let last = rows.last?.last, abs(last.box.midY - piece.box.midY) < min(last.box.height, piece.box.height) * 0.5 {
                rows[rows.count - 1].append(piece)
            } else {
                rows.append([piece])
            }
        }
        return rows.map { row in row.sorted { $0.box.minX < $1.box.minX }.map(\.text).joined(separator: " ") }
    }

    private static func barcode(_ o: VNBarcodeObservation) -> TicketPass.Barcode? {
        let format: TicketPass.Barcode.Format
        switch o.symbology {
        case .qr: format = .qr
        case .aztec: format = .aztec
        case .pdf417: format = .pdf417
        case .code128: format = .code128
        case .dataMatrix: format = .dataMatrix
        case .ean13: format = .ean13
        case .ean8: format = .ean8
        case .upce: format = .upce
        case .code39, .code39Checksum, .code39FullASCII, .code39FullASCIIChecksum: format = .code39
        case .code93, .code93i: format = .code93
        case .itf14, .i2of5, .i2of5Checksum: format = .itf
        case .codabar: format = .codabar
        default: return nil
        }
        // Vision's box has its origin bottom left; the stored region top left.
        let box = o.boundingBox
        let region = TicketPass.Barcode.Region(x: Double(box.minX), y: Double(1 - box.maxY),
                                               width: Double(box.width), height: Double(box.height))
        let descriptor = o.barcodeDescriptor.flatMap { try? NSKeyedArchiver.archivedData(withRootObject: $0, requiringSecureCoding: true) }
        let message = o.payloadStringValue
        // Raw bytes only when the text does not carry them exactly (binary rail ticket codes).
        var payload = o.payloadData
        if let message, let bytes = payload, Data(message.utf8) == bytes { payload = nil }
        guard message != nil || payload != nil || descriptor != nil else { return nil }
        return TicketPass.Barcode(format: format, message: payload == nil ? message : nil, payload: payload, descriptor: descriptor,
                                  region: region)
    }

    /// Average of the clearly coloured pixels; nil for black-and-white tickets.
    static func dominantColor(_ image: CGImage) -> String? {
        let side = 32
        var pixels = [UInt8](repeating: 0, count: side * side * 4)
        let drawn = pixels.withUnsafeMutableBytes { buffer -> Bool in
            guard let context = CGContext(data: buffer.baseAddress, width: side, height: side, bitsPerComponent: 8,
                                          bytesPerRow: side * 4, space: CGColorSpaceCreateDeviceRGB(),
                                          bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            context.interpolationQuality = .medium
            context.draw(image, in: CGRect(x: 0, y: 0, width: side, height: side))
            return true
        }
        guard drawn else { return nil }
        var (r, g, b, weight) = (0.0, 0.0, 0.0, 0.0)
        for i in stride(from: 0, to: pixels.count, by: 4) {
            let (pr, pg, pb) = (Double(pixels[i]) / 255, Double(pixels[i + 1]) / 255, Double(pixels[i + 2]) / 255)
            let maxC = max(pr, pg, pb), minC = min(pr, pg, pb)
            let saturation = maxC == 0 ? 0 : (maxC - minC) / maxC
            guard saturation > 0.35, maxC > 0.2 else { continue }
            r += pr * saturation; g += pg * saturation; b += pb * saturation; weight += saturation
        }
        guard weight > Double(side * side) * 0.03 else { return nil }
        return PassStyle.hex(LineColors.RGBValues(r: r / weight, g: g / weight, b: b / weight))
    }
}
#endif

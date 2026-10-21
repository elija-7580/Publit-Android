#if os(Android)
import SwiftUI

// Android: reading and drawing tickets on the device, with the same API the iOS code has
// (Vision/PDFKit → ML Kit/PdfRenderer, Core Image → ZXing; see Skip/DroidTickets.kt).
// Nothing is uploaded.

/// Android pickers and the document scanner; each returns a copy of the chosen file in the cache.
@MainActor
enum DroidPicker {
    static func image() async -> URL? { await pick { Droid.call("pickImage", $0) } }
    static func file(_ mimeTypes: [String]) async -> URL? { await pick { Droid.call("pickFile", $0, mimeTypes.joined(separator: ",")) } }
    static func scan() async -> URL? { await pick { Droid.call("scanDocument", $0) } }
    static var canScan: Bool { Droid.bool("canScanDocuments") }

    private static func pick(_ start: (String) -> Void) async -> URL? {
        let path = await DroidRequests.shared.run(start)
        return path.isEmpty ? nil : URL(fileURLWithPath: path)
    }
}

/// Files handed to the Kotlin side: data written once into the cache, removed again after use.
enum DroidScratch {
    static var directory: URL {
        let base = Droid.string("cacheDir").map { URL(fileURLWithPath: $0) } ?? FileManager.default.temporaryDirectory
        let dir = base.appendingPathComponent("Tickets", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    static func file(_ ext: String) -> URL { directory.appendingPathComponent(UUID().uuidString + "." + ext) }

    /// Writes `data`, runs `body` with its path, then deletes it.
    static func with<T>(_ data: Data, pdf: Bool, _ body: (String) throws -> T) rethrows -> T? {
        let url = file(pdf ? "pdf" : "img")
        guard (try? data.write(to: url)) != nil else { return nil }
        defer { try? FileManager.default.removeItem(at: url) }
        return try body(url.path)
    }

    /// A PNG written by Kotlin, read into an image and deleted.
    static func image(_ write: (String) -> Bool) -> UIImage? {
        let out = file("png")
        defer { try? FileManager.default.removeItem(at: out) }
        guard write(out.path), let data = try? Data(contentsOf: out) else { return nil }
        return UIImage(data: data)
    }
}

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

/// A page as Kotlin sees it: a file plus the page number (PDF) — the Android stand-in for CGImage.
struct TicketPage: @unchecked Sendable {
    let data: Data
    let pdf: Bool
    let page: Int
}

enum TicketRecognizer {
    enum Failure: LocalizedError {
        case unreadable
        var errorDescription: String? { L10n.t("This file could not be read.") }
    }

    static func pageCount(pdf data: Data) -> Int {
        DroidScratch.with(data, pdf: true) { Int(Droid.double("pdfPageCount", $0) ?? 0) } ?? 0
    }

    /// A PDF page as an image, sharp enough for small print and dense codes.
    static func renderPage(pdf data: Data, page: Int, maxPixels: CGFloat = 2600) -> UIImage? {
        image(TicketPage(data: data, pdf: true, page: page), maxPixels: Double(maxPixels))
    }

    static func image(_ page: TicketPage, maxPixels: Double = 2600) -> UIImage? {
        DroidScratch.with(page.data, pdf: page.pdf) { path in
            DroidScratch.image { Droid.bool("renderTicketPage", path, page.pdf, Double(page.page), maxPixels, $0) }
        } ?? nil
    }

    /// PdfRenderer has no text layer: every page is read with ML Kit's text recognition.
    static func scan(pdf data: Data, page: Int) async throws -> TicketScan {
        try await scan(TicketPage(data: data, pdf: true, page: page))
    }

    static func scan(imageData data: Data) async throws -> TicketScan {
        try await scan(TicketPage(data: data, pdf: false, page: 0))
    }

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

    static func scan(_ page: TicketPage, recognizeText: Bool = true) async throws -> TicketScan {
        try await Task.detached(priority: .userInitiated) { () throws -> TicketScan in
            let trace = PerformanceTrace.begin("Recognise ticket")
            defer { PerformanceTrace.end(trace, "Recognise ticket") }
            let text = DroidScratch.with(page.data, pdf: page.pdf) {
                Droid.string("scanTicket", $0, page.pdf, Double(page.page), recognizeText)
            } ?? nil
            try Task.checkCancellation()
            guard let text, let json = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any] else {
                throw Failure.unreadable
            }
            let lines = (json["lines"] as? [String] ?? []).map(TicketTextParser.clean).filter { !$0.isEmpty }
            let codes = (json["codes"] as? [[String: Any]] ?? []).compactMap(barcode)
            return TicketScan(lines: lines, barcodes: codes, suggestedColor: json["color"] as? String)
        }.value
    }

    private static func barcode(_ o: [String: Any]) -> TicketPass.Barcode? {
        guard let raw = o["format"] as? String, let format = TicketPass.Barcode.Format(rawValue: raw) else { return nil }
        let message = o["text"] as? String
        let payload = (o["bytes"] as? String).flatMap { Data(base64Encoded: $0) }
        guard message != nil || payload != nil else { return nil }
        var region: TicketPass.Barcode.Region?
        if let x = o["x"] as? Double, let y = o["y"] as? Double, let w = o["w"] as? Double, let h = o["h"] as? Double {
            region = TicketPass.Barcode.Region(x: x, y: y, width: w, height: h)
        }
        return TicketPass.Barcode(format: format, message: payload == nil ? message : nil, payload: payload, descriptor: nil, region: region)
    }

    /// No text layer on Android: the review screen uses OCR pieces.
    static func pieces(pdf data: Data, page number: Int) -> [TicketTextPiece] { [] }

    /// Individually selectable OCR regions for the review screen; nothing leaves the device.
    static func pieces(_ page: TicketPage) async throws -> [TicketTextPiece] {
        try await Task.detached(priority: .userInitiated) { () throws -> [TicketTextPiece] in
            let text = DroidScratch.with(page.data, pdf: page.pdf) { Droid.string("ticketPieces", $0, page.pdf, Double(page.page)) } ?? nil
            guard let text, let items = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [[String: Any]] else {
                throw Failure.unreadable
            }
            return items.enumerated().compactMap { index, o in
                guard let t = o["text"] as? String, let x = o["x"] as? Double, let y = o["y"] as? Double,
                      let w = o["w"] as? Double, let h = o["h"] as? Double else { return nil }
                return TicketTextPiece(id: index, text: t, rect: CGRect(x: x, y: y, width: w, height: h))
            }
        }.value
    }
}

/// The original ticket a code was read from, for codes shown cut out of it.
enum BarcodeSource: @unchecked Sendable {
    case file(URL, TicketPass.Document.Kind, page: Int)
    case data(Data, TicketPass.Document.Kind, page: Int)
}

/// Draws a stored code again: black on white, every module a whole number of screen pixels,
/// so scanners read it like the original. Codes ZXing cannot draw (EAN, ITF …) are cut out of the original.
enum BarcodeRenderer {
    struct Rendered {
        let image: UIImage
        let exact: Bool
    }

    static func render(_ code: TicketPass.Barcode, fitting size: CGSize, scale: CGFloat, source: BarcodeSource?) -> Rendered? {
        if let image = redrawn(code, maxPixels: CGSize(width: size.width * scale, height: size.height * scale), scale: scale) {
            return Rendered(image: image, exact: true)
        }
        if let source, let crop = cutOut(code, from: source) { return Rendered(image: crop, exact: false) }
        return nil
    }

    static func redrawn(_ code: TicketPass.Barcode, maxPixels: CGSize, scale: CGFloat) -> UIImage? {
        let text = code.payload == nil ? (code.message ?? "") : ""
        let bytes = code.payload?.base64EncodedString() ?? ""
        guard !text.isEmpty || !bytes.isEmpty else { return nil }
        return DroidScratch.image {
            Droid.bool("renderCode", code.format.rawValue, text, bytes, Double(maxPixels.width), Double(maxPixels.height), $0)
        }
    }

    /// The code's region of the original page, with a quiet zone, exactly as it was issued.
    static func cutOut(_ code: TicketPass.Barcode, from source: BarcodeSource) -> UIImage? {
        guard let r = code.region?.padded() else { return nil }
        let data: Data, kind: TicketPass.Document.Kind, page: Int
        switch source {
        case .file(let url, let k, let n):
            guard let d = try? Data(contentsOf: url) else { return nil }
            (data, kind, page) = (d, k, n)
        case .data(let d, let k, let n):
            (data, kind, page) = (d, k, n)
        }
        return DroidScratch.with(data, pdf: kind == .pdf) { path in
            DroidScratch.image { Droid.bool("cropTicket", path, kind == .pdf, Double(page), r.x, r.y, r.width, r.height, $0) }
        } ?? nil
    }

    /// For sharing: about `targetWidth` pixels wide.
    static func image(_ code: TicketPass.Barcode, targetWidth: CGFloat = 900) -> UIImage? {
        redrawn(code, maxPixels: CGSize(width: targetWidth, height: targetWidth), scale: 1)
    }
}
#endif

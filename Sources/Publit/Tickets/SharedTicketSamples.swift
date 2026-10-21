#if DEBUG && canImport(UIKit)
import Foundation
#if canImport(UIKit)
import UIKit
#endif

/// Synthetic documents for simulator QA, not passenger tickets. Routed through the real file importer.
@MainActor
enum SharedTicketSamples {
    static let ticketLines = ["ÖBB", "Sparschiene", "Von: Wien Hbf", "Nach: Graz Hbf",
                              "Gültig von: 12.10.2030 14:00", "Gültig bis: 12.10.2030 18:00",
                              "Ticket-Nr.: TEST12345", "2. Klasse"]

    static func pdf(_ pages: [[String]]) -> Data {
        UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 595, height: 842)).pdfData { context in
            for lines in pages {
                context.beginPage()
                draw(lines)
            }
        }
    }

    static func image() -> Data {
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 595, height: 842))
        return renderer.pngData { context in
            UIColor.white.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 595, height: 842))
            draw(ticketLines)
        }
    }

    static func file(_ kind: String) throws -> URL {
        let data: Data
        let ext: String
        switch kind {
        case "image": data = image(); ext = "png"
        case "unique": data = pdf([["Read me", "Keep your original document", "Have a good journey"], ticketLines]); ext = "pdf"
        case "ambiguous": data = pdf([ticketLines, ticketLines]); ext = "pdf"
        case "unknown": data = pdf([["Shopping list", "Apples", "Bread"]]); ext = "pdf"
        default: data = pdf([ticketLines]); ext = "pdf"
        }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("shared-ticket-\(kind).\(ext)")
        try data.write(to: url, options: .atomic)
        return url
    }

    private static func draw(_ lines: [String]) {
        for (i, line) in lines.enumerated() {
            (line as NSString).draw(in: CGRect(x: 40, y: 50 + i * 48, width: 510, height: 44),
                                   withAttributes: [.font: UIFont.systemFont(ofSize: 22), .foregroundColor: UIColor.black])
        }
    }
}
#endif

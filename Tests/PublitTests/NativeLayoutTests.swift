// SwiftPM on Linux covers the core; these tests additionally compile/run with the iOS app target.
#if canImport(UIKit) && canImport(PDFKit)
import XCTest
import SwiftUI
import UIKit
import PDFKit
@testable import Publit

@MainActor
final class NativeLayoutTests: XCTestCase {
    func testDurationFigureInitializesScaledMetric() {
        // Regression for build 31's native-only missing-wrappedValue compile failure.
        _ = DurationFigure(seconds: 44 * 60)
        _ = DurationFigure(seconds: 4 * 60, size: 20)
    }

    func testPDFTextMappingUsesEmbeddedTextAndNormalizedBoxes() throws {
        let renderer = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 360, height: 480))
        let data = renderer.pdfData { context in
            context.beginPage()
            for (index, text) in ["DEMO KEIN FAHRSCHEIN", "Von: Wien Hbf", "Nach: Innsbruck Hbf"].enumerated() {
                (text as NSString).draw(at: CGPoint(x: 24, y: 32 + index * 40),
                    withAttributes: [.font: UIFont.systemFont(ofSize: 16), .foregroundColor: UIColor.black])
            }
        }
        let pieces = TicketRecognizer.pieces(pdf: data, page: 0)
        XCTAssertTrue(pieces.contains { $0.text.contains("Wien Hbf") })
        XCTAssertTrue(pieces.contains { $0.text.contains("Innsbruck Hbf") })
        for piece in pieces {
            XCTAssertGreaterThan(piece.rect.width, 0)
            XCTAssertGreaterThan(piece.rect.height, 0)
            XCTAssertGreaterThanOrEqual(piece.rect.minX, 0)
            XCTAssertGreaterThanOrEqual(piece.rect.minY, 0)
            XCTAssertLessThanOrEqual(piece.rect.maxX, 1)
            XCTAssertLessThanOrEqual(piece.rect.maxY, 1)
        }
        XCTAssertEqual(Set(pieces.map(\.id)).count, pieces.count)
        XCTAssertTrue(TicketRecognizer.pieces(pdf: data, page: 8).isEmpty)
        XCTAssertTrue(TicketRecognizer.pieces(pdf: Data(), page: 0).isEmpty)
    }
}
#endif

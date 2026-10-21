import Foundation
import XCTest
@testable import Publit

final class SharedTicketPolicyTests: XCTestCase {
    func testRecognizablePageDoesNotInventDates() {
        let draft = TicketTextParser.parse(lines: ["ÖBB", "Sparschiene", "Von: Wien Hbf", "Nach: Graz Hbf"], strictValidity: true)
        XCTAssertTrue(TicketImportPolicy.isRecognizable(draft, barcodes: []))
        XCTAssertEqual(TicketPass(draft: draft, barcode: nil).validity(), .unknown)
    }

    func testAdvertisementAndWebsiteQRNeedReview() {
        let advertisement = TicketTextParser.parse(lines: ["ÖBB", "Travel with us", "oebb.at"])
        let website = TicketPass.Barcode(format: .qr, message: "https://oebb.at")
        XCTAssertFalse(TicketImportPolicy.isRecognizable(advertisement, barcodes: [website]))
        XCTAssertFalse(TicketImportPolicy.isRecognizable(TicketDraft(), barcodes: [website]))
    }

    func testUnlabelledDatesAreNotAutomaticValidity() {
        let d = TicketTextParser.parse(lines: ["ÖBB", "Sparschiene", "12.10.2030", "13.10.2030"], strictValidity: true)
        XCTAssertNil(d.validFrom)
        XCTAssertNil(d.validUntil)
        let birth = TicketTextParser.parse(lines: ["ÖBB", "Sparschiene", "Geb.: 12.10.1990", "Kauf: 11.10.2030"], strictValidity: true)
        XCTAssertNil(birth.validFrom)
        XCTAssertNil(birth.validUntil)
    }

    func testLabelledValidityAndExpiredTicketsArePreserved() {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "Europe/Vienna")!
        let d = TicketTextParser.parse(lines: ["ÖBB", "Sparschiene", "Gültig von: 12.10.2020 14:00", "Gültig bis: 12.10.2020 18:00"], calendar: cal, strictValidity: true)
        XCTAssertTrue(TicketImportPolicy.isRecognizable(d, barcodes: []))
        XCTAssertNotNil(d.validFrom)
        guard case .expired = TicketPass(draft: d, barcode: nil).validity(at: Date(timeIntervalSince1970: 1_800_000_000)) else {
            return XCTFail("must not make an expired document valid")
        }
    }

    func testReversedLabelledValidityNeedsReview() {
        let d = TicketTextParser.parse(lines: ["ÖBB", "Sparschiene", "Gültig von: 13.10.2030", "Gültig bis: 12.10.2030"], strictValidity: true)
        XCTAssertFalse(TicketImportPolicy.isRecognizable(d, barcodes: []))
    }

    func testFingerprintIncludesDocumentAndPage() {
        let a = Data("ticket".utf8)
        XCTAssertEqual(TicketImportPolicy.fingerprint(a, page: 0), TicketImportPolicy.fingerprint(a, page: 0))
        XCTAssertNotEqual(TicketImportPolicy.fingerprint(a, page: 0), TicketImportPolicy.fingerprint(a, page: 1))
        XCTAssertNotEqual(TicketImportPolicy.fingerprint(a, page: 0), TicketImportPolicy.fingerprint(Data("other".utf8), page: 0))
    }

    func testOldDocumentsDecodeWithoutFingerprint() throws {
        let json = Data(#"{"file":"old.pdf","kind":"pdf","page":0}"#.utf8)
        let old = try JSONDecoder().decode(TicketPass.Document.self, from: json)
        XCTAssertNil(old.fingerprint)
        XCTAssertEqual(old.page, 0)
    }

    @MainActor func testFileHandoffOpensTicketsWithoutBreakingDeepLinks() async {
        let app = AppState()
        let local = URL(fileURLWithPath: "/tmp/ticket.pdf")
        app.open(local)
        XCTAssertEqual(app.tab, .tickets)
        XCTAssertEqual(app.sharedTicketFile?.url, local)
        XCTAssertNil(app.deepLink)
        app.open(DeepLink.trip("saved").url)
        XCTAssertEqual(app.deepLink, .trip("saved"))
        XCTAssertEqual(app.tab, .plan)
    }

    @MainActor func testRemoteDocumentDoesNotGetDownloaded() async {
        let app = AppState()
        app.open(URL(string: "https://example.org/private.pdf")!)
        XCTAssertNil(app.sharedTicketFile)
    }
}

final class SharedTicketStoreTests: XCTestCase {
    @MainActor func testRepeatedShareIsIdempotentAndOriginalIsUnchanged() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = PassStore(directory: dir)
        let bytes = Data("unaltered source".utf8)
        let first = try await store.add(TicketPass(title: "Ticket"), document: bytes, kind: .pdf, deduplicate: true)
        let again = try await store.add(TicketPass(title: "Ticket"), document: bytes, kind: .pdf, deduplicate: true)
        XCTAssertEqual(first.id, again.id)
        XCTAssertEqual(store.passes.count, 1)
        XCTAssertEqual(try Data(contentsOf: XCTUnwrap(store.documentURL(first))), bytes)
        XCTAssertEqual(PassStore(directory: dir).passes.count, 1)
        let secondPage = try await store.add(TicketPass(title: "Return"), document: bytes, kind: .pdf, page: 1, deduplicate: true)
        XCTAssertNotEqual(first.id, secondPage.id)
        XCTAssertEqual(store.passes.count, 2)
    }

    @MainActor func testConcurrentSharesDoNotDuplicate() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = PassStore(directory: dir)
        let data = Data("ticket".utf8)
        async let a = store.add(TicketPass(), document: data, kind: .image, deduplicate: true)
        async let b = store.add(TicketPass(), document: data, kind: .image, deduplicate: true)
        let (first, second) = try await (a, b)
        XCTAssertEqual(first.id, second.id)
        XCTAssertEqual(store.passes.count, 1)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(at: store.documentDirectory, includingPropertiesForKeys: nil).count, 1)
    }

    @MainActor func testCancelledShareDoesNotSave() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = PassStore(directory: dir)
        let task = Task { try await store.add(TicketPass(), document: Data("ticket".utf8), kind: .image, deduplicate: true) }
        task.cancel()
        do { _ = try await task.value; XCTFail("cancelled import saved") } catch is CancellationError {} catch { XCTFail("\(error)") }
        XCTAssertTrue(store.passes.isEmpty)
    }
}

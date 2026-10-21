import Foundation
import XCTest
@testable import Publit

/// Operator logos (Wikidata / Wikimedia Commons), logo files in the store, code formats and regions.
final class TicketLogoTests: XCTestCase {
    private final class Requests: @unchecked Sendable {
        private let lock = NSLock()
        private var urls: [URL] = []
        func add(_ u: URL) { lock.lock(); urls.append(u); lock.unlock() }
        var all: [URL] { lock.lock(); defer { lock.unlock() }; return urls }
    }

    func testCurrentLogoComesFirst() throws {
        let found = OperatorLogos.entities(try Fixtures.data("wikidata_entities"), language: "de")
        let mvv = try XCTUnwrap(found["Q259000"])
        XCTAssertEqual(mvv.logos.first, "Münchner Verkehrsverbund Logo 2022.svg")
        XCTAssertFalse(mvv.logos.prefix(2).contains { $0.lowercased().contains("white") }, "white variant is for dark backgrounds")
        XCTAssertEqual(mvv.logos.last.map { $0.contains("1972") || $0.contains("2018") || $0.contains("Tarifverbund") }, true)
        XCTAssertEqual(found["Q83822"]?.logos.first, "Logo ÖBB.svg")
        XCTAssertEqual(found["Q83822"]?.label, "Österreichische Bundesbahnen")
        XCTAssertNotNil(found["Q83822"]?.website)
    }

    func testCandidatesUseCommonsPNGAndWebsiteIcon() throws {
        let found = OperatorLogos.entities(try Fixtures.data("wikidata_entities"), language: "de")
        let list = OperatorLogos.candidates(found, order: ["Q83822"])
        let first = try XCTUnwrap(list.first)
        XCTAssertEqual(first.source, .wikimedia)
        XCTAssertEqual(first.url.host, "commons.wikimedia.org")
        XCTAssertTrue(first.url.absoluteString.contains("Special:FilePath/Logo_%C3%96BB.svg"), first.url.absoluteString)
        XCTAssertTrue(first.url.absoluteString.hasSuffix("width=320"))
        XCTAssertEqual(list.last?.source, .website)
        XCTAssertEqual(Set(list.map(\.id)).count, list.count)
    }

    func testSearchHitsAreRead() throws {
        let ids = OperatorLogos.ids(fromSearch: try Fixtures.data("wikidata_search"))
        XCTAssertEqual(ids.first, "Q668849")
        XCTAssertEqual(OperatorLogos.ids(fromSearch: Data("nonsense".utf8)), [])
    }

    func testWebsiteIconDomain() {
        XCTAssertEqual(OperatorLogos.websiteIconURL("https://www.oebb.at/")?.absoluteString,
                       "https://www.google.com/s2/favicons?domain=oebb.at&sz=256")
        XCTAssertEqual(OperatorLogos.websiteIconURL("bahn.de")?.absoluteString,
                       "https://www.google.com/s2/favicons?domain=bahn.de&sz=256")
        XCTAssertNil(OperatorLogos.websiteIconURL("not a site"))
    }

    func testKnownOperatorSkipsTheSearch() async throws {
        let entities = try Fixtures.data("wikidata_entities")
        let requests = Requests()
        let list = await OperatorLogos.lookup(name: "ÖBB", language: "de") { url in
            requests.add(url)
            return entities
        }
        XCTAssertEqual(requests.all.count, 1)
        XCTAssertTrue(requests.all[0].absoluteString.contains("wbgetentities"))
        XCTAssertTrue(requests.all[0].absoluteString.contains("Q83822"))
        XCTAssertEqual(list.first?.credit, "Wikimedia Commons: Logo ÖBB.svg")
    }

    func testUnknownOperatorIsSearched() async throws {
        let search = try Fixtures.data("wikidata_search"), entities = try Fixtures.data("wikidata_entities")
        let requests = Requests()
        _ = await OperatorLogos.lookup(name: "Linz AG Linien", language: "de") { url in
            requests.add(url)
            return url.absoluteString.contains("wbsearchentities") ? search : entities
        }
        XCTAssertEqual(requests.all.count, 2)
        XCTAssertTrue(requests.all[0].absoluteString.contains("wbsearchentities"))
    }

    func testOfflineLookupFallsBackToTheKnownWebsite() async {
        let list = await OperatorLogos.lookup(name: "FlixBus", language: "en") { _ in throw URLError(.notConnectedToInternet) }
        XCTAssertEqual(list.map(\.source), [.website])
        let none = await OperatorLogos.lookup(name: "x", language: "en") { _ in Data() }
        XCTAssertTrue(none.isEmpty)
    }

    func testWalletTakesOnlyItsFourFormats() {
        XCTAssertTrue(TicketPass.Barcode(format: .qr, message: "A").walletReady)
        XCTAssertTrue(TicketPass.Barcode(format: .code128, message: "A").walletReady)
        XCTAssertFalse(TicketPass.Barcode(format: .dataMatrix, message: "A").walletReady)
        XCTAssertFalse(TicketPass.Barcode(format: .ean13, message: "4006381333931").walletReady)
        XCTAssertTrue(TicketPass.Barcode.Format.ean13.isLinear)
        XCTAssertFalse(TicketPass.Barcode.Format.ean13.canRedraw)
        XCTAssertTrue(TicketPass.Barcode.Format.dataMatrix.isSquare)
    }

    func testWalletRefusesCodesItCannotShow() throws {
        let config = try XCTUnwrap(WalletPassConfig(passTypeIdentifier: "pass.test", teamIdentifier: "ABCDE12345",
                                                    signerURL: "https://example.com/sign"))
        var pass = TicketPass(title: "T", barcode: .init(format: .ean13, message: "4006381333931"))
        XCTAssertThrowsError(try WalletPassBuilder.pass(for: pass, config: config))
        pass.barcode = .init(format: .aztec, message: "A")
        XCTAssertNoThrow(try WalletPassBuilder.pass(for: pass, config: config))
    }

    func testRegionKeepsAQuietZoneInsideThePage() {
        let r = TicketPass.Barcode.Region(x: 0.02, y: 0.5, width: 0.5, height: 0.1).padded()
        XCTAssertEqual(r.x, 0, accuracy: 1e-9)
        XCTAssertEqual(r.y, 0.488, accuracy: 1e-9)
        XCTAssertEqual(r.x + r.width, 0.58, accuracy: 1e-9)
        XCTAssertLessThanOrEqual(r.y + r.height, 1)
    }

    func testOldPassesWithoutLogoOrRegionStillLoad() throws {
        let json = #"{"id":"p","title":"Old","category":"single","symbol":"train","style":{"background":"D70015"},"barcode":{"format":"qr","message":"X"},"createdAt":0,"updatedAt":0}"#
        let pass = try JSONDecoder().decode(TicketPass.self, from: Data(json.utf8))
        XCTAssertNil(pass.logo)
        XCTAssertNil(pass.barcode?.region)
        XCTAssertEqual(pass.barcode?.format, .qr)
    }
}

@MainActor
final class PassLogoStoreTests: XCTestCase {
    func testLogoIsStoredReplacedAndRemovedWithThePass() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("logos-\(UUID().uuidString)", isDirectory: true)
        let store = PassStore(directory: dir)
        let legacy = TicketPass(title: "T", legacyId: "old-1")
        store.insert(legacy)
        XCTAssertEqual(store.pass(linkedTo: "old-1")?.id, legacy.id)
        XCTAssertEqual(store.pass(linkedTo: legacy.id)?.id, legacy.id)
        store.setLogo(Data([1, 2]), source: "Wikimedia Commons: X.svg", for: legacy.id)
        let first = try XCTUnwrap(store.logoURL(XCTUnwrap(store.pass(legacy.id))))
        XCTAssertEqual(try Data(contentsOf: first), Data([1, 2]))
        XCTAssertEqual(store.pass(legacy.id)?.logo?.source, "Wikimedia Commons: X.svg")
        store.setLogo(nil, source: nil, for: legacy.id)
        XCTAssertNil(store.pass(legacy.id)?.logo)
        XCTAssertFalse(FileManager.default.fileExists(atPath: first.path))
        store.setLogo(Data([3]), source: nil, for: legacy.id)
        let second = try XCTUnwrap(store.logoURL(XCTUnwrap(store.pass(legacy.id))))
        store.remove(legacy.id)
        XCTAssertFalse(FileManager.default.fileExists(atPath: second.path))
    }
}

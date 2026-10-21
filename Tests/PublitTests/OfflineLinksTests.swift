import XCTest
@testable import Publit

final class OfflineLinksTests: XCTestCase {
    func testSubstitutePlaceholders() {
        XCTAssertEqual(L10n.substitute("Tight change at %@ (%lld min)", ["Nordfriedhof", 1]), "Tight change at Nordfriedhof (1 min)")
        XCTAssertEqual(L10n.substitute("%2$@ ← %1$@", ["A", "B"]), "B ← A")
        XCTAssertEqual(L10n.substitute("100%% sure, %d", [3]), "100% sure, 3")
        XCTAssertEqual(L10n.f("Leave in %lld min", 6), "Leave in 6 min")
    }

    func testCacheFreshStaleAndDisk() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let cache = ResponseCache(directory: dir)
        let t0 = Date(timeIntervalSince1970: 1_000_000)
        cache.store("k", data: Data("{\"a\":1}".utf8), url: "https://x/y?z=1", persist: true, now: t0)
        XCTAssertNotNil(cache.fresh("k", maxAge: 20, now: t0.addingTimeInterval(10)))
        XCTAssertNil(cache.fresh("k", maxAge: 20, now: t0.addingTimeInterval(30)), "too old for reuse")
        XCTAssertNil(cache.fresh("k", maxAge: 0, now: t0), "maxAge 0 never reuses")

        // A new instance (app relaunch) only has the disk copy.
        let relaunched = ResponseCache(directory: dir)
        let e = try XCTUnwrap(relaunched.stale("k"))
        XCTAssertEqual(String(decoding: e.data, as: UTF8.self), "{\"a\":1}")
        XCTAssertEqual(e.url, "https://x/y?z=1")
        XCTAssertEqual(e.date, t0)
        XCTAssertNil(relaunched.stale("other"))
    }

    func testNoStoreKeepsNothingOnDisk() {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        ResponseCache(directory: dir).store("k", data: Data("x".utf8), url: "u", persist: false)
        XCTAssertNil(ResponseCache(directory: dir).stale("k"))
    }

    func testDeepLinksForWidgetAndSiri() {
        let stop = DeepLink.stop(id: "de:09162:2", name: "Marienplatz", lat: 48.137, lon: 11.575)
        XCTAssertEqual(DeepLink(url: stop.url), stop)
        XCTAssertEqual(DeepLink(url: URL(string: "publit://go/home")!), .go(.home))
        XCTAssertEqual(DeepLink.go(.work).url.absoluteString, "publit://go/work")
        XCTAssertNil(DeepLink(url: URL(string: "publit://go/school")!))
        XCTAssertNil(DeepLink(url: URL(string: "publit://stop/x")!), "coordinates required")
    }

    func testSharedRouteLinkCarriesTheWholeRoute() throws {
        let data = try Data(contentsOf: URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("Fixtures/plan.json"))
        let it = try TransitousClient.makeDecoder().decode(PlanResponse.self, from: data).itineraries[0]
        let route = SharedRoute(it, fromName: "Café & Bar = ü/1", toName: "Home?#")
        let web = ShareLinks.web(.route(route))
        XCTAssertTrue(web.absoluteString.hasPrefix("https://elija-7580.github.io/publit-site/open/#route?i="))
        XCTAssertFalse(web.absoluteString.contains("+"), "a + would turn into a space on the web page")
        for url in [DeepLink.route(route).url, web] {
            guard case .route(let back)? = DeepLink(url: url) else { return XCTFail("\(url)") }
            XCTAssertEqual(back.itineraryId, it.id, "the ID survives packing")
            XCTAssertEqual(back.fromName, "Café & Bar = ü/1")
            XCTAssertEqual(back.toName, "Home?#")
            XCTAssertEqual(back.departure, it.legs[0].scheduledStartTime)
            XCTAssertEqual(back.from.lat, it.legs[0].from.lat, accuracy: 0.00001)
            XCTAssertEqual(back.to.lon, it.legs.last!.to.lon, accuracy: 0.00001)
        }
        XCTAssertLessThan(ShareLinks.pack(it.id).count, it.id.count + 2)
        XCTAssertNil(DeepLink(url: URL(string: "publit://route?f=A&t=B")!), "places and time are required")
        XCTAssertNil(DeepLink(url: URL(string: "https://example.org/Publit-/open/#route?fl=1,2&tl=3,4&d=0")!))
        guard case .route(let bare)? = DeepLink(url: URL(string: "publit://route?fl=48.1,11.5&tl=48.2,11.6&d=1800000000")!) else {
            return XCTFail("no ID: still a route to search again")
        }
        XCTAssertNil(bare.itineraryId)
        XCTAssertEqual(ShareLinks.unpack(ShareLinks.pack("not base64 ✓")), "not base64 ✓")
    }

    func testStopShareLinkOpensTheBoard() {
        let stop = DeepLink.stop(id: "de:09162:2", name: "Marienplatz", lat: 48.137, lon: 11.575)
        XCTAssertEqual(DeepLink(url: ShareLinks.web(stop)), stop)
    }

    func testLatLonApiValueRoundTrip() {
        let p = LatLon(lat: 48.13715, lon: 11.5754)
        XCTAssertEqual(LatLon(apiValue: p.apiValue), p)
        XCTAssertNil(LatLon(apiValue: "nonsense"))
    }
    func testOfflineNearbyNeverReturnsAnotherCity() {
        let a = "https://example.org/nearby?center=48.137,11.575"
        XCTAssertTrue(TransitousClient.sameOfflineRequest(savedURL: a,
            requestedURL: URL(string: "https://example.org/nearby?center=48.138,11.575")!, slot: "nearby"))
        XCTAssertFalse(TransitousClient.sameOfflineRequest(savedURL: a,
            requestedURL: URL(string: "https://example.org/nearby?center=41.1,16.9")!, slot: "nearby"))
        XCTAssertFalse(TransitousClient.sameOfflineRequest(savedURL: a,
            requestedURL: URL(string: "https://example.org/nearby?center=48.138,11.575")!, slot: nil))
    }

    func testCancellationAndTLSFailuresDoNotBecomeOfflineData() {
        XCTAssertTrue(TransitousClient.isOffline(URLError(.notConnectedToInternet)))
        XCTAssertFalse(TransitousClient.isOffline(URLError(.cancelled)))
        XCTAssertFalse(TransitousClient.isOffline(URLError(.secureConnectionFailed)))
    }

}

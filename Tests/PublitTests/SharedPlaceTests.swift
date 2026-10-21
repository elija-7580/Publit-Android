import XCTest
@testable import Publit

/// Circle lines and places shared from other map apps (feedback round 3, Venice).
final class SharedPlaceTests: XCTestCase {
    // MARK: Circle lines

    func testLidoCircleLineIsCircular() {
        XCTAssertTrue(CircularLine.isLoop(headsign: "Lido S.M.E. \"A\"", origin: "Lido S.M.E. \"A\""))
        // 5.2 boards at pier B, its headsign names pier A: still the same place.
        XCTAssertTrue(CircularLine.isLoop(headsign: "Lido S.M.E. \"A\"", origin: "Lido S.M.E. \"B\""))
        XCTAssertTrue(CircularLine.isLoop(headsign: "Santa Maria Elisabetta", origin: "Santa Maria Elisabetta"))
    }

    func testOrdinaryDirectionsAreNotCircular() {
        XCTAssertFalse(CircularLine.isLoop(headsign: "P.le Roma \"G\"", origin: "Lido S.M.E. \"D\""))
        XCTAssertFalse(CircularLine.isLoop(headsign: "Hauptbahnhof", origin: "Hauptbahnhof Nord"))
        XCTAssertFalse(CircularLine.isLoop(headsign: nil, origin: "Marienplatz"))
        XCTAssertFalse(CircularLine.isLoop(headsign: "", origin: ""))
        XCTAssertEqual(CircularLine.baseName("Lido S.M.E. \"A\""), CircularLine.baseName("Lido S.M.E. \u{201C}C\u{201D}"))
    }

    // MARK: Map links

    func testGooglePlaceLinkUsesThePin() throws {
        let url = "https://www.google.com/maps/place/Caff%C3%A8+Cannaregio/@45.4440,12.3200,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d45.4436!4d12.3264!16s"
        let link = try XCTUnwrap(MapLink.parse(text: url))
        XCTAssertEqual(link.name, "Caffè Cannaregio")
        XCTAssertEqual(link.lat ?? 0, 45.4436, accuracy: 1e-6)
        XCTAssertEqual(link.lon ?? 0, 12.3264, accuracy: 1e-6)
    }

    func testGoogleQueryAndDirectionsLinks() throws {
        let q = try XCTUnwrap(MapLink.parse(text: "https://maps.google.com/?q=45.4560,12.4600"))
        XCTAssertEqual(q.lat ?? 0, 45.456, accuracy: 1e-6)
        let api = try XCTUnwrap(MapLink.parse(text: "https://www.google.com/maps/dir/?api=1&destination=45.43,12.34"))
        XCTAssertEqual(api.lon ?? 0, 12.34, accuracy: 1e-6)
        let garbled = try XCTUnwrap(MapLink.parse(text: "httwww.google.com/maps/search/48.1351,+11.5820"))
        XCTAssertEqual(garbled.lat ?? 0, 48.1351, accuracy: 0.0001)
        XCTAssertEqual(garbled.lon ?? 0, 11.5820, accuracy: 0.0001)
        let search = try XCTUnwrap(MapLink.parse(text: "https://www.google.com/maps/search/45.4436,+12.3264?entry=tts"))
        XCTAssertEqual(search.lat ?? 0, 45.4436, accuracy: 1e-6)
        let name = try XCTUnwrap(MapLink.parse(text: "https://www.google.com/maps/search/?api=1&query=Punta+Sabbioni"))
        XCTAssertEqual(name.name, "Punta Sabbioni")
        XCTAssertFalse(name.hasCoordinate)
    }

    func testLinksWithoutSlashesOrSchemeAreRecognised() throws {
        let noSlashes = try XCTUnwrap(MapLink.parse(text: "https:www.google.com/maps/search/48.1351,+11.5820"))
        XCTAssertEqual(noSlashes.lat ?? 0, 48.1351, accuracy: 1e-6)
        let bare = try XCTUnwrap(MapLink.parse(text: "www.google.com/maps/search/48.1351,+11.5820"))
        XCTAssertEqual(bare.lon ?? 0, 11.582, accuracy: 1e-6)
        XCTAssertNil(MapLink.parse(text: "Hauptbahnhof"))
    }

    func testGoogleShortLinkWithNameIsFollowedLater() throws {
        let link = try XCTUnwrap(MapLink.parse(text: "Caffè Cannaregio\nhttps://maps.app.goo.gl/AbCdEf123"))
        XCTAssertEqual(link.shortLink?.host, "maps.app.goo.gl")
        XCTAssertEqual(link.name, "Caffè Cannaregio")
        XCTAssertFalse(link.hasCoordinate)
    }

    func testAppleMapsOsmGeoAndPlainCoordinates() throws {
        let apple = try XCTUnwrap(MapLink.parse(text: "https://maps.apple.com/?ll=45.4436,12.3264&q=Caff%C3%A8%20Cannaregio"))
        XCTAssertEqual(apple.name, "Caffè Cannaregio")
        XCTAssertEqual(apple.lat ?? 0, 45.4436, accuracy: 1e-6)
        let apple18 = try XCTUnwrap(MapLink.parse(text: "https://maps.apple.com/place?coordinate=45.4436,12.3264&name=Pier"))
        XCTAssertEqual(apple18.name, "Pier")
        let osm = try XCTUnwrap(MapLink.parse(text: "https://www.openstreetmap.org/?mlat=45.45&mlon=12.46#map=17/45.45/12.46"))
        XCTAssertEqual(osm.lon ?? 0, 12.46, accuracy: 1e-6)
        let geo = try XCTUnwrap(MapLink.parse(text: "geo:45.4436,12.3264?q=45.4436,12.3264(Caff%C3%A8%20Cannaregio)"))
        XCTAssertEqual(geo.name, "Caffè Cannaregio")
        XCTAssertEqual(geo.lat ?? 0, 45.4436, accuracy: 1e-6)
        let plain = try XCTUnwrap(MapLink.parse(text: " 45.4560, 12.4600 "))
        XCTAssertEqual(plain.lon ?? 0, 12.46, accuracy: 1e-6)
        XCTAssertNil(MapLink.parse(text: "Marienplatz"))
        XCTAssertNil(MapLink.parse(text: "https://example.com/45.1,12.1"))
        XCTAssertNil(MapLink.parse(text: "95.0, 12.0"))
    }

    func testCoordinatesInAGoogleMapsPage() throws {
        let page = #"<meta content="https://maps.google.com/maps/api/staticmap?center=45.4436%2C12.3264&amp;zoom=17" property="og:image">"#
        let c = try XCTUnwrap(MapLink.coordinatesInPage(page))
        XCTAssertEqual(c.0, 45.4436, accuracy: 1e-6)
    }

    func testSharedPlaceOpensRoutesThroughADeepLink() throws {
        let link = MapLink(name: "Caffè Cannaregio", lat: 45.4436, lon: 12.3264)
        let url = try XCTUnwrap(link.appURL)
        guard case .place(let name, let lat, let lon)? = DeepLink(url: url) else { return XCTFail(url.absoluteString) }
        XCTAssertEqual(name, "Caffè Cannaregio")
        XCTAssertEqual(lat ?? 0, 45.4436, accuracy: 1e-6)
        XCTAssertEqual(lon ?? 0, 12.3264, accuracy: 1e-6)
        let byName = try XCTUnwrap(MapLink(name: "Punta Sabbioni").appURL)
        guard case .place("Punta Sabbioni", nil, nil)? = DeepLink(url: byName) else { return XCTFail(byName.absoluteString) }
        XCTAssertNil(DeepLink(url: URL(string: "publit://place?lat=95&lon=12")!))
        XCTAssertEqual(DeepLink(url: DeepLink.place(name: "X", lat: 1, lon: 2).url), .place(name: "X", lat: 1, lon: 2))
    }
}

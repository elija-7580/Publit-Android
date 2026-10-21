import XCTest
@testable import Publit

final class MapLayersTests: XCTestCase {
    private func match(_ type: String, category: String? = nil) throws -> GeocodeMatch {
        var json: [String: Any] = ["type": type, "name": "X", "id": "x", "lat": 48.4, "lon": 9.99, "areas": [] as [Any]]
        if let category { json["category"] = category }
        return try JSONDecoder().decode(GeocodeMatch.self, from: JSONSerialization.data(withJSONObject: json))
    }

    func testCategoryIsDecodedAndOptional() throws {
        XCTAssertEqual(try match("PLACE", category: "place_6").category, "place_6")
        XCTAssertNil(try match("STOP").category)
    }

    func testSpanFitsWhatWasFound() throws {
        let city = MapSearch.meters(try match("PLACE", category: "place_6"))
        let capital = MapSearch.meters(try match("PLACE", category: "place_capital_8"))
        let suburb = MapSearch.meters(try match("PLACE", category: "suburb"))
        let village = MapSearch.meters(try match("PLACE", category: "village"))
        let poi = MapSearch.meters(try match("PLACE", category: "restaurant_14"))
        let stop = MapSearch.meters(try match("STOP"))
        let address = MapSearch.meters(try match("ADDRESS"))
        XCTAssertEqual(city, capital)
        XCTAssertGreaterThan(city, suburb)
        XCTAssertGreaterThan(suburb, village)
        XCTAssertGreaterThan(village, stop)
        XCTAssertGreaterThan(stop, poi)
        XCTAssertGreaterThan(poi, address)
        // Even a whole city stays zoomed in far enough for stations, lines and vehicles (0.06° latitude).
        XCTAssertLessThan(city / 111_000, 0.06)
        XCTAssertEqual(MapSearch.meters(try match("PLACE", category: nil)), poi)
    }

    func testQueryNeedsTwoCharacters() {
        XCTAssertNil(MapSearch.query(" U "))
        XCTAssertEqual(MapSearch.query("  Ulm "), "Ulm")
    }

    func testBaseStorageAndLabels() {
        XCTAssertEqual(MapBase(storageValue: "satellite"), .satellite)
        XCTAssertEqual(MapBase(storageValue: "nonsense"), .standard)
        XCTAssertEqual(MapBase(storageValue: ""), .standard)
        XCTAssertTrue(MapBase.imagery.isSatellite)
        XCTAssertFalse(MapBase.muted.isSatellite)
        var look = MapLook(base: .imagery, realistic3D: true, pointsOfInterest: true, traffic: true)
        XCTAssertFalse(look.showsTraffic)
        XCTAssertFalse(look.showsPointsOfInterest)
        look.base = .satellite
        XCTAssertTrue(look.showsTraffic)
        XCTAssertTrue(look.showsPointsOfInterest)
        XCTAssertNotEqual(look, MapLook())
    }
}

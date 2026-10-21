import XCTest
@testable import Publit

final class Build55Tests: XCTestCase {
    func testCategoryWordsMatchTheMapsStoredWords() {
        XCTAssertEqual(PlaceCategory.supermarket.word, "foodmarket")
        XCTAssertEqual(PlaceCategory.of("evcharger"), .evCharger)
        XCTAssertEqual(PlaceCategory.of("Restaurant"), .restaurant)
        XCTAssertNil(PlaceCategory.of("beach"))
        XCTAssertNil(PlaceCategory.of(nil))
        XCTAssertEqual(Set(PlaceCategory.allCases.map(\.word)).count, PlaceCategory.allCases.count)
    }

    func testTypedWordFindsCategory() {
        XCTAssertEqual(PlaceCategory.matching("restaurant"), .restaurant)
        XCTAssertNil(PlaceCategory.matching("re"))
    }

    func testScopesFilterResults() {
        let stop = GeocodeMatch(type: "STOP", name: "Karlsplatz", id: "s", lat: 48.2, lon: 16.37, street: nil, houseNumber: nil,
                                country: nil, areas: [], category: nil)
        let address = GeocodeMatch(type: "ADDRESS", name: "Ring 1", id: "a", lat: 48.2, lon: 16.37, street: "Ring", houseNumber: "1",
                                   country: nil, areas: [], category: nil)
        let cafe = GeocodeMatch.picked(name: "Café Sperl", at: LatLon(lat: 48.2, lon: 16.36), category: "cafe")
        let all = [stop, address, cafe]
        XCTAssertEqual(MapSearchScope.all.filter(all).count, 3)
        XCTAssertEqual(MapSearchScope.stops.filter(all).map(\.id), ["s"])
        XCTAssertEqual(MapSearchScope.addresses.filter(all).map(\.id), ["a"])
        XCTAssertEqual(MapSearchScope.places.filter(all).map(\.name), ["Café Sperl"])
        XCTAssertFalse(MapSearchScope.stops.usesApple)
        XCTAssertFalse(MapSearchScope.places.usesTransit)
    }

    func testTransitLookIsTheDefault() {
        XCTAssertEqual(MapBase(storageValue: MapBase.defaultValue), .muted)
    }
}

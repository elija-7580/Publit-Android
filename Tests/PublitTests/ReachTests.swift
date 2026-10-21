import XCTest
@testable import Publit

/// Far stops and long walks (Punta Sabbioni, build 34): never an empty board or a silent "no connections".
final class ReachTests: XCTestCase {
    func testNearbyWidensUpToFiveKilometres() {
        XCTAssertEqual(Reach.nearbyRadii, [500, 1500, 5000])
        XCTAssertEqual(Reach.nearbyRadii, Reach.nearbyRadii.sorted())
        XCTAssertGreaterThanOrEqual(Reach.farDistance, Double(Reach.nearbyRadii[1]))
    }

    func testWalkStepsGrowAndEndAtTwoHours() {
        XCTAssertEqual(Reach.walkSteps.first, TransitousClient.walkLimits.prePost)
        XCTAssertEqual(Reach.walkSteps, Reach.walkSteps.sorted())
        XCTAssertEqual(Reach.nextWalkStep(after: 1800), 2700)
        XCTAssertEqual(Reach.nextWalkStep(after: 2700), 7200)
        XCTAssertNil(Reach.nextWalkStep(after: 7200))
        XCTAssertEqual(TransitousClient.widerWalkLimit, 2700)
    }

    func testWalkMinutesFromStraightLine() {
        XCTAssertEqual(Reach.walkMinutes(meters: 0), 1)
        XCTAssertEqual(Reach.walkMinutes(meters: .nan), 1)
        // 1 km straight line ≈ 1.3 km of streets ≈ 17 min.
        XCTAssertEqual(Reach.walkMinutes(meters: 1000), 17)
        XCTAssertEqual(Reach.walkMinutes(meters: 2950), 48)
    }

    func testFarNoteNamesDistanceAndWalk() {
        let note = Reach.farNote(meters: 2950)
        XCTAssertTrue(note.contains("km"), note)
        XCTAssertTrue(note.contains("48"), note)
    }

    /// Real answer from Cavallino-Treporti to San Zaccaria (40 min walk to the ferry at Punta Sabbioni).
    func testLongWalkIsMeasuredAndNamed() throws {
        let res = try TransitousClient.makeDecoder().decode(PlanResponse.self, from: Fixtures.data("plan_long_walk"))
        XCTAssertFalse(res.itineraries.isEmpty)
        XCTAssertEqual(Reach.longestAccessWalk(res.itineraries), 2400)
        let note = try XCTUnwrap(Reach.longWalkNote(res.itineraries))
        XCTAssertTrue(note.contains("40"), note)
        XCTAssertTrue(res.itineraries.allSatisfy { $0.legs.first?.mode == "WALK" && $0.legs.contains(where: \.isTransit) })
    }

    func testNoNoteForUsualWalksOrWalkOnly() throws {
        let res = try TransitousClient.makeDecoder().decode(PlanResponse.self, from: Fixtures.data("plan_muc"))
        XCTAssertLessThanOrEqual(Reach.longestAccessWalk(res.itineraries), Reach.longWalkSeconds)
        XCTAssertNil(Reach.longWalkNote(res.itineraries))
        XCTAssertNil(Reach.longWalkNote([]))
        // Walking the whole way is not "a long walk to the stop".
        XCTAssertEqual(Reach.longestAccessWalk(res.direct), 0)
    }

    func testOwnLimitNoteNamesTheLimit() {
        XCTAssertTrue(Reach.ownLimitNote(minutes: 10).contains("10"))
    }

    func testPickedPlaceKeepsItsIdWhenNamed() {
        let point = LatLon(lat: 45.447340, lon: 12.424333)
        let pin = GeocodeMatch.picked(name: "Dropped pin", at: point)
        let named = GeocodeMatch.picked(name: "Lungomare Dante Alighieri", at: point, street: "Lungomare Dante Alighieri",
                                        houseNumber: "1", locality: "Cavallino-Treporti", country: "IT", category: "beach")
        XCTAssertEqual(pin.id, named.id)
        XCTAssertEqual(pin.id, "pick|45.44734,12.42433")
        XCTAssertEqual(named.type, GeocodeMatch.pickedType)
        XCTAssertFalse(named.isStop)
        XCTAssertEqual(named.subtitle, "Cavallino-Treporti, IT")
        // Opens the place sheet (not a town overview) and routes like a search hit.
        XCTAssertLessThan(MapSearch.meters(named), 2500)
        let saved = SavedPlace(match: named)
        XCTAssertNil(saved.stopId)
        XCTAssertEqual(saved.lat, point.lat)
    }

    func testMergedSearchKeepsStopsFirstAndDropsDuplicates() {
        let stop = GeocodeMatch(type: "STOP", name: "Punta Sabbioni", id: "it-5108", lat: 45.44734, lon: 12.42433,
                                street: nil, houseNumber: nil, country: "IT", areas: [], category: nil)
        let same = GeocodeMatch.picked(name: "Punta Sabbioni", at: LatLon(lat: 45.4475, lon: 12.4245))
        let museum = GeocodeMatch.picked(name: "Museo della Laguna", at: LatLon(lat: 45.46, lon: 12.43))
        let merged = GeocodeMatch.merged([stop], [same, museum])
        XCTAssertEqual(merged.map(\.id), [stop.id, museum.id])
        XCTAssertEqual(GeocodeMatch.merged([], [museum]).count, 1)
        XCTAssertEqual(GeocodeMatch.merged(Array(repeating: museum, count: 20), [], limit: 15).count, 15)
    }
}

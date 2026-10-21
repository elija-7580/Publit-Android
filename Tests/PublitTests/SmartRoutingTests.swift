import XCTest
@testable import Publit

final class SmartRoutingTests: XCTestCase {
    private func leg(_ mode: String, _ from: String, _ to: String, start: Int, end: Int, realTime: Bool = false) throws -> Leg {
        let t0 = 1_791_360_000.0
        func iso(_ m: Int) -> String { DateParsing.format(Date(timeIntervalSince1970: t0 + Double(m) * 60)) }
        let json = """
        {"mode":"\(mode)","from":{"name":"\(from)","lat":48,"lon":11},"to":{"name":"\(to)","lat":48,"lon":11},
         "duration":\((end - start) * 60),"startTime":"\(iso(start))","endTime":"\(iso(end))",
         "scheduledStartTime":"\(iso(start))","scheduledEndTime":"\(iso(end))",
         "realTime":\(realTime),"tripId":"\(mode)-\(from)-\(start)","legGeometry":{"points":"","precision":6,"length":0}}
        """
        return try TransitousClient.makeDecoder().decode(Leg.self, from: Data(json.utf8))
    }

    private func itinerary(_ legs: [Leg]) -> Itinerary {
        Itinerary(id: UUID().uuidString, duration: Int(legs.last!.endTime.timeIntervalSince(legs.first!.startTime)),
                  startTime: legs.first!.startTime, endTime: legs.last!.endTime,
                  transfers: max(0, legs.filter(\.isTransit).count - 1), legs: legs)
    }

    private let w = SmartRouting.Weights()

    func testChanceGrowsWithSpareTimeAndDependsOnTheVehicle() throws {
        let subway = try leg("SUBWAY", "A", "B", start: 0, end: 10)
        let train = try leg("REGIONAL_RAIL", "A", "B", start: 0, end: 10)
        let tight = SmartRouting.chance(spareMinutes: 0, walkSeconds: 120, incoming: subway)
        let roomy = SmartRouting.chance(spareMinutes: 5, walkSeconds: 120, incoming: subway)
        XCTAssertLessThan(tight, roomy)
        XCTAssertGreaterThan(roomy, 0.95)
        // Trains run late more often than the underground: the same 2 minutes are less sure.
        XCTAssertLessThan(SmartRouting.chance(spareMinutes: 2, walkSeconds: 60, incoming: train),
                          SmartRouting.chance(spareMinutes: 2, walkSeconds: 60, incoming: subway))
        XCTAssertLessThan(SmartRouting.chance(spareMinutes: -3, walkSeconds: 60, incoming: train), 0.1)
    }

    func testChangesAndReliability() throws {
        let it = itinerary([try leg("WALK", "Home", "A", start: 0, end: 5),
                            try leg("SUBWAY", "A", "B", start: 5, end: 15),
                            try leg("WALK", "B", "B", start: 15, end: 17),
                            try leg("BUS", "B", "C", start: 19, end: 30)])
        let changes = SmartRouting.changes(it)
        XCTAssertEqual(changes.count, 1)
        XCTAssertEqual(changes[0].stopName, "B")
        XCTAssertEqual(changes[0].spareMinutes, 2, accuracy: 0.01)
        XCTAssertEqual(changes[0].outgoingIndex, 3)
        XCTAssertEqual(SmartRouting.reliability(it), changes[0].chance, accuracy: 0.0001)
        XCTAssertLessThan(changes[0].percent, 100)
    }

    func testRecommendsTheDirectRideOverASlightlyFasterOneWithAChange() throws {
        let direct = itinerary([try leg("BUS", "A", "C", start: 0, end: 34)])
        let change = itinerary([try leg("SUBWAY", "A", "B", start: 0, end: 12),
                                try leg("SUBWAY", "B", "C", start: 14, end: 30)])
        XCTAssertEqual(SmartRouting.recommended([change, direct], weights: w)?.routeKey, direct.routeKey)
        // Much faster with the change: then the change is worth it.
        let quick = itinerary([try leg("SUBWAY", "A", "B", start: 0, end: 6),
                               try leg("SUBWAY", "B", "C", start: 10, end: 14)])
        XCTAssertEqual(SmartRouting.recommended([quick, direct], weights: w)?.routeKey, quick.routeKey)
        XCTAssertEqual(SmartRouting.reason(for: direct, in: [change, direct]), L10n.t("One change fewer"))
        let badges = SmartRouting.badges([change, direct], weights: w)
        XCTAssertEqual(badges[direct.routeKey], [.recommended, .fewestChanges])
        XCTAssertEqual(badges[change.routeKey], [.fastest])
    }

    func testPrunedDropsRoutesBeatenOnEverything() throws {
        let early = itinerary([try leg("SUBWAY", "A", "C", start: 0, end: 42)])
        let later = itinerary([try leg("SUBWAY", "A", "C", start: 12, end: 42)])
        let walk = itinerary([try leg("WALK", "A", "C", start: 0, end: 50)])
        let kept = SmartRouting.pruned([early, later, walk])
        XCTAssertEqual(kept.map(\.routeKey), [later.routeKey, walk.routeKey])
    }

    func testBackupIsTheNextVehicleThatStillWorks() throws {
        var ride = try leg("BUS", "B", "C", start: 20, end: 30)
        let earlier = [try leg("WALK", "B", "B", start: 14, end: 15), try leg("BUS", "B", "C", start: 15, end: 25), try leg("WALK", "C", "C", start: 25, end: 26)]
        let later = [try leg("WALK", "B", "B", start: 25, end: 27), try leg("TRAM", "B", "C", start: 27, end: 38), try leg("WALK", "C", "C", start: 38, end: 40)]
        ride.alternatives = [earlier, later]
        let backup = try XCTUnwrap(SmartRouting.backup(for: ride))
        XCTAssertEqual(backup.leg.mode, "TRAM")
        XCTAssertEqual(backup.arrival, later[2].endTime)
        ride.alternatives = [earlier]
        XCTAssertNil(SmartRouting.backup(for: ride))
    }

    func testPickingFewerChangesRaisesTheirWeight() throws {
        let change = itinerary([try leg("SUBWAY", "A", "B", start: 0, end: 6),
                                try leg("SUBWAY", "B", "C", start: 10, end: 14)])
        let direct = itinerary([try leg("BUS", "A", "C", start: 0, end: 34)])
        let learned = SmartRouting.learned(chosen: direct, offered: [change, direct], arriveBy: false, weights: w)
        XCTAssertEqual(learned.transferMinutes, w.transferMinutes + 2)
        let same = SmartRouting.learned(chosen: change, offered: [change, direct], arriveBy: false, weights: w)
        XCTAssertEqual(same, w)
    }

    func testRequestAddsItsParametersOnlyWhenOn() {
        XCTAssertTrue(SmartRouting.Request().queryItems.isEmpty)
        let on = SmartRouting.Request(enabled: true, pedestrianSpeed: 1.25, extraTransferMinutes: 5, flatWalks: true)
        let d = Dictionary(uniqueKeysWithValues: on.queryItems.map { ($0.name, $0.value ?? "") })
        XCTAssertEqual(d["numLegAlternatives"], "2")
        XCTAssertEqual(d["pedestrianSpeed"], "1.25")
        XCTAssertEqual(d["additionalTransferTime"], "5")
        XCTAssertEqual(d["elevationCosts"], "LOW")
        XCTAssertNotEqual(on.key, SmartRouting.Request().key)
    }

    func testOldSavedTripsDecodeWithoutAlternatives() throws {
        let plan = try TransitousClient.makeDecoder().decode(PlanResponse.self, from: Fixtures.data("plan"))
        XCTAssertTrue(plan.itineraries.allSatisfy { $0.legs.allSatisfy { ($0.alternatives ?? []).isEmpty } })
    }
}

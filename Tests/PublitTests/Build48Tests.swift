import XCTest
@testable import Publit

final class Build48Tests: XCTestCase {
    private let t0 = 1_791_360_000.0   // 2026-10-07 08:00 UTC

    private func date(_ m: Double) -> Date { Date(timeIntervalSince1970: t0 + m * 60) }

    private func leg(_ mode: String, start: Double, end: Double, name: String? = nil, cancelled: Bool? = nil,
                     to: (Double, Double) = (48.21, 16.38)) throws -> Leg {
        func iso(_ m: Double) -> String { DateParsing.format(date(m)) }
        let line = name.map { ",\"routeShortName\":\"\($0)\"" } ?? ""
        let cancel = cancelled.map { ",\"cancelled\":\($0)" } ?? ""
        let json = """
        {"mode":"\(mode)","from":{"name":"A","lat":48.2,"lon":16.37},"to":{"name":"B","lat":\(to.0),"lon":\(to.1)},
         "duration":\(Int((end - start) * 60)),"startTime":"\(iso(start))","endTime":"\(iso(end))",
         "scheduledStartTime":"\(iso(start))","scheduledEndTime":"\(iso(end))",
         "realTime":false\(line)\(cancel),"legGeometry":{"points":"","precision":6,"length":0}}
        """
        return try TransitousClient.makeDecoder().decode(Leg.self, from: Data(json.utf8))
    }

    private func itinerary(_ legs: [Leg], id: String = "x") -> Itinerary {
        Itinerary(id: id, duration: Int(legs.last!.endTime.timeIntervalSince(legs.first!.startTime)),
                  startTime: legs.first!.startTime, endTime: legs.last!.endTime, transfers: 0, legs: legs)
    }

    func testChoicesSkipMissedAndCancelledEarliestFirst() throws {
        let missed = itinerary([try leg("BUS", start: 0, end: 30, name: "1")], id: "a")
        let late = itinerary([try leg("BUS", start: 10, end: 50, name: "2")], id: "b")
        let early = itinerary([try leg("BUS", start: 12, end: 40, name: "3")], id: "c")
        let gone = itinerary([try leg("BUS", start: 15, end: 35, name: "4", cancelled: true)], id: "d")
        let picks = TripAlternative.choices([missed, late, early, gone], at: date(5))
        XCTAssertEqual(picks.map(\.id), ["c", "b"])
        XCTAssertEqual(TripAlternative.pick([missed, late, early, gone], at: date(5))?.id, "c")
        XCTAssertEqual(TripAlternative.choices([missed, late, early], at: date(5), limit: 1).count, 1)
    }

    func testComparisonWithTrip() throws {
        let trip = itinerary([try leg("BUS", start: 0, end: 30, name: "1")])
        let later = itinerary([try leg("BUS", start: 10, end: 38, name: "2")])
        let earlier = itinerary([try leg("BUS", start: 5, end: 25, name: "3")])
        XCTAssertEqual(TripAlternative.minutesLater(later, than: trip), 8)
        XCTAssertEqual(TripAlternative.minutesLater(earlier, than: trip), -5)
        XCTAssertEqual(TripAlternative.minutesLater(trip, than: trip), 0)
        XCTAssertFalse(TripAlternative.comparison(later, than: trip).isEmpty)
        XCTAssertNotEqual(TripAlternative.comparison(later, than: trip), TripAlternative.comparison(earlier, than: trip))
    }

    func testStartBlockedGivesReason() throws {
        let it = itinerary([try leg("BUS", start: 10, end: 30, name: "1")])
        XCTAssertNil(TripStatus.startBlocked(it, now: date(0)))
        XCTAssertNotNil(TripStatus.startBlocked(it, now: date(60)))
        let cancelled = itinerary([try leg("BUS", start: 10, end: 30, name: "1", cancelled: true)])
        XCTAssertNotNil(TripStatus.startBlocked(cancelled, now: date(0)))
    }

    func testWalkWithoutShapeIsStraightLine() throws {
        let walk = try leg("WALK", start: 0, end: 5)
        XCTAssertEqual(walk.mapPath.count, 2)
        let here = try leg("WALK", start: 0, end: 1, to: (48.2, 16.37))
        XCTAssertTrue(here.mapPath.isEmpty)
    }

    func testOverviewPathThinsLongLines() {
        let line = (0..<5000).map { LatLon(lat: 48 + Double($0) * 0.001, lon: 16 + sin(Double($0) / 50) * 0.002) }
        let thin = OverviewPaths.path(line, key: "test-long")
        XCTAssertLessThan(thin.count, line.count / 2)
        XCTAssertEqual(thin.first, line.first)
        XCTAssertEqual(thin.last, line.last)
        let short = Array(line.prefix(100))
        XCTAssertEqual(OverviewPaths.path(short, key: "test-short").count, 100)
    }
}

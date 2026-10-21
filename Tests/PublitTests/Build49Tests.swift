import XCTest
@testable import Publit

final class Build49Tests: XCTestCase {
    private let t0 = 1_791_360_000.0

    private func date(_ m: Double) -> Date { Date(timeIntervalSince1970: t0 + m * 60) }

    private func leg(_ mode: String, start: Double, end: Double, from: (Double, Double) = (48.2, 16.37),
                     to: (Double, Double) = (48.21, 16.38)) throws -> Leg {
        func iso(_ m: Double) -> String { DateParsing.format(date(m)) }
        let line = mode == "WALK" || mode == "BIKE" || mode == "CAR" ? "" : ",\"routeShortName\":\"1\""
        let json = """
        {"mode":"\(mode)","from":{"name":"A","lat":\(from.0),"lon":\(from.1)},"to":{"name":"B","lat":\(to.0),"lon":\(to.1)},
         "duration":\(Int((end - start) * 60)),"startTime":"\(iso(start))","endTime":"\(iso(end))",
         "scheduledStartTime":"\(iso(start))","scheduledEndTime":"\(iso(end))",
         "realTime":false\(line),"legGeometry":{"points":"","precision":6,"length":0}}
        """
        return try TransitousClient.makeDecoder().decode(Leg.self, from: Data(json.utf8))
    }

    private func itinerary(_ legs: [Leg], id: String = "x") -> Itinerary {
        Itinerary(id: id, duration: Int(legs.last!.endTime.timeIntervalSince(legs.first!.startTime)),
                  startTime: legs.first!.startTime, endTime: legs.last!.endTime, transfers: 0, legs: legs)
    }

    func testOnlyRealisticStreetComparisons() throws {
        func c(_ mode: String, _ minutes: Double) throws -> StreetComparison {
            StreetComparison(mode: mode, itinerary: itinerary([try leg(mode, start: 0, end: minutes)]))
        }
        XCTAssertTrue(try c("WALK", 30).isRealistic(bestTransit: 600))
        XCTAssertFalse(try c("WALK", 180).isRealistic(bestTransit: 3600))
        XCTAssertTrue(try c("WALK", 70).isRealistic(bestTransit: 3600))      // about as fast as transit
        XCTAssertFalse(try c("WALK", 70).isRealistic(bestTransit: 1200))     // far slower than transit
        XCTAssertTrue(try c("BIKE", 80).isRealistic(bestTransit: 1200))
        XCTAssertFalse(try c("BIKE", 300).isRealistic(bestTransit: 18000))
        XCTAssertTrue(try c("CAR", 300).isRealistic(bestTransit: 600))
    }

    func testRegionLabelAndTicketText() throws {
        let venice = TransitRegion.at(LatLon(lat: 45.438, lon: 12.335))
        XCTAssertEqual(venice?.network, "ACTV")
        XCTAssertTrue(venice?.title.contains("ACTV") == true)
        XCTAssertFalse(venice?.ticketText.isEmpty ?? true)
        XCTAssertNotNil(venice?.link)
        XCTAssertNil(TransitRegion.at(LatLon(lat: 0, lon: 0)))
        // Rides only, each network once, in order.
        let it = itinerary([try leg("WALK", start: 0, end: 5, from: (45.44, 12.33), to: (45.44, 12.33)),
                            try leg("BUS", start: 5, end: 30, from: (45.44, 12.33), to: (48.2, 16.37)),
                            try leg("TRAM", start: 35, end: 40, from: (48.2, 16.37), to: (48.21, 16.38))])
        XCTAssertEqual(TransitRegion.along(it).map(\.network), ["ACTV", "Wiener Linien"])
        for r in TransitRegion.all { XCTAssertNotNil(r.link, r.network); XCTAssertLessThan(r.box.minLat, r.box.maxLat) }
    }

    func testOldHistoryWithoutSourceStillLoads() throws {
        let old = #"{"id":"1","from":{"currentLocation":{}},"to":{"currentLocation":{}},"mode":"now","date":0,"searchedAt":0}"#
        if let entry = try? JSONDecoder().decode(RecentSearch.self, from: Data(old.utf8)) {
            XCTAssertNil(entry.source)
        }
        XCTAssertEqual(RecentSearch.Source.map.symbol, "mappin.and.ellipse")
        let encoded = try JSONEncoder().encode(RecentSearch.Source.favorite)
        XCTAssertEqual(try JSONDecoder().decode(RecentSearch.Source.self, from: encoded), .favorite)
    }

    @MainActor
    func testHistoryKeepsWhereTheDestinationCameFrom() async {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { JSONPersistence.flush(); try? FileManager.default.removeItem(at: dir) }
        let history = SearchHistoryStore(directory: dir)
        let p = SavedPlace(id: "p", name: "Rialto", subtitle: nil, lat: 45.438, lon: 12.336, stopId: nil)
        history.record(from: .currentLocation, to: .place(p), mode: .now, date: .now, source: .map)
        XCTAssertEqual(history.entries.first?.source, .map)
        let plan = PlanModel()
        history.entries.first?.apply(to: plan)
        XCTAssertEqual(plan.source, .map)
    }
}

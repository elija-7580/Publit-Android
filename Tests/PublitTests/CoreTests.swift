import Foundation
import XCTest
@testable import Publit

final class LineColorTests: XCTestCase {
    func testKeepsLegibleOperatorTextColour() {
        let p = LineColors.resolve(routeColor: "E3000F", routeTextColor: "ffffff", fallback: "000000")
        XCTAssertEqual(p, .init(background: "E3000F", text: "FFFFFF"))
    }

    func testReplacesIllegibleDefaultWhiteOnLightLine() {
        // Munich S1 (light blue) comes without a text colour; white on it is about 2.3:1.
        let p = LineColors.resolve(routeColor: "16c0e9", routeTextColor: nil, fallback: "000000")
        XCTAssertEqual(p.text, "000000")
        let ratio = LineColors.contrast(LineColors.RGB(hex: p.text)!, LineColors.RGB(hex: p.background)!)
        XCTAssertGreaterThanOrEqual(ratio, 4.5)
    }

    func testReplacesOperatorPairBelowThreshold() {
        let p = LineColors.resolve(routeColor: "FFD800", routeTextColor: "FFFFFF", fallback: "000000")
        XCTAssertEqual(p.text, "000000")
    }

    func testFallsBackToModeColourWhenFeedHasNone() {
        let p = LineColors.resolve(routeColor: nil, routeTextColor: "000000", fallback: ModeStyle.defaultHex("BUS"))
        XCTAssertEqual(p.background, "2973CC")
        XCTAssertEqual(p.text, "FFFFFF")
    }

    func testRejectsMalformedHex() {
        XCTAssertNil(LineColors.RGB(hex: "12345"))
        XCTAssertNil(LineColors.RGB(hex: "zzzzzz"))
        XCTAssertNotNil(LineColors.RGB(hex: "#0065ae"))
    }

    func testWhiteBlackContrastIs21() {
        XCTAssertEqual(LineColors.contrast(LineColors.white, LineColors.black), 21, accuracy: 0.01)
    }
}

final class GroupingTests: XCTestCase {
    func testMergesPlatformsOfOneStationWithDifferentParentIds() throws {
        // Live sample: Marienplatz arrives as three IDs (no parent, "_G" parent, bus stop).
        let res = try TransitousClient.makeDecoder()
            .decode(StopTimesResponse.self, from: Fixtures.data("stoptimes_marienplatz"))
        let rawKeys = Set(res.stopTimes.filter { $0.place.name == "Marienplatz" }.map(\.place.stationId))
        XCTAssertGreaterThan(rawKeys.count, 1, "fixture should reproduce the duplicate-station bug")

        let groups = StopGrouping.group(res.stopTimes, around: LatLon(lat: 48.1372, lon: 11.5755))
        let stations = groups.filter { !$0.isPole }
        XCTAssertEqual(stations.filter { $0.name == "Marienplatz" }.count, 1)
        XCTAssertEqual(Set(stations.map(\.name)).count, stations.count, "every station name appears once")
        XCTAssertEqual(groups.first?.name, "Marienplatz", "nearest station first")
        XCTAssertEqual(groups.reduce(0) { $0 + $1.departures.count }, res.stopTimes.count, "no departure lost")
        XCTAssertEqual(Set(groups.map(\.id)).count, groups.count, "unique IDs")
    }

    func testBusStopsOnEitherSideOfTheRoadStaySeparate() throws {
        let res = try TransitousClient.makeDecoder()
            .decode(StopTimesResponse.self, from: Fixtures.data("stoptimes_marienplatz"))
        let groups = StopGrouping.group(res.stopTimes, around: nil)
        // The bus stop at Marienplatz is its own board, at its own position, with its directions.
        let bus = try XCTUnwrap(groups.first { $0.name == "Marienplatz" && $0.isPole })
        XCTAssertTrue(bus.departures.allSatisfy { $0.mode == "BUS" })
        XCTAssertFalse(bus.directions.isEmpty)
        XCTAssertEqual(bus.id, bus.departures[0].place.stopId)
        XCTAssertFalse(groups.first { $0.name == "Marienplatz" && !$0.isPole }!.departures.contains { $0.mode == "BUS" })
        // Without separating, everything with the name is one board (the previous behaviour).
        XCTAssertEqual(StopGrouping.group(res.stopTimes, around: nil, separatePoles: false).filter { $0.name == "Marienplatz" }.count, 1)
    }

    func testDeparturesInsideGroupAreChronological() throws {
        let res = try TransitousClient.makeDecoder()
            .decode(StopTimesResponse.self, from: Fixtures.data("stoptimes_marienplatz"))
        for g in StopGrouping.group(res.stopTimes, around: nil) {
            XCTAssertEqual(g.departures.map(\.departureTime), g.departures.map(\.departureTime).sorted())
        }
    }

    func testNameNormalisationIgnoresCaseAndAccents() {
        XCTAssertEqual(StopGrouping.normalizedName(" Bari Centrale "), StopGrouping.normalizedName("bari centrale"))
        XCTAssertEqual(StopGrouping.normalizedName("Kadıköy"), StopGrouping.normalizedName("Kadiköy"))
    }

    func testHaversineDistance() {
        let marienplatz = LatLon(lat: 48.137, lon: 11.5754)
        let hbf = LatLon(lat: 48.1402, lon: 11.5586)
        XCTAssertEqual(marienplatz.distance(to: hbf), 1300, accuracy: 60)
        XCTAssertEqual(hbf.distance(to: hbf), 0, accuracy: 0.001)
    }
}

final class TransferRiskTests: XCTestCase {
    private func leg(_ mode: String, _ from: String, _ to: String, start: Int, end: Int,
                     scheduledEnd: Int? = nil, realTime: Bool = true) throws -> Leg {
        let t0 = 1_791_360_000.0
        func iso(_ m: Int) -> String { DateParsing.format(Date(timeIntervalSince1970: t0 + Double(m) * 60)) }
        let json = """
        {"mode":"\(mode)","from":{"name":"\(from)","lat":48,"lon":11},"to":{"name":"\(to)","lat":48,"lon":11},
         "duration":\((end - start) * 60),"startTime":"\(iso(start))","endTime":"\(iso(end))",
         "scheduledStartTime":"\(iso(start))","scheduledEndTime":"\(iso(scheduledEnd ?? end))",
         "realTime":\(realTime),"legGeometry":{"points":"","precision":6,"length":0}}
        """
        return try TransitousClient.makeDecoder().decode(Leg.self, from: Data(json.utf8))
    }

    private func itinerary(_ legs: [Leg]) -> Itinerary {
        Itinerary(id: "t", duration: 0, startTime: legs.first!.startTime, endTime: legs.last!.endTime,
                  transfers: legs.filter(\.isTransit).count - 1, legs: legs)
    }

    func testNoWarningForComfortableChange() throws {
        let it = itinerary([try leg("SUBWAY", "A", "B", start: 0, end: 8),
                            try leg("WALK", "B", "B", start: 8, end: 10),
                            try leg("BUS", "B", "C", start: 14, end: 30)])
        XCTAssertTrue(TransferRisk.warnings(for: it).isEmpty)
    }

    func testTightWhenIncomingIsLateAndBufferSmall() throws {
        let it = itinerary([try leg("SUBWAY", "A", "Nordfriedhof", start: 0, end: 10, scheduledEnd: 8),
                            try leg("WALK", "Nordfriedhof", "Nordfriedhof", start: 10, end: 12),
                            try leg("BUS", "Nordfriedhof", "C", start: 13, end: 30)])
        let w = TransferRisk.warnings(for: it)
        XCTAssertEqual(w.count, 1)
        XCTAssertEqual(w.first?.level, .tight)
        XCTAssertEqual(w.first?.bufferMinutes, 1)
        XCTAssertEqual(w.first?.message, "Tight change at Nordfriedhof (1 min)")
    }

    func testMissedWhenWalkLongerThanGap() throws {
        let it = itinerary([try leg("SUBWAY", "A", "B", start: 0, end: 10, scheduledEnd: 6),
                            try leg("WALK", "B", "B", start: 10, end: 14),
                            try leg("BUS", "B", "C", start: 12, end: 30)])
        XCTAssertEqual(TransferRisk.warnings(for: it).first?.level, .missed)
    }

    func testDirectTripHasNoWarnings() throws {
        let it = itinerary([try leg("WALK", "Home", "A", start: 0, end: 3),
                            try leg("SUBURBAN", "A", "Airport", start: 5, end: 45)])
        XCTAssertTrue(TransferRisk.warnings(for: it).isEmpty)
    }

    func testLiveFixtureHasNoFalseAlarms() throws {
        let plan = try TransitousClient.makeDecoder().decode(PlanResponse.self, from: Fixtures.data("plan_muc"))
        XCTAssertEqual(plan.itineraries.count, 3)
        for it in plan.itineraries { XCTAssertTrue(TransferRisk.warnings(for: it).isEmpty) }
    }
}

final class FormattingTests: XCTestCase {
    func testChangesAndStopsPluralisation() {
        XCTAssertEqual(Fmt.changes(0), "Direct")
        XCTAssertEqual(Fmt.changes(1), "1 change")
        XCTAssertEqual(Fmt.changes(3), "3 changes")
        XCTAssertEqual(Fmt.stops(1), "1 stop")
    }

    func testDuration() {
        XCTAssertEqual(Fmt.duration(seconds: 20), "1 min")
        XCTAssertEqual(Fmt.duration(seconds: 2520), "42 min")
        XCTAssertEqual(Fmt.duration(seconds: 3600), "1 h")
        XCTAssertEqual(Fmt.duration(seconds: 5400), "1 h 30 min")
    }

    func testTimeUsesStopTimeZone() throws {
        let d = try XCTUnwrap(DateParsing.parse("2026-10-07T06:15:00Z"))
        XCTAssertTrue(Fmt.time(d, tz: "Europe/Istanbul").contains("9:15"), Fmt.time(d, tz: "Europe/Istanbul"))
        XCTAssertTrue(Fmt.time(d, tz: "Europe/Vienna").contains("8:15"), Fmt.time(d, tz: "Europe/Vienna"))
    }

    func testForeignZoneNote() throws {
        let d = try XCTUnwrap(DateParsing.parse("2026-10-07T06:15:00Z"))
        let vienna = try XCTUnwrap(TimeZone(identifier: "Europe/Vienna"))
        XCTAssertEqual(Fmt.foreignZoneNote(tz: "Europe/Istanbul", at: d, device: vienna), "Local time Istanbul (GMT+3)")
        XCTAssertNil(Fmt.foreignZoneNote(tz: "Europe/Rome", at: d, device: vienna))
        XCTAssertNil(Fmt.foreignZoneNote(tz: nil, at: d, device: vienna))
    }
}

@MainActor
final class FavoritesTests: XCTestCase {
    private func store() -> FavoritesStore {
        let d = UserDefaults(suiteName: "publit-tests-\(UUID().uuidString)")!
        return FavoritesStore(defaults: d)
    }

    /// Search gives the S-Bahn platform ID, Nearby the station ID: still one favourite.
    func testSameStationDifferentStopIdIsOneFavourite() async {
        let s = store()
        let fromSearch = SavedPlace(id: "de-DELFI_de:09162:2:40:81", name: "Marienplatz", subtitle: "München", lat: 48.13725, lon: 11.57585, stopId: "de-DELFI_de:09162:2:40:81")
        let fromNearby = SavedPlace(id: "de-DELFI_de:09162:2", name: "Marienplatz", subtitle: nil, lat: 48.13716, lon: 11.57549, stopId: "de-DELFI_de:09162:2")
        s.toggle(fromSearch)
        XCTAssertTrue(s.contains(fromNearby))
        s.toggle(fromNearby)
        XCTAssertTrue(s.stops.isEmpty)
        let farAway = SavedPlace(id: "x", name: "Marienplatz", subtitle: nil, lat: 48.3, lon: 11.7, stopId: "x")
        s.toggle(fromSearch)
        XCTAssertFalse(s.contains(farAway))
    }

    private let a = SavedPlace(id: "a", name: "Flat", subtitle: nil, lat: 48.2, lon: 16.3, stopId: nil)
    private let b = SavedPlace(id: "b", name: "Office", subtitle: nil, lat: 48.4, lon: 15.6, stopId: nil)

    func testHomeAndWorkAreExclusiveSlots() async {
        let s = store()
        s.set(a, as: .home)
        s.set(b, as: .home)
        XCTAssertEqual(s.place(for: .home)?.id, "b")
        XCTAssertEqual(s.items.filter { $0.role == .home }.count, 1)
        XCTAssertTrue(s.places.contains { $0.id == "a" }, "previous Home stays as a normal saved place")
    }

    func testPinnedPlacesAreNotListedTwice() async {
        let s = store()
        s.set(a, as: .work)
        XCTAssertTrue(s.places.isEmpty)
        XCTAssertEqual(s.pinned.map(\.id), ["a"])
    }

    func testPersistsAcrossInstancesAndReadsOldFormat() async throws {
        let suite = "publit-tests-\(UUID().uuidString)"
        let old = #"[{"id":"x","name":"Old","lat":1,"lon":2}]"#
        UserDefaults(suiteName: suite)!.set(Data(old.utf8), forKey: "publit.favorites.v1")
        let s = FavoritesStore(defaults: UserDefaults(suiteName: suite)!)
        XCTAssertEqual(s.items.first?.name, "Old")
        s.set(a, as: .home)
        let again = FavoritesStore(defaults: UserDefaults(suiteName: suite)!)
        XCTAssertEqual(again.place(for: .home)?.id, "a")
    }
}

@MainActor
final class PlanModelTests: XCTestCase {
    func testMissingLocationExplainsWhatToDo() async {
        let m = PlanModel()
        m.to = .place(SavedPlace(id: "t", name: "T", subtitle: nil, lat: 1, lon: 1, stopId: nil))
        await m.search(current: nil, locationDenied: true)
        XCTAssertEqual(m.errorMessage?.hasPrefix("Location is off"), true)
        await m.search(current: nil, locationDenied: false)
        XCTAssertEqual(m.errorMessage, "Waiting for your location…")
    }

    func testQueryKeyChangesWithInputs() async {
        let m = PlanModel()
        let k0 = m.queryKey
        m.timeMode = .arriveBy
        XCTAssertNotEqual(m.queryKey, k0)
    }

    func testDemoPreset() async {
        let app = AppState()
        app.applyDemo("munich")
        XCTAssertEqual(app.plan.to?.title, "Flughafen München")
        app.applyDemo(nil)
        XCTAssertEqual(app.plan.from.title, "Marienplatz")
    }
}

final class TripProgressTests: XCTestCase {
    private func stops() throws -> [Place] {
        let it = try TransitousClient.makeDecoder().decode(Itinerary.self, from: Fixtures.data("trip_s3"))
        let leg = try XCTUnwrap(it.legs.first)
        XCTAssertEqual(leg.allStops.count, (leg.intermediateStops?.count ?? 0) + 2)
        return leg.allStops
    }

    func testBeforeDuringAndAfter() throws {
        let s = try stops()
        let start = try XCTUnwrap(s.first?.departure)
        let end = try XCTUnwrap(s.last?.arrival)
        XCTAssertEqual(TripProgress.at(start.addingTimeInterval(-60), stops: s), .notStarted)
        XCTAssertEqual(TripProgress.at(end.addingTimeInterval(60), stops: s), .finished)
        let mid = TripProgress.at(start.addingTimeInterval(end.timeIntervalSince(start) / 2), stops: s)
        switch mid {
        case .between(let i, let f):
            XCTAssertTrue((0..<(s.count - 1)).contains(i))
            XCTAssertTrue((0...1).contains(f))
        case .atStop(let i):
            XCTAssertTrue((1..<(s.count - 1)).contains(i))
        default: XCTFail("expected the vehicle on the way, got \(mid)")
        }
    }

    func testPositionLiesBetweenNeighbouringStops() throws {
        let s = try stops()
        let d0 = try XCTUnwrap(s[0].departure), a1 = try XCTUnwrap(s[1].arrival ?? s[1].departure)
        let p = TripProgress.at(d0.addingTimeInterval(a1.timeIntervalSince(d0) / 2), stops: s)
        let pos = try XCTUnwrap(TripProgress.position(p, stops: s))
        XCTAssertEqual(pos.lat, (s[0].lat + s[1].lat) / 2, accuracy: abs(s[0].lat - s[1].lat) / 2 + 1e-6)
        XCTAssertEqual(TripProgress.nextStopIndex(p, count: s.count), 1)
    }

    func testEmptyTrip() {
        XCTAssertEqual(TripProgress.at(Date(), stops: []), .notStarted)
        XCTAssertNil(TripProgress.position(.finished, stops: []))
    }
}

@MainActor
final class SavedLineTests: XCTestCase {
    func testSaveLineAtStopAndMatchDepartures() async throws {
        let res = try TransitousClient.makeDecoder()
            .decode(StopTimesResponse.self, from: Fixtures.data("stoptimes_marienplatz"))
        let u3 = try XCTUnwrap(res.stopTimes.first { $0.lineName == "U3" })
        let line = SavedLine(stopTime: u3, stopId: "de-DELFI_de:09162:2", stopName: "Marienplatz",
                             at: LatLon(lat: 48.137, lon: 11.575))
        let matching = res.stopTimes.filter(line.matches)
        XCTAssertFalse(matching.isEmpty)
        XCTAssertTrue(matching.allSatisfy { $0.lineName == "U3" })
        XCTAssertEqual(line.lineColors.background, "EC6725")

        let store = FavoritesStore(defaults: UserDefaults(suiteName: "publit-tests-\(UUID().uuidString)")!)
        store.toggle(line)
        XCTAssertTrue(store.containsLine(line.id))
        store.toggle(line)
        XCTAssertTrue(store.lines.isEmpty)
    }
}

final class PaletteTests: XCTestCase {
    /// Live, delay, cancelled and link colours must stay readable (WCAG AA, 4.5:1) in both modes.
    func testStatusColoursMeetAA() {
        for tone in Palette.all {
            for bg in Palette.lightBackgrounds {
                XCTAssertGreaterThanOrEqual(LineColors.contrast(LineColors.RGB(hex: tone.light)!, LineColors.RGB(hex: bg)!), 4.5, "\(tone.light) on \(bg)")
            }
            for bg in Palette.darkBackgrounds {
                XCTAssertGreaterThanOrEqual(LineColors.contrast(LineColors.RGB(hex: tone.dark)!, LineColors.RGB(hex: bg)!), 4.5, "\(tone.dark) on \(bg)")
            }
        }
    }
}

final class PolylineSimplifyTests: XCTestCase {
    func testDropsPointsOnAStraightLineAndKeepsEnds() {
        let line = (0...100).map { LatLon(lat: 48.0 + Double($0) * 0.0001, lon: 11.0) }
        let s = Polyline.simplify(line)
        XCTAssertEqual(s.count, 2)
        XCTAssertEqual(s.first, line.first)
        XCTAssertEqual(s.last, line.last)
    }

    func testKeepsCorners() {
        let a = (0...50).map { LatLon(lat: 48.0 + Double($0) * 0.0001, lon: 11.0) }
        let b = (1...50).map { LatLon(lat: 48.005, lon: 11.0 + Double($0) * 0.0001) }
        let s = Polyline.simplify(a + b)
        XCTAssertEqual(s.count, 3)
        XCTAssertEqual(s[1], LatLon(lat: 48.005, lon: 11.0))
    }

    func testEveryDroppedPointStaysCloseToTheSimplifiedLine() {
        // A wiggly street: the simplified line must stay within a few metres of every original point.
        let line = (0..<400).map { i in LatLon(lat: 48.0 + Double(i) * 0.00005, lon: 11.0 + sin(Double(i) / 9) * 0.0004) }
        let s = Polyline.simplify(line, toleranceMeters: 4)
        XCTAssertLessThan(s.count, line.count / 2)
        for p in line {
            let d = zip(s, s.dropFirst()).map { segmentDistance(p, $0, $1) }.min() ?? .infinity
            XCTAssertLessThanOrEqual(d, 4.5)
        }
    }

    private func segmentDistance(_ p: LatLon, _ a: LatLon, _ b: LatLon) -> Double {
        let mx = 111_320 * cos(48.0 * .pi / 180), my = 110_540.0
        let ax = a.lon * mx, ay = a.lat * my, bx = b.lon * mx, by = b.lat * my, px = p.lon * mx, py = p.lat * my
        let dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy
        let t = len2 == 0 ? 0 : max(0, min(1, ((px - ax) * dx + (py - ay) * dy) / len2))
        let ex = px - ax - t * dx, ey = py - ay - t * dy
        return (ex * ex + ey * ey).squareRoot()
    }
}

final class LinkSafetyTests: XCTestCase {
    func testFeedLinksOpenOnlyAsWebPages() {
        XCTAssertEqual(TicketLinks.webURL("https://www.mvv-muenchen.de/x")?.host, "www.mvv-muenchen.de")
        XCTAssertEqual(TicketLinks.webURL("www.oebb.at")?.scheme, "https")
        XCTAssertNil(TicketLinks.webURL("tel://0800123"))
        XCTAssertNil(TicketLinks.webURL("sms://123"))
        XCTAssertNil(TicketLinks.webURL("publit://ticket"))
        XCTAssertNil(TicketLinks.webURL("someapp://open"))
    }

    func testStopLinkRejectsImpossibleCoordinates() {
        XCTAssertNotNil(DeepLink(url: URL(string: "publit://stop/x?lat=48.1&lon=11.5")!))
        XCTAssertNil(DeepLink(url: URL(string: "publit://stop/x?lat=480&lon=11.5")!))
        XCTAssertNil(DeepLink(url: URL(string: "publit://stop/x?lat=nan&lon=11.5")!))
        XCTAssertNil(DeepLink(url: URL(string: "publit://stop/x?lat=48.1&lon=inf")!))
    }
}

final class ResponseCacheBoundsTests: XCTestCase {
    func testMemoryStaysBelowByteLimitAndKeepsNewest() {
        let cache = ResponseCache(directory: nil)
        let blob = Data(repeating: 7, count: 2 * 1024 * 1024)
        let start = Date(timeIntervalSince1970: 1_000_000)
        for i in 0..<30 {
            cache.store("k\(i)", data: blob, url: "u\(i)", persist: false, now: start.addingTimeInterval(Double(i)))
        }
        let kept = (0..<30).filter { cache.stale("k\($0)") != nil }
        XCTAssertLessThanOrEqual(kept.count * blob.count, ResponseCache.maxMemoryBytes)
        XCTAssertNotNil(cache.stale("k29"), "the newest response stays")
    }
}

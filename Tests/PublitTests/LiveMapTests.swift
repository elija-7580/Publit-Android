import XCTest
@testable import Publit

final class LiveMapTests: XCTestCase {
    private let decoder = TransitousClient.makeDecoder()

    private func segments() throws -> [TripSegment] {
        try decoder.decode([TripSegment].self, from: Fixtures.data("map_trips"))
    }

    private func stopTimes(_ edit: ((inout [[String: Any]]) -> Void)? = nil) throws -> [StopTime] {
        var data = try Fixtures.data("stoptimes_marienplatz")
        if let edit {
            var root = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
            var list = try XCTUnwrap(root["stopTimes"] as? [[String: Any]])
            edit(&list)
            root["stopTimes"] = list
            data = try JSONSerialization.data(withJSONObject: root)
        }
        return try decoder.decode(StopTimesResponse.self, from: data).stopTimes
    }

    private func date(_ s: String) -> Date { ISO8601DateFormatter().date(from: s)! }

    // MARK: Decoding

    func testSegmentsDecode() throws {
        let s = try segments()
        XCTAssertEqual(s.count, 16)
        XCTAssertEqual(s[0].mode, "SUBWAY")
        XCTAssertEqual(s[0].from.name, "Universität")
        XCTAssertFalse(s[0].polyline.isEmpty)
        XCTAssertFalse(s[0].trips.isEmpty)
    }

    func testRentalsDecode() throws {
        let r = try decoder.decode(RentalsResponse.self, from: Fixtures.data("rentals"))
        XCTAssertEqual(r.stations.count, 3)
        XCTAssertEqual(r.vehicles.count, 4)
        XCTAssertFalse(r.groupName(r.stations[0].providerGroupId).isEmpty)
        XCTAssertEqual(RentalKind(r.vehicles[0].formFactor), .scooterStanding)
    }

    func testRentalLegDecodes() throws {
        let plan = try decoder.decode(PlanResponse.self, from: Fixtures.data("plan_rental"))
        let legs = try XCTUnwrap(plan.itineraries.first).legs
        let rentals = legs.compactMap(\.rental)
        XCTAssertEqual(rentals.count, 2)
        XCTAssertEqual(rentals[0].kind, .scooterStanding)
        XCTAssertNotNil(rentals[0].bookingURL)
        XCTAssertEqual(rentals[1].kind, .bicycle)
        XCTAssertEqual(rentals[1].fromStationName, "Orleanspl.")
        XCTAssertNil(legs[0].rental)
    }

    // MARK: Live map

    func testBuildSkipsFlightsAndGroupsTrips() throws {
        let snap = LiveMap.build(try segments(), filter: .all, precision: 5, tolerance: 2)
        XCTAssertFalse(snap.shapes.contains { $0.mode == "AIRPLANE" })
        XCTAssertFalse(snap.tracks.contains { $0.mode == "AIRPLANE" })
        XCTAssertEqual(snap.tracks.count, 5)
        XCTAssertEqual(snap.shapes.count, 15)
        // Buses are drawn first, rail last.
        XCTAssertEqual(snap.shapes.first?.mode, "BUS")
        XCTAssertEqual(snap.shapes.last?.mode, "SUBWAY")
        for t in snap.tracks { XCTAssertEqual(t.pieces.map(\.departure), t.pieces.map(\.departure).sorted()) }
    }

    func testBuildHonoursModeFilter() throws {
        let snap = LiveMap.build(try segments(), filter: ModeFilter(included: [.tram]), precision: 5, tolerance: 2)
        XCTAssertFalse(snap.tracks.isEmpty)
        XCTAssertTrue(snap.tracks.allSatisfy { $0.mode == "TRAM" })
    }

    func testVehiclePositionOverTime() throws {
        let snap = LiveMap.build(try segments(), filter: .all, precision: 5, tolerance: 2)
        let track = try XCTUnwrap(snap.tracks.first { $0.pieces.first?.departure == date("2026-10-13T09:46:00Z") && $0.mode == "TRAM" })
        let mid = try XCTUnwrap(track.vehicle(at: date("2026-10-13T09:47:30Z")))
        XCTAssertEqual(mid.nextStop, "Karlsplatz (Stachus)")
        XCTAssertNotNil(mid.heading)
        XCTAssertNil(track.vehicle(at: date("2026-10-13T09:30:00Z")))
        XCTAssertNil(track.vehicle(at: date("2026-10-13T10:30:00Z")))
        // Waiting at a stop between two pieces.
        let subway = try XCTUnwrap(snap.tracks.first { $0.pieces.count == 3 && $0.pieces.first?.departure == date("2026-10-13T09:49:00Z") })
        let waiting = try XCTUnwrap(subway.vehicle(at: date("2026-10-13T09:51:30Z")))
        XCTAssertEqual(waiting.nextStop, "Sendlinger Tor")
        XCTAssertNil(waiting.heading)
    }

    func testVehiclesInsideBounds() throws {
        let snap = LiveMap.build(try segments(), filter: .all, precision: 5, tolerance: 2)
        let t = date("2026-10-13T09:47:30Z")
        let all = LiveMap.vehicles(snap.tracks, at: t, min: LatLon(lat: 47, lon: 10), max: LatLon(lat: 49, lon: 13))
        XCTAssertFalse(all.isEmpty)
        let none = LiveMap.vehicles(snap.tracks, at: t, min: LatLon(lat: 50, lon: 10), max: LatLon(lat: 51, lon: 13))
        XCTAssertTrue(none.isEmpty)
    }

    func testRulerAndHelpers() {
        let r = PolylineRuler([LatLon(lat: 48, lon: 11), LatLon(lat: 48, lon: 11.01), LatLon(lat: 48.01, lon: 11.01)])
        XCTAssertGreaterThan(r.length, 1500)
        let half = r.point(at: r.length / 4)
        XCTAssertNotNil(half)
        XCTAssertEqual(half!.0.lat, 48, accuracy: 0.0001)
        XCTAssertNotNil(r.point(at: r.length + 100))
        let d = r.distance(ofNearest: LatLon(lat: 48.01, lon: 11.01))
        XCTAssertEqual(d, r.length, accuracy: 1)
        XCTAssertEqual(LiveMap.colorHex("#ee1c25", mode: "BUS"), "EE1C25")
        XCTAssertEqual(LiveMap.colorHex("zz", mode: "BUS"), ModeStyle.defaultHex("BUS"))
        XCTAssertEqual(LiveMap.tolerance(spanMeters: 100), 2)
        XCTAssertEqual(LiveMap.tolerance(spanMeters: 1_000_000), 40)
    }

    // MARK: Lines

    func testLineCatalogAndSearch() throws {
        let sts = try stopTimes()
        let lines = LineCatalog.build(sts, around: LatLon(lat: 48.137, lon: 11.575))
        XCTAssertFalse(lines.isEmpty)
        XCTAssertEqual(Set(lines.map(\.key)).count, lines.count)
        let u6 = LineCatalog.search("u 6", in: lines)
        XCTAssertEqual(u6.first?.lineName, "U6")
        XCTAssertEqual(LineCatalog.search("Linie U6", in: lines).first?.lineName, "U6")
        XCTAssertFalse(LineCatalog.search("garching", in: lines).isEmpty)
        XCTAssertTrue(LineCatalog.search("xyzzy", in: lines).isEmpty)
        XCTAssertEqual(LineCatalog.search("", in: lines).count, lines.count)
        XCTAssertEqual(LineCatalog.fold("Ü 6-a"), "u6a")
    }

    func testLineDirectionsAndTimetable() throws {
        let sts = try stopTimes()
        let lines = LineCatalog.build(sts, around: LatLon(lat: 48.137, lon: 11.575))
        let u3 = try XCTUnwrap(lines.first { $0.lineName == "U3" })
        let dirs = LineDirection.from(sts, matching: u3.key)
        XCTAssertEqual(Set(dirs.map(\.id)), ["0", "1"])
        XCTAssertTrue(dirs.contains { $0.headsign.contains("Moosach") })
        let times = sts.filter { u3.matches($0) }.map { $0.scheduledDepartureTime ?? $0.departureTime }
        let start = times.min()!.addingTimeInterval(-3600), end = times.max()!.addingTimeInterval(3600)
        let tz = TimeZone(identifier: "Europe/Berlin")!
        let all = LineTimetable.build(sts, key: u3.key, direction: nil, from: start, to: end, timeZone: tz)
        let one = LineTimetable.build(sts, key: u3.key, direction: dirs[0].id, from: start, to: end, timeZone: tz)
        XCTAssertFalse(one.isEmpty)
        XCTAssertLessThanOrEqual(one.count, all.count)
        XCTAssertEqual(all.count, Set(times.map { Int($0.timeIntervalSince1970 / 60) }).count)
        for row in all.rows { XCTAssertEqual(row.minutes, row.minutes.sorted()) }
        XCTAssertTrue(LineTimetable.build(sts, key: u3.key, direction: nil, from: end, to: end.addingTimeInterval(60), timeZone: tz).isEmpty)
    }

    func testEstimatedVehiclesOnPattern() throws {
        let t0 = date("2026-10-13T10:00:00Z")
        func place(_ name: String, _ lon: Double, arr: Double?, dep: Double?) -> Place {
            Place(name: name, stopId: name, parentId: nil, lat: 48, lon: lon, tz: "Europe/Berlin",
                  arrival: arr.map { t0.addingTimeInterval($0) }, departure: dep.map { t0.addingTimeInterval($0) },
                  scheduledArrival: nil, scheduledDeparture: nil, track: nil, scheduledTrack: nil, cancelled: nil, alerts: nil)
        }
        let stops = [place("A", 11.00, arr: nil, dep: 0), place("B", 11.01, arr: 120, dep: 150), place("C", 11.02, arr: 300, dep: nil)]
        let pattern = try XCTUnwrap(LineVehicles.Pattern(stops: stops, shape: []))
        XCTAssertEqual(pattern.duration, 300)
        XCTAssertEqual(pattern.index(of: "B", near: LatLon(lat: 0, lon: 0)), 1)
        XCTAssertEqual(pattern.index(of: nil, near: LatLon(lat: 48, lon: 11.019)), 2)
        let between = try XCTUnwrap(pattern.position(after: 210))
        XCTAssertEqual(between.1, 2)
        XCTAssertGreaterThan(between.0.lon, 11.01)
        XCTAssertLessThan(between.0.lon, 11.02)
        XCTAssertNil(pattern.position(after: 1000))

        let sts = try stopTimes()
        let dep = sts[0]
        let vehicles = LineVehicles.estimate(pattern, at: 1, departures: [dep, dep], now: dep.departureTime.addingTimeInterval(30))
        XCTAssertEqual(vehicles.count, 1)
        XCTAssertEqual(vehicles.first?.nextStop, "C")
        XCTAssertTrue(LineVehicles.estimate(pattern, at: 1, departures: [dep], now: dep.departureTime.addingTimeInterval(3600)).isEmpty)
        let w = LineVehicles.window(pattern, at: 1, now: t0)
        XCTAssertEqual(w.seconds, 420)
    }

    // MARK: Route sorting and options

    func testRouteSort() throws {
        let plan = try decoder.decode(PlanResponse.self, from: Fixtures.data("plan_rental"))
        let list = plan.itineraries
        XCTAssertEqual(RouteSort.departure.apply(list).map(\.startTime), list.map(\.startTime))
        let fastest = RouteSort.fastest.apply(list)
        XCTAssertEqual(fastest.map(\.duration), fastest.map(\.duration).sorted())
        let fewest = RouteSort.fewestChanges.apply(list)
        XCTAssertEqual(fewest.map(\.transfers), fewest.map(\.transfers).sorted())
        let walking = RouteSort.leastWalking.apply(list)
        XCTAssertEqual(walking.map(RouteSort.walkSeconds), walking.map(RouteSort.walkSeconds).sorted())
        XCTAssertEqual(Set(RouteSort.allCases.map(\.title)).count, RouteSort.allCases.count)
    }

    func testOldRouteOptionsStillDecode() throws {
        let old = #"{"maxWalkMinutes":15,"wheelchair":true,"compareStreetModes":false}"#
        let o = try JSONDecoder().decode(RouteOptions.self, from: Data(old.utf8))
        XCTAssertEqual(o.maxWalkMinutes, 15)
        XCTAssertTrue(o.wheelchair)
        XCTAssertFalse(o.sharing)
        XCTAssertEqual(o.sort, .recommended)
        var n = RouteOptions()
        n.sharing = true
        n.sort = .fastest
        let back = try JSONDecoder().decode(RouteOptions.self, from: JSONEncoder().encode(n))
        XCTAssertTrue(back.sharing)
        XCTAssertEqual(back.sort, .fastest)
        XCTAssertFalse(back.isDefault)
        XCTAssertNotEqual(back.key, RouteOptions().key)
        let unknown = #"{"order":"cheapest"}"#
        XCTAssertEqual(try JSONDecoder().decode(RouteOptions.self, from: Data(unknown.utf8)).sort, .recommended)
        // The old key (departure by default) is left behind: smart routing's order takes over.
        let older = #"{"sort":"departure"}"#
        XCTAssertEqual(try JSONDecoder().decode(RouteOptions.self, from: Data(older.utf8)).sort, .recommended)
    }

    // MARK: Disruptions and sharing

    func testDisruptionsAnnouncedOnce() throws {
        let base = try stopTimes()
        let firstLine = SavedLine(stopTime: base[0], stopId: "de:09162:2", stopName: "Marienplatz", at: base[0].place.coordinate)
        let key = SavedLine.key(base[0])
        let sts = try stopTimes { list in
            for i in list.indices where i < 6 { list[i]["cancelled"] = true }
        }
        let now = sts[0].departureTime.addingTimeInterval(-120)
        let found = LineDisruption.detect(firstLine, in: sts, now: now)
        let cancelledMine = sts.filter { SavedLine.key($0) == key && $0.isCancelled }
        if cancelledMine.isEmpty {
            XCTAssertTrue(found.isEmpty)
        } else {
            XCTAssertEqual(found.count, 1)
            XCTAssertTrue(found[0].fingerprint.hasPrefix("cancel|"))
        }
        XCTAssertTrue(LineDisruption.detect(firstLine, in: base, now: now).allSatisfy { $0.fingerprint.hasPrefix("alert|") })

        var ledger = DisruptionLedger()
        XCTAssertEqual(ledger.takeNew(found, now: now).count, found.count)
        XCTAssertTrue(ledger.takeNew(found, now: now.addingTimeInterval(600)).isEmpty)
        XCTAssertEqual(ledger.takeNew(found, now: now.addingTimeInterval(3 * 24 * 3600)).count, found.count)
        XCTAssertEqual(LineDisruption.stableHash("abc"), LineDisruption.stableHash("abc"))
        XCTAssertNotEqual(LineDisruption.stableHash("abc"), LineDisruption.stableHash("abd"))
    }

    func testArrivalShareMessages() {
        let now = date("2026-10-13T10:00:00Z")
        let later = now.addingTimeInterval(900)
        let msg = ArrivalShare.message(to: "Odeonsplatz", arrival: later, tz: "Europe/Berlin", delayMinutes: 0, now: now)
        XCTAssertTrue(msg.contains("Odeonsplatz"))
        XCTAssertTrue(msg.contains(Fmt.time(later, tz: "Europe/Berlin")))
        let late = ArrivalShare.message(to: "Odeonsplatz", arrival: later, tz: "Europe/Berlin", delayMinutes: 5, now: now)
        XCTAssertTrue(late.contains("5"))
        XCTAssertGreaterThan(late.count, msg.count)
        let arrived = ArrivalShare.message(to: "Odeonsplatz", arrival: now.addingTimeInterval(-60), tz: nil, delayMinutes: 0, now: now)
        XCTAssertNotEqual(arrived, msg)
        XCTAssertTrue(ArrivalShare.shifted(sharedArrival: now, current: now.addingTimeInterval(240)))
        XCTAssertFalse(ArrivalShare.shifted(sharedArrival: now, current: now.addingTimeInterval(60)))
    }
}

import Foundation
import XCTest
@testable import Publit

final class StabilityRegressionTests: XCTestCase {
    func testMalformedPolylineCannotOverflowOrCreateInvalidCoordinates() {
        for input in [String(repeating: "~", count: 100), "\u{0}", "?", "_", String(repeating: "z", count: 500)] {
            XCTAssertTrue(Polyline.decode(input, precision: 5).isEmpty, "\(input.prefix(10))")
        }
        XCTAssertTrue(Polyline.decode("??", precision: -1).isEmpty)
        XCTAssertTrue(Polyline.decode("??", precision: 300).isEmpty)
        XCTAssertTrue(Polyline.decode("??", precision: 5).allSatisfy(\.isValidCoordinate))
        XCTAssertNil(LatLon(apiValue: "nan,10"))
        XCTAssertNil(LatLon(apiValue: "91,10"))
        XCTAssertNil(LatLon(apiValue: "10,181"))
        XCTAssertNil(PolylineRuler([LatLon(lat: 1, lon: 1)]).point(at: .nan))
        XCTAssertTrue(PolylineRuler([LatLon(lat: .nan, lon: 1)]).points.isEmpty)
    }

    func testNearbyCacheReuseDoesNotCrossFiltersOrParameters() {
        func url(_ center: String, radius: String = "500", mode: String = "TRAM", n: String = "40") -> URL {
            var parts = URLComponents(string: "https://api.transitous.org/api/v6/stoptimes")!
            parts.queryItems = [URLQueryItem(name: "center", value: center), URLQueryItem(name: "radius", value: radius),
                                URLQueryItem(name: "mode", value: mode), URLQueryItem(name: "n", value: n)]
            return parts.url!
        }
        let saved = url("48.137,11.575").absoluteString
        XCTAssertTrue(TransitousClient.sameNearbyRequest(savedURL: saved, requestedURL: url("48.1371,11.5751")))
        XCTAssertFalse(TransitousClient.sameNearbyRequest(savedURL: saved, requestedURL: url("48.14,11.58")))
        XCTAssertFalse(TransitousClient.sameNearbyRequest(savedURL: saved, requestedURL: url("48.137,11.575", mode: "BUS")))
        XCTAssertFalse(TransitousClient.sameNearbyRequest(savedURL: saved, requestedURL: url("48.137,11.575", radius: "1500")))
        XCTAssertFalse(TransitousClient.sameNearbyRequest(savedURL: saved, requestedURL: url("48.137,11.575", n: "80")))
    }

    func testMapCacheReusesContainedBoundsButNotMissingDetailOrExpiredData() throws {
        let cache = MapStopCache()
        let now = Date()
        let stops = try TransitousClient.makeDecoder().decode([MapStop].self, from: Fixtures.data("mapstops_marienplatz"))
        let lo = LatLon(lat: 48, lon: 11), hi = LatLon(lat: 49, lon: 12)
        cache.store(stops, min: lo, max: hi, zoom: 16, now: now)
        XCTAssertNotNil(cache.covering(min: LatLon(lat: 48.1, lon: 11.1), max: LatLon(lat: 48.9, lon: 11.9), zoom: 15, now: now))
        XCTAssertNil(cache.covering(min: lo, max: hi, zoom: 17, now: now))
        XCTAssertNil(cache.covering(min: lo, max: LatLon(lat: 50, lon: 12), zoom: 16, now: now))
        XCTAssertNil(cache.covering(min: lo, max: hi, zoom: 16, now: now.addingTimeInterval(901)))
    }

    func testMapSharingSnapshotIsBoundedAndAvailable() throws {
        let rentals = try TransitousClient.makeDecoder().decode(RentalsResponse.self, from: Fixtures.data("rentals"))
        let small = rentals.mapSnapshot(min: LatLon(lat: 48, lon: 11), max: LatLon(lat: 49, lon: 12), limit: 2)
        XCTAssertLessThanOrEqual(small.vehicles.count, 2)
        XCTAssertLessThanOrEqual(small.stations.count, 2)
        XCTAssertTrue(small.vehicles.allSatisfy { $0.isAvailable && $0.coordinate.isValidCoordinate })
        let empty = rentals.mapSnapshot(min: LatLon(lat: 0, lon: 0), max: LatLon(lat: 1, lon: 1))
        XCTAssertTrue(empty.stations.isEmpty && empty.vehicles.isEmpty)
    }

    @MainActor func testTicketMetadataFailureIsSurfacedWithoutAGhostTicket() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = PassStore(directory: dir)
        // Make the metadata destination unwritable without depending on Unix user permissions.
        try FileManager.default.createDirectory(at: dir.appendingPathComponent("passes.json"), withIntermediateDirectories: false)
        do {
            _ = try await store.add(TicketPass(title: "Ticket"), document: Data("original".utf8), kind: .pdf)
            XCTFail("must not report success after a failed metadata commit")
        } catch {}
        XCTAssertTrue(store.passes.isEmpty)
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(at: store.documentDirectory, includingPropertiesForKeys: nil).isEmpty)
    }

    func testQueuedMetadataWriteReportsItsResult() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let file = JSONFile<[String]>(name: "test.json", directory: dir)
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            file.saveInBackground(["saved"]) { continuation.resume(with: $0) }
        }
        XCTAssertEqual(file.load(), ["saved"])
    }

    // MARK: First mile (Bike & Ride, Park & Ride)

    func testFirstMileQueryKeepsWalkingAndAddsChosenMode() throws {
        func items(_ o: RouteOptions) -> [String: String] {
            var d: [String: String] = [:]
            for q in TransitousClient.planQuery(from: LatLon(lat: 48.2, lon: 16.37), to: LatLon(lat: 48.3, lon: 14.28),
                                                time: nil, arriveBy: false, options: o) { d[q.name] = q.value }
            return d
        }
        var o = RouteOptions()
        XCTAssertNil(items(o)["preTransitModes"], "default walking needs no parameter")
        XCTAssertNil(items(o)["postTransitModes"])
        o.firstMile = .bike
        XCTAssertEqual(items(o)["preTransitModes"], "WALK,BIKE")
        XCTAssertNil(items(o)["postTransitModes"], "an own bike is not waiting at the destination station")
        o.firstMile = .car
        o.sharing = true
        XCTAssertEqual(items(o)["preTransitModes"], "WALK,CAR_PARKING,RENTAL")
        XCTAssertEqual(items(o)["postTransitModes"], "WALK,RENTAL")
        XCTAssertTrue(o.isSlowSearch)
        XCTAssertFalse(RouteOptions().isSlowSearch)
    }

    func testFirstMileIsSavedAndChangesTheSearch() throws {
        var o = RouteOptions()
        o.firstMile = .car
        let back = try JSONDecoder().decode(RouteOptions.self, from: JSONEncoder().encode(o))
        XCTAssertEqual(back.firstMile, .car)
        XCTAssertFalse(back.isDefault)
        XCTAssertEqual(back.activeCount, 1)
        XCTAssertNotEqual(back.key, RouteOptions().key)
        let old = #"{"maxWalkMinutes":15,"firstMile":"teleport"}"#
        XCTAssertEqual(try JSONDecoder().decode(RouteOptions.self, from: Data(old.utf8)).firstMile, .walk)
    }
}

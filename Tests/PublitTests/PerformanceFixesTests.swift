import XCTest
@testable import Publit

final class FastDateParsingTests: XCTestCase {
    private let reference: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    func testMatchesTheSystemParser() {
        let samples = ["2026-10-12T14:05:00Z", "2024-02-29T23:59:59Z", "2000-01-01T00:00:00Z", "1999-12-31T12:30:45Z",
                       "2026-03-29T01:00:00Z", "2100-03-01T00:00:00Z"]
        for s in samples {
            XCTAssertEqual(DateParsing.fastUTC(s), DateParsing.parse(s), s)
            XCTAssertEqual(DateParsing.fastUTC(s), ISO8601DateFormatter().date(from: s), s)
        }
        let fractional = "2026-10-12T14:05:00.250Z"
        XCTAssertEqual(DateParsing.fastUTC(fractional)!.timeIntervalSince1970,
                       reference.date(from: fractional)!.timeIntervalSince1970, accuracy: 0.0005)
    }

    func testOtherFormsFallBack() {
        XCTAssertNil(DateParsing.fastUTC("2026-10-12T14:05:00+02:00"))
        XCTAssertNil(DateParsing.fastUTC("2026-13-12T14:05:00Z"))
        XCTAssertNil(DateParsing.fastUTC("2026-10-12 14:05:00Z"))
        XCTAssertNil(DateParsing.fastUTC("2026-1a-12T14:05:00Z"))
        XCTAssertNil(DateParsing.fastUTC("nonsense"))
        XCTAssertEqual(DateParsing.parse("2026-10-12T16:05:00+02:00"), DateParsing.parse("2026-10-12T14:05:00Z"))
    }

    func testFixturesDecodeTheSameWay() throws {
        // Every time in a real response goes through the fast path and gives the formatter's result.
        let text = String(decoding: try Fixtures.data("plan_roma"), as: UTF8.self)
        let regex = try NSRegularExpression(pattern: "\"(\\d{4}-\\d\\d-\\d\\dT[^\"]+Z)\"")
        let matches = regex.matches(in: text, range: NSRange(text.startIndex..., in: text))
        XCTAssertFalse(matches.isEmpty)
        for m in matches {
            let s = String(text[Range(m.range(at: 1), in: text)!])
            let expected = ISO8601DateFormatter().date(from: s) ?? reference.date(from: s)
            XCTAssertNotNil(DateParsing.fastUTC(s), s)
            XCTAssertEqual(DateParsing.fastUTC(s)!.timeIntervalSince1970, expected!.timeIntervalSince1970, accuracy: 0.001, s)
        }
    }
}

final class RequestPrivacyTests: XCTestCase {
    func testCoordinatesAreRoundedForRequests() {
        let p = LatLon(lat: 48.137154321, lon: 11.575498765)
        XCTAssertEqual(p.apiValue, "48.13715,11.5755")
        XCTAssertEqual(p.coarseAPIValue, "48.14,11.58")
        XCTAssertEqual(LatLon(lat: -0.000001, lon: 2).apiValue, "0,2")
        XCTAssertEqual(LatLon(lat: -33.86882, lon: 151.20929).apiValue, "-33.86882,151.20929")
    }

    func testSearchBiasIsTheSameAroundOnePlace() {
        // Two fixes 30 m apart give the same search URL, so the cached answer is reused.
        XCTAssertEqual(LatLon(lat: 48.13702, lon: 11.57511).coarseAPIValue, LatLon(lat: 48.13731, lon: 11.57532).coarseAPIValue)
    }
}

final class ReminderFollowTests: XCTestCase {
    private func shifted(_ it: Itinerary, by seconds: TimeInterval) -> Itinerary {
        Itinerary(id: it.id, duration: it.duration, startTime: it.startTime.addingTimeInterval(seconds),
                  endTime: it.endTime.addingTimeInterval(seconds), transfers: it.transfers, legs: it.legs)
    }

    func testReminderMovesWithLiveTimes() throws {
        let it = try TransitousClient.makeDecoder().decode(PlanResponse.self, from: Fixtures.data("plan_roma")).itineraries[0]
        let before = it.startTime.addingTimeInterval(-3600)
        XCTAssertTrue(TripStatus.reminderMoved(from: it, to: shifted(it, by: 5 * 60), now: before), "5 min delay")
        XCTAssertTrue(TripStatus.reminderMoved(from: it, to: shifted(it, by: -120), now: before), "earlier train")
        XCTAssertFalse(TripStatus.reminderMoved(from: it, to: shifted(it, by: 20), now: before), "under a minute")
        XCTAssertFalse(TripStatus.reminderMoved(from: it, to: shifted(it, by: 300), now: it.startTime.addingTimeInterval(400)),
                       "trip already started")
    }
}

final class SavedTripEqualityTests: XCTestCase {
    func testFollowLiveCountsAsAChange() throws {
        let it = try TransitousClient.makeDecoder().decode(PlanResponse.self, from: Fixtures.data("plan_roma")).itineraries[0]
        let a = SavedTrip(id: "t", itinerary: it, fromName: "A", toName: "B", savedAt: .distantPast)
        var b = a
        b.followLive = true
        XCTAssertNotEqual(a, b)
    }
}

final class MapStationIDTests: XCTestCase {
    func testOneParentWithTwoNamesGivesTwoIDs() {
        let stops = [
            MapStop(name: "Hauptbahnhof", stopId: "p1", parentId: "P", lat: 48.14, lon: 11.56, importance: 1, modes: ["RAIL"]),
            MapStop(name: "Hauptbahnhof Nord", stopId: "p2", parentId: "P", lat: 48.141, lon: 11.561, importance: 0.5, modes: ["BUS"]),
        ]
        let stations = MapStation.group(stops)
        XCTAssertEqual(stations.count, 2)
        XCTAssertEqual(Set(stations.map(\.id)).count, 2)
    }
}

final class ResponseCacheWriteTests: XCTestCase {
    func testDiskCopyIsReadableRightAfterStoring() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let cache = ResponseCache(directory: dir)
        for i in 0..<20 { cache.store("k\(i)", data: Data("v\(i)".utf8), url: "u\(i)", persist: true) }
        // A new instance (app relaunch) only has the disk copies; reads wait for pending writes.
        let relaunched = ResponseCache(directory: dir)
        for i in 0..<20 { XCTAssertEqual(relaunched.stale("k\(i)")?.data, Data("v\(i)".utf8)) }
    }
}

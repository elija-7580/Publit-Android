import XCTest
@testable import Publit

final class HangLogTests: XCTestCase {
    private func makeLog() -> HangLog {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        return HangLog(directory: dir)
    }

    func testShortWaitsAreNotRecorded() {
        let log = makeLog()
        let t0 = Date(timeIntervalSince1970: 1_000_000)
        XCTAssertTrue(log.tick(now: t0))
        XCTAssertFalse(log.tick(now: t0.addingTimeInterval(0.5)))
        log.answered(now: t0.addingTimeInterval(1.2))
        XCTAssertFalse(log.hasEntries)
    }

    func testFreezeIsRecordedWithLastScreensAndRecovery() throws {
        let log = makeLog()
        let t0 = Date(timeIntervalSince1970: 1_000_000)
        log.note("open Plan", at: t0.addingTimeInterval(-3))
        log.note("open Route", at: t0.addingTimeInterval(-1))
        XCTAssertTrue(log.tick(now: t0))
        for half in 1...11 { log.tick(now: t0.addingTimeInterval(Double(half) * 0.5)) }
        log.answered(now: t0.addingTimeInterval(5.6))
        log.flush()
        let text = try String(contentsOf: log.fileURL, encoding: .utf8)
        XCTAssertTrue(text.contains("No response for 2 s"))
        XCTAssertTrue(text.contains("open Plan") && text.contains("open Route"))
        XCTAssertTrue(text.contains("Still no response after 5 s"))
        XCTAssertFalse(text.contains("after 8 s"))
        XCTAssertTrue(text.contains("Responding again after 5.6 s"))
        log.clear()
        XCTAssertFalse(log.hasEntries)
    }

    func testSuspensionIsNotAFreeze() {
        let log = makeLog()
        let t0 = Date(timeIntervalSince1970: 1_000_000)
        XCTAssertTrue(log.tick(now: t0))
        // The app was suspended for a minute with the question still queued.
        XCTAssertFalse(log.tick(now: t0.addingTimeInterval(60)))
        XCTAssertFalse(log.tick(now: t0.addingTimeInterval(60.5)))
        log.answered(now: t0.addingTimeInterval(60.6))
        XCTAssertFalse(log.hasEntries)
    }

    func testTrailKeepsOnlyTheLatestSteps() throws {
        let log = makeLog()
        let t0 = Date(timeIntervalSince1970: 1_000_000)
        for i in 0..<60 { log.note("open Screen\(i)", at: t0) }
        log.tick(now: t0)
        log.tick(now: t0.addingTimeInterval(2.1))
        log.flush()
        let text = try String(contentsOf: log.fileURL, encoding: .utf8)
        XCTAssertTrue(text.contains("— FREEZE · "))
        XCTAssertFalse(text.contains("Screen19\n"))
        XCTAssertTrue(text.contains("Screen20\n") && text.contains("Screen59\n"))
    }

    func testErrorsAreRecordedOnceWithinTheWindow() throws {
        let log = makeLog()
        let t0 = Date(timeIntervalSince1970: 1_000_000)
        log.note("open Plan", at: t0)
        log.error("plan", URLError(.timedOut), at: t0)
        log.error("plan", URLError(.timedOut), at: t0.addingTimeInterval(60))
        log.error("plan", CancellationError(), at: t0.addingTimeInterval(61))
        log.error("refresh", URLError(.notConnectedToInternet), at: t0.addingTimeInterval(62))
        log.error("plan", URLError(.timedOut), at: t0.addingTimeInterval(400))
        log.flush()
        let text = try String(contentsOf: log.fileURL, encoding: .utf8)
        XCTAssertTrue(text.contains("plan: URLError \(URLError.Code.timedOut.rawValue)"))
        XCTAssertTrue(text.contains("open Plan"))
        let s = log.summary
        XCTAssertEqual(s.errors, 3)
        XCTAssertEqual(s.freezes, 0)
        XCTAssertEqual(s.total, 3)
        XCTAssertEqual(s.last, t0.addingTimeInterval(400))
    }

    func testDecodingErrorsNameTheFieldPath() {
        struct Leg: Decodable { let lat: Double }
        struct Root: Decodable { let legs: [Leg] }
        let data = Data(#"{"legs":[{"lat":1},{"lon":2}]}"#.utf8)
        do {
            _ = try JSONDecoder().decode(Root.self, from: data)
            XCTFail("should not decode")
        } catch {
            let text = HangLog.describe(error)
            XCTAssertTrue(text.contains("legs[1]"), text)
            XCTAssertTrue(text.contains("lat"), text)
        }
    }

    func testAnExitWhileOpenIsNotedAtTheNextLaunch() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let t0 = Date(timeIntervalSince1970: 1_000_000)
        let first = HangLog(directory: dir)
        first.scene(active: true, at: t0)
        first.note("open Trip", at: t0)
        first.flush()
        let next = HangLog(directory: dir)
        next.checkPreviousSession(at: t0.addingTimeInterval(30))
        next.flush()
        let text = try String(contentsOf: next.fileURL, encoding: .utf8)
        XCTAssertTrue(text.contains("— EXIT · "))
        XCTAssertTrue(text.contains("open Trip"))
        XCTAssertEqual(next.summary.exits, 1)
        // Checked once only.
        next.checkPreviousSession(at: t0.addingTimeInterval(31))
        XCTAssertEqual(next.summary.exits, 1)
    }

    func testABackgroundedSessionIsNotAnExit() {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let t0 = Date(timeIntervalSince1970: 1_000_000)
        let first = HangLog(directory: dir)
        first.scene(active: true, at: t0)
        first.scene(active: false, at: t0.addingTimeInterval(5))
        first.flush()
        let next = HangLog(directory: dir)
        next.checkPreviousSession(at: t0.addingTimeInterval(30))
        XCTAssertFalse(next.hasEntries)
    }

    func testReportHasHeaderCountsAndEntries() throws {
        let log = makeLog()
        let t0 = Date(timeIntervalSince1970: 1_000_000)
        log.error("departures", URLError(.badServerResponse), at: t0)
        let text = try String(contentsOf: log.writeReport(at: t0), encoding: .utf8)
        XCTAssertTrue(text.hasPrefix("Publit diagnostics · "))
        XCTAssertTrue(text.contains("Entries: 0 freezes, 1 errors, 0 crashes, 0 unexpected exits"))
        XCTAssertTrue(text.contains("— ERROR · "))
        XCTAssertTrue(text.contains("departures: URLError"))
    }

    func testOldEntriesCountAsFreezes() throws {
        let log = makeLog()
        try FileManager.default.createDirectory(at: log.fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try "\n— 2025-01-02 10:00:00 · Publit 0.5 (12) · iPhone · iOS 18\nNo response for 2 s.\n".write(to: log.fileURL, atomically: true, encoding: .utf8)
        XCTAssertEqual(log.summary.freezes, 1)
    }
}

/// A board refresh must not turn a departure into a different list row (that closed the trip opened from it).
final class StableDepartureIdentityTests: XCTestCase {
    private func stopTimes(delayingFirstBy minutes: Int = 0, duplicateFirst: Bool = false) throws -> [StopTime] {
        var root = try XCTUnwrap(JSONSerialization.jsonObject(with: Fixtures.data("stoptimes")) as? [String: Any])
        var list = try XCTUnwrap(root["stopTimes"] as? [[String: Any]])
        if minutes != 0 {
            var place = try XCTUnwrap(list[0]["place"] as? [String: Any])
            let departure = try XCTUnwrap(DateParsing.parse(try XCTUnwrap(place["departure"] as? String)))
            place["departure"] = ISO8601DateFormatter().string(from: departure.addingTimeInterval(Double(minutes) * 60))
            list[0]["place"] = place
        }
        if duplicateFirst { list.insert(list[0], at: 1) }
        root["stopTimes"] = list
        let data = try JSONSerialization.data(withJSONObject: root)
        return try TransitousClient.makeDecoder().decode(StopTimesResponse.self, from: data).stopTimes
    }

    func testANewDelayKeepsTheRowIdentity() throws {
        let before = try stopTimes()[0]
        let after = try stopTimes(delayingFirstBy: 4)[0]
        XCTAssertNotEqual(before.departureTime, after.departureTime)
        XCTAssertEqual(before.id, after.id)
    }

    func testRepeatedDeparturesAppearOnce() throws {
        let list = try stopTimes(duplicateFirst: true)
        let now = list.map(\.departureTime).min()!.addingTimeInterval(-120)
        let shown = StopTime.upcoming(list, now: now)
        XCTAssertEqual(Set(shown.map(\.id)).count, shown.count)
        XCTAssertEqual(shown.count, list.count - 1)
    }
}

#if canImport(Darwin)
final class RelativeAgoTests: XCTestCase {
    func testAgoIsToTheMinute() {
        let now = Date(timeIntervalSinceReferenceDate: 800_000_000)
        let en = Locale(identifier: "en_US")
        XCTAssertEqual(Fmt.ago(now.addingTimeInterval(-20), now: now, locale: en), "now")
        XCTAssertEqual(Fmt.ago(now.addingTimeInterval(-5 * 60 - 40), now: now, locale: en), "5 minutes ago")
    }
}
#endif

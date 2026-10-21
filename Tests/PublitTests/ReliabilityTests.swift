import Foundation
import XCTest
@testable import Publit

/// Network sharing and retries, Live Activity refresh pacing, fares kept across live refreshes.
final class ReliabilityTests: XCTestCase {
    private final class Counter: @unchecked Sendable {
        private let lock = NSLock()
        private var n = 0
        func next() -> Int { lock.lock(); defer { lock.unlock() }; n += 1; return n }
        var value: Int { lock.lock(); defer { lock.unlock() }; return n }
    }

    func testOverlappingRequestsShareOneDownload() async throws {
        let coalescer = RequestCoalescer()
        let calls = Counter()
        let load: @Sendable () async throws -> (Data, Int) = {
            _ = calls.next()
            try await Task.sleep(for: .milliseconds(200))
            return (Data("ok".utf8), 200)
        }
        async let a = coalescer.run("u", load)
        async let b = coalescer.run("u", load)
        async let c = coalescer.run("other", load)
        let results = try await [a, b, c]
        XCTAssertEqual(results.map(\.1), [200, 200, 200])
        XCTAssertEqual(calls.value, 2, "same URL once, the other URL once")
        let left = await coalescer.count
        XCTAssertEqual(left, 0)
    }

    func testOneCancelledCallerDoesNotCancelTheOther() async throws {
        let coalescer = RequestCoalescer()
        let load: @Sendable () async throws -> (Data, Int) = {
            try await Task.sleep(for: .milliseconds(300))
            return (Data(), 200)
        }
        let first = Task { try await coalescer.run("u", load) }
        try await Task.sleep(for: .milliseconds(50))
        let second = Task { try await coalescer.run("u", load) }
        try await Task.sleep(for: .milliseconds(50))
        first.cancel()
        let r = try await second.value
        XCTAssertEqual(r.1, 200)
    }

    func testRetriesOnceWhenTheServerIsBusy() async throws {
        let calls = Counter()
        let r = try await TransitousClient.withRetry(delay: .milliseconds(10)) {
            calls.next() == 1 ? (Data(), 503) : (Data("ok".utf8), 200)
        }
        XCTAssertEqual(r.1, 200)
        XCTAssertEqual(calls.value, 2)
    }

    func testRetriesOnceWhenTheConnectionDrops() async throws {
        let calls = Counter()
        let r = try await TransitousClient.withRetry(delay: .milliseconds(10)) {
            if calls.next() == 1 { throw URLError(.networkConnectionLost) }
            return (Data(), 200)
        }
        XCTAssertEqual(r.1, 200)
        XCTAssertEqual(calls.value, 2)
    }

    func testNoRetryForClientErrorsOrTimeouts() async throws {
        let calls = Counter()
        let r = try await TransitousClient.withRetry(delay: .milliseconds(10)) { _ = calls.next(); return (Data(), 400) }
        XCTAssertEqual(r.1, 400)
        XCTAssertEqual(calls.value, 1)
        let timeouts = Counter()
        do {
            _ = try await TransitousClient.withRetry(delay: .milliseconds(10)) { _ = timeouts.next(); throw URLError(.timedOut) }
            XCTFail("should throw")
        } catch {}
        XCTAssertEqual(timeouts.value, 1)
    }

    func testLiveActivityRefreshesRarelyWhileNothingHappens() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        func interval(start: Double, end: Double, next: Double) -> TimeInterval {
            LiveRefresh.interval(start: now.addingTimeInterval(start * 60), end: now.addingTimeInterval(end * 60),
                                 nextEvent: now.addingTimeInterval(next * 60), now: now)
        }
        XCTAssertEqual(interval(start: 120, end: 160, next: 120), 600)
        XCTAssertEqual(interval(start: 30, end: 60, next: 30), 180)
        XCTAssertEqual(interval(start: 8, end: 40, next: 8), 60)
        XCTAssertEqual(interval(start: -10, end: 50, next: 40), 120, "long ride")
        XCTAssertEqual(interval(start: -10, end: 5, next: 5), 60, "getting off soon")
        XCTAssertEqual(interval(start: -60, end: -1, next: -1), LiveRefresh.idle)
        XCTAssertEqual(LiveRefresh.interval([], now: now), LiveRefresh.idle)
    }

    func testLiveActivityHeadlineHasNoStaleCountdown() throws {
        let it = try TransitousClient.makeDecoder().decode(PlanResponse.self, from: Fixtures.data("plan")).itineraries[0]
        let before = it.startTime.addingTimeInterval(-10 * 60)
        XCTAssertNotEqual(TripStatus.at(before, it).headline, TripStatus.at(before, it, countdown: false).headline)
        XCTAssertEqual(TripStatus.at(before, it, countdown: false).headline,
                       TripStatus.at(before.addingTimeInterval(120), it, countdown: false).headline)
    }

    func testLiveRefreshKeepsTheSavedFares() throws {
        let it = try TransitousClient.makeDecoder().decode(PlanResponse.self, from: Fixtures.data("plan")).itineraries[0]
        var old = it
        old.fareTransfers = [FareTransfer(rule: nil, transferProducts: nil, effectiveFareLegProducts: [])]
        for i in old.legs.indices where old.legs[i].isTransit { old.legs[i].fareTransferIndex = 0 }
        let merged = TripStore.keepingFares(of: old, in: it)
        XCTAssertEqual(merged.fareTransfers?.count, 1)
        XCTAssertEqual(merged.legs.compactMap(\.fareTransferIndex).count, old.legs.compactMap(\.fareTransferIndex).count)
        // A refresh that brings fares of its own wins.
        XCTAssertEqual(TripStore.keepingFares(of: it, in: old).fareTransfers?.count, 1)
    }
}

final class ServerFailoverTests: XCTestCase {
    func testBackupKeepsPathAndQuery() {
        let url = URL(string: "https://api.transitous.org/api/v6/stoptimes?stopId=de-DELFI_de:09162:6&n=5")!
        let moved = TransitousClient.moved(url, to: TransitousClient.backupServer)
        XCTAssertEqual(moved.absoluteString, "https://europe.motis-project.de/api/v6/stoptimes?stopId=de-DELFI_de:09162:6&n=5")
    }

    func testOnlyServerProblemsFailOver() {
        XCTAssertTrue(TransitousClient.serverFailed(status: 503))
        XCTAssertTrue(TransitousClient.serverFailed(status: 429))
        XCTAssertFalse(TransitousClient.serverFailed(status: 400))
        XCTAssertFalse(TransitousClient.serverFailed(status: 200))
        XCTAssertTrue(TransitousClient.shouldFailOver(URLError(.cannotConnectToHost)))
        XCTAssertTrue(TransitousClient.shouldFailOver(URLError(.timedOut)))
        // Offline or cancelled: another server would not help.
        XCTAssertFalse(TransitousClient.shouldFailOver(URLError(.notConnectedToInternet)))
        XCTAssertFalse(TransitousClient.shouldFailOver(URLError(.cancelled)))
        XCTAssertFalse(TransitousClient.shouldFailOver(CancellationError()))
    }

    func testBackupFirstForAWhileAfterAFailure() {
        let f = ServerFailover()
        let t0 = Date()
        XCTAssertTrue(f.primaryFirst(now: t0))
        f.primaryFailed(now: t0)
        XCTAssertFalse(f.primaryFirst(now: t0.addingTimeInterval(60)))
        XCTAssertTrue(f.primaryFirst(now: t0.addingTimeInterval(ServerFailover.holdOff + 1)))
        f.primaryFailed(now: t0)
        f.primaryAnswered()
        XCTAssertTrue(f.primaryFirst(now: t0))
    }
}

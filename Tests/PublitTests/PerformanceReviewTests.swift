import Foundation
import XCTest
@testable import Publit

final class PerformanceReviewTests: XCTestCase {
    func testAllCancelledWaitersReleaseTheFlightImmediately() async throws {
        let coalescer = RequestCoalescer()
        let loader: @Sendable () async throws -> (Data, Int) = {
            try await Task.sleep(for: .seconds(30))
            return (Data(), 200)
        }
        let a = Task { try await coalescer.run("same", loader) }
        let b = Task { try await coalescer.run("same", loader) }
        try await Task.sleep(for: .milliseconds(50))
        a.cancel(); b.cancel()
        for task in [a, b] {
            do { _ = try await task.value; XCTFail("cancelled waiter succeeded") } catch is CancellationError {} catch { XCTFail("\(error)") }
        }
        let pending = await coalescer.count
        XCTAssertEqual(pending, 0)
        // An old cancelled completion must not evict a new flight with the same URL.
        let fresh = try await coalescer.run("same") { (Data("new".utf8), 200) }
        XCTAssertEqual(fresh.0, Data("new".utf8))
    }

    func testSingleCancelledWaiterReturnsWithoutWaitingForSharedDownload() async throws {
        let coalescer = RequestCoalescer()
        let loader: @Sendable () async throws -> (Data, Int) = {
            try await Task.sleep(for: .milliseconds(300))
            return (Data("ok".utf8), 200)
        }
        let a = Task { try await coalescer.run("same", loader) }
        let b = Task { try await coalescer.run("same", loader) }
        try await Task.sleep(for: .milliseconds(50))
        a.cancel()
        do { _ = try await a.value; XCTFail("cancelled waiter succeeded") } catch is CancellationError {} catch { XCTFail("\(error)") }
        let pending = await coalescer.count
        XCTAssertEqual(pending, 1, "other waiter must still own the download")
        let result = try await b.value
        XCTAssertEqual(result.1, 200)
    }

    func testOversizedResponseIsNotRetainedInMemory() {
        let cache = ResponseCache(directory: nil)
        cache.store("huge", data: Data(repeating: 0, count: ResponseCache.maxMemoryBytes + 1), url: "u", persist: false)
        XCTAssertNil(cache.fresh("huge", maxAge: 60))
        XCTAssertNil(cache.stale("huge"))
    }

    func testReplacingAnEntryDoesNotEvictOtherEntriesAtCountLimit() {
        let cache = ResponseCache(directory: nil)
        for i in 0..<ResponseCache.maxMemoryEntries { cache.store("k\(i)", data: Data(), url: "u", persist: false) }
        cache.store("k0", data: Data("updated".utf8), url: "u", persist: false)
        XCTAssertNotNil(cache.fresh("k1", maxAge: 60))
        XCTAssertEqual(cache.fresh("k0", maxAge: 60)?.data, Data("updated".utf8))
    }
}

import XCTest
@testable import Publit

final class ResourceUseTests: XCTestCase {
    private func tempDir() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    }

    func testDecodedAnswerIsKeptWithTheMemoryCopy() throws {
        let cache = ResponseCache(directory: nil)
        let data = Data("{\"a\":1}".utf8)
        cache.store("k", data: data, url: "u", persist: false)
        let hit = try XCTUnwrap(cache.fresh("k", maxAge: 60))
        XCTAssertNil(hit.decoded)
        cache.remember("k", decoded: [1, 2, 3], for: hit)
        XCTAssertEqual(cache.fresh("k", maxAge: 60)?.decoded as? [Int], [1, 2, 3])
        XCTAssertEqual(cache.memoryBytes, data.count * 3)
        // A newer answer replaces it; a late remember for the old one is ignored.
        cache.store("k", data: data, url: "u2", persist: false, now: Date().addingTimeInterval(1))
        cache.remember("k", decoded: [9], for: hit)
        XCTAssertNil(cache.fresh("k", maxAge: 60)?.decoded)
    }

    func testLiveAnswersAreWrittenToDiskAtMostEveryInterval() throws {
        let dir = tempDir()
        defer { try? FileManager.default.removeItem(at: dir) }
        let cache = ResponseCache(directory: dir)
        let t0 = Date(timeIntervalSince1970: 1_000_000)
        cache.store("live", data: Data("1".utf8), url: "u", persist: true, now: t0, diskInterval: 120)
        cache.store("live", data: Data("2".utf8), url: "u", persist: true, now: t0.addingTimeInterval(30), diskInterval: 120)
        ResponseCache.flushWrites()
        let file = dir.appendingPathComponent(ResponseCache.fileName("live"))
        XCTAssertEqual(ResponseCache.decode(try Data(contentsOf: file))?.data, Data("1".utf8))
        // Memory always has the newest answer.
        XCTAssertEqual(cache.fresh("live", maxAge: 60, now: t0.addingTimeInterval(31))?.data, Data("2".utf8))
        cache.store("live", data: Data("3".utf8), url: "u", persist: true, now: t0.addingTimeInterval(121), diskInterval: 120)
        ResponseCache.flushWrites()
        XCTAssertEqual(ResponseCache.decode(try Data(contentsOf: file))?.data, Data("3".utf8))
    }

    func testReleasingMemoryKeepsOnlyTheNewestBytes() {
        let cache = ResponseCache(directory: nil)
        let t0 = Date()
        for i in 0..<5 {
            cache.store("k\(i)", data: Data(count: 1000), url: "u", persist: false,
                        now: t0.addingTimeInterval(Double(i)), decoded: i)
        }
        XCTAssertEqual(cache.memoryBytes, 15_000)
        cache.releaseMemory(keepingBytes: 2500)
        XCTAssertEqual(cache.memoryBytes, 2000)
        XCTAssertNotNil(cache.fresh("k4", maxAge: 60))
        XCTAssertNotNil(cache.fresh("k3", maxAge: 60))
        XCTAssertNil(cache.fresh("k2", maxAge: 60))
        XCTAssertNil(cache.fresh("k4", maxAge: 60)?.decoded)
        cache.releaseMemory()
        XCTAssertEqual(cache.memoryBytes, 0)
        XCTAssertNil(cache.fresh("k4", maxAge: 60))
    }

    func testConstrainedEnergyDoublesScreenRefreshes() {
        XCTAssertEqual(Energy.interval(30, constrained: false), 30)
        XCTAssertEqual(Energy.interval(30, constrained: true), 60)
    }

    func testSessionStepsAreWrittenOnFlush() throws {
        let dir = tempDir()
        defer { try? FileManager.default.removeItem(at: dir) }
        let t0 = Date(timeIntervalSince1970: 1_000_000)
        let log = HangLog(directory: dir)
        log.scene(active: true, at: t0)
        for i in 0..<20 { log.note("step \(i)", at: t0.addingTimeInterval(Double(i))) }
        log.flush()
        let next = HangLog(directory: dir)
        next.checkPreviousSession(at: t0.addingTimeInterval(60))
        next.flush()
        let text = try String(contentsOf: next.fileURL, encoding: .utf8)
        XCTAssertTrue(text.contains("step 19"))
    }
}

import XCTest
@testable import Publit

final class Build51Tests: XCTestCase {
    func testExportCombinesDefaultsAndFiles() throws {
        let suite = "build51-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set(Data(#"[{"name":"Karlsplatz"}]"#.utf8), forKey: "publit.favorites.v1")
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(suite, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        try Data(#"[{"id":"t1"}]"#.utf8).write(to: dir.appendingPathComponent("trips.json"))

        let data = DataExport.json(defaults: defaults, directory: dir, now: Date(timeIntervalSince1970: 0))
        let root = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(root["app"] as? String, "Publit")
        XCTAssertEqual((root["places"] as? [[String: Any]])?.first?["name"] as? String, "Karlsplatz")
        XCTAssertEqual((root["trips"] as? [[String: Any]])?.first?["id"] as? String, "t1")
        XCTAssertNil(root["searchHistory"], "missing files are left out")
        XCTAssertNil(root["lines"])
    }

    func testStartTabRawValues() {
        XCTAssertEqual(StartTab.allCases.map(\.rawValue), ["nearby", "plan"])
        XCTAssertEqual(StartTab.defaultsKey, "startTab")
    }
}

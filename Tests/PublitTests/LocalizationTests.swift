import Foundation
import XCTest

final class LocalizationTests: XCTestCase {
    private let languages = Set(["en", "de", "fr", "it", "es", "pt-PT", "nl", "pl", "uk", "ro", "cs", "sv", "el", "hu", "tr"])
    private var root: URL {
        URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
    }

    private func catalog(_ name: String) throws -> [String: Any] {
        let data = try Data(contentsOf: root.appendingPathComponent(name))
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    private func leaves(_ value: [String: Any]) -> [String] {
        if let unit = value["stringUnit"] as? [String: Any], let text = unit["value"] as? String { return [text] }
        return value.values.flatMap { ($0 as? [String: Any]).map(leaves) ?? [] }
    }

    private func tokens(_ text: String) -> [String] {
        let expression = try! NSRegularExpression(pattern: #"%(?:\d+\$)?(?:lld|ld|d|@)|\$\{[^}]+\}"#)
        let ns = text as NSString
        return expression.matches(in: text, range: NSRange(location: 0, length: ns.length))
            .map { ns.substring(with: $0.range).replacingOccurrences(of: #"^%\d+\$"#, with: "%", options: .regularExpression) }
            .sorted()
    }

    func testEveryCatalogHasAllLanguagesAndPreservesPlaceholders() throws {
        for path in ["Sources/Shared/Localizable.xcstrings", "Sources/App/AppShortcuts.xcstrings", "Sources/App/InfoPlist.xcstrings"] {
            let data = try catalog(path)
            XCTAssertEqual(data["sourceLanguage"] as? String, "en")
            let strings = try XCTUnwrap(data["strings"] as? [String: [String: Any]])
            XCTAssertFalse(strings.isEmpty)
            for (key, item) in strings {
                let loc = try XCTUnwrap(item["localizations"] as? [String: [String: Any]])
                XCTAssertEqual(Set(loc.keys), languages, "Missing language: \(key)")
                let source = try XCTUnwrap(loc["en"].map(leaves)?.first)
                for (language, translation) in loc {
                    let texts = leaves(translation)
                    XCTAssertFalse(texts.isEmpty, "Empty translation: \(key)/\(language)")
                    for text in texts {
                        XCTAssertEqual(tokens(text), tokens(source), "Placeholder mismatch: \(key)/\(language)")
                        XCTAssertFalse(text.contains("ZXQ"), "Unrestored placeholder")
                    }
                }
            }
        }
    }

    func testSlavicAndRomanianPlurals() throws {
        let data = try catalog("Sources/Shared/Localizable.xcstrings")
        let strings = try XCTUnwrap(data["strings"] as? [String: [String: Any]])
        for key in ["%lld changes", "%lld stops", "live, %lld minutes late"] {
            let loc = try XCTUnwrap(strings[key]?["localizations"] as? [String: [String: Any]])
            for language in ["pl", "uk", "cs", "ro"] {
                let variations = try XCTUnwrap(loc[language]?["variations"] as? [String: Any])
                let plural = try XCTUnwrap(variations["plural"] as? [String: Any])
                XCTAssertNotNil(plural["one"])
                XCTAssertNotNil(plural["few"])
                XCTAssertNotNil(plural["other"])
                if language != "ro" { XCTAssertNotNil(plural["many"]) }
            }
        }
    }

    func testPositionalPlaceholdersAreCompleteAndInRange() throws {
        let data = try catalog("Sources/Shared/Localizable.xcstrings")
        let strings = try XCTUnwrap(data["strings"] as? [String: [String: Any]])
        let any = try NSRegularExpression(pattern: #"%(?:\d+\$)?(?:lld|ld|d|@)"#)
        let positional = try NSRegularExpression(pattern: #"%(\d+)\$"#)
        for (key, item) in strings {
            let loc = try XCTUnwrap(item["localizations"] as? [String: [String: Any]])
            for (language, translation) in loc {
                for text in leaves(translation) {
                    let ns = text as NSString
                    let total = any.numberOfMatches(in: text, range: NSRange(location: 0, length: ns.length))
                    let indexes = positional.matches(in: text, range: NSRange(location: 0, length: ns.length))
                        .compactMap { Int(ns.substring(with: $0.range(at: 1))) }
                    guard !indexes.isEmpty else { continue }
                    XCTAssertEqual(indexes.count, total, "Mixed positional placeholders: \(key)/\(language)")
                    XCTAssertEqual(Set(indexes), Set(1...total), "Positional placeholders out of range: \(key)/\(language)")
                }
            }
        }
    }

    func testSiriPhrasesAlwaysContainExactlyOneApplicationName() throws {
        let data = try catalog("Sources/App/AppShortcuts.xcstrings")
        let strings = try XCTUnwrap(data["strings"] as? [String: [String: Any]])
        for (_, item) in strings {
            let loc = try XCTUnwrap(item["localizations"] as? [String: [String: Any]])
            for (_, translation) in loc {
                for text in leaves(translation) {
                    XCTAssertEqual(tokens(text).filter { $0 == "${applicationName}" }.count, 1)
                }
            }
        }
    }
}

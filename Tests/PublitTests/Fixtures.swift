import Foundation
import XCTest

enum Fixtures {
    private final class Token {}

    static func data(_ name: String) throws -> Data {
        #if SWIFT_PACKAGE
        let bundle = Bundle.module
        let url = bundle.url(forResource: name, withExtension: "json", subdirectory: "Fixtures")
            ?? bundle.url(forResource: name, withExtension: "json")
        #else
        let url = Bundle(for: Token.self).url(forResource: name, withExtension: "json")
        #endif
        return try Data(contentsOf: XCTUnwrap(url, "missing fixture \(name).json"))
    }
}

import Foundation
#if canImport(os)
import os
#endif

/// Inspect network, storage and image work in Instruments → Points of Interest.
/// No location, stop name, URL or ticket contents are logged.
enum PerformanceTrace {
    struct Token {
        #if canImport(os)
        let id: OSSignpostID
        #endif
    }
    #if canImport(os)
    private static let log = OSLog(subsystem: "io.github.publit.app", category: "PointsOfInterest")
    #endif
    static func begin(_ name: StaticString) -> Token {
        #if canImport(os)
        let id = OSSignpostID(log: log)
        os_signpost(.begin, log: log, name: name, signpostID: id)
        return Token(id: id)
        #else
        return Token()
        #endif
    }
    static func end(_ token: Token, _ name: StaticString) {
        #if canImport(os)
        os_signpost(.end, log: log, name: name, signpostID: token.id)
        #endif
    }
}

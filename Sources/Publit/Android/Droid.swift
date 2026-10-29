#if os(Android)
import Foundation
import SkipFuse

/// Calls into the app's Kotlin side (`Sources/Publit/Skip/Droid.kt`, companion of `publit.module.Droid`):
/// Android services the iOS frameworks provided (location, notifications, speech, calendar, sharing …).
/// Values cross as simple types or JSON strings.
enum Droid {
    // A `static let` is initialised once, thread-safely. The former lazily-set `static var` was read
    // and written from the main thread and background tasks at the same time (a data race).
    nonisolated(unsafe) private static let statics: AnyDynamicObject? =
        try? AnyDynamicObject(forStaticsOfClassName: "publit.module.Droid")

    static var kotlin: AnyDynamicObject? { statics }

    /// Calls a Kotlin function without a result.
    static func call(_ name: String, _ args: Any?...) {
        guard let k = kotlin else { return }
        do { try k[dynamicMember: name].dynamicallyCall(withArguments: args) as Void }
        catch { logger.error("Droid.\(name) failed: \(error)") }
    }

    static func string(_ name: String, _ args: Any?...) -> String? {
        guard let k = kotlin else { return nil }
        do { return try k[dynamicMember: name].dynamicallyCall(withArguments: args) as String? }
        catch { logger.error("Droid.\(name) failed: \(error)"); return nil }
    }

    static func bool(_ name: String, _ args: Any?...) -> Bool {
        guard let k = kotlin else { return false }
        do { return try (k[dynamicMember: name].dynamicallyCall(withArguments: args) as Bool?) ?? false }
        catch { logger.error("Droid.\(name) failed: \(error)"); return false }
    }

    static func double(_ name: String, _ args: Any?...) -> Double? {
        guard let k = kotlin else { return nil }
        do { return try k[dynamicMember: name].dynamicallyCall(withArguments: args) as Double? }
        catch { logger.error("Droid.\(name) failed: \(error)"); return nil }
    }

    /// A Kotlin object made with its constructor (for Compose content in `ComposeView`).
    static func make(_ className: String, _ args: Any?...) -> AnyDynamicObject? {
        do { return try AnyDynamicObject(className: className, arguments: args) }
        catch { logger.error("Droid make \(className) failed: \(error)"); return nil }
    }
}

/// Waits for Android activity results (pickers, scanners): `await DroidRequests.shared.run { id in … }`.
@MainActor final class DroidRequests {
    static let shared = DroidRequests()
    private var waiting: [String: CheckedContinuation<String, Never>] = [:]

    func run(_ start: (String) -> Void) async -> String {
        let id = UUID().uuidString
        return await withCheckedContinuation { c in
            waiting[id] = c
            start(id)
        }
    }

    func finish(_ id: String, _ json: String) {
        waiting.removeValue(forKey: id)?.resume(returning: json)
    }
}
#endif

import Foundation

/// Battery-aware timing for screen refreshes. In Low Power Mode, or when the phone is hot,
/// live screens refresh half as often: the data stays usable, the radio and CPU rest more.
/// Live Activities and reminders keep their own timing.
enum Energy {
    /// True in Low Power Mode or at a serious or critical thermal state.
    static var isConstrained: Bool {
        #if canImport(Darwin)
        let info = ProcessInfo.processInfo
        return info.isLowPowerModeEnabled || info.thermalState.rawValue >= ProcessInfo.ThermalState.serious.rawValue
        #else
        return false
        #endif
    }

    /// `seconds`, doubled while energy is constrained.
    static func interval(_ seconds: TimeInterval, constrained: Bool = Energy.isConstrained) -> TimeInterval {
        constrained ? seconds * 2 : seconds
    }

    static func duration(_ seconds: TimeInterval) -> Duration { .seconds(interval(seconds)) }
}

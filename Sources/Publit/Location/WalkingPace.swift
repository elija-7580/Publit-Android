#if canImport(CoreMotion)
import CoreMotion
#endif
import Foundation
#if canImport(HealthKit)
import HealthKit
#endif

/// Your usual walking speed, so smart routing plans walks and changes at your pace: from Apple Health
/// (the iPhone's measured walking speed, last 30 days) when you allow it, else from the step counter
/// (last seven days). Read on the device only; only the speed is kept.
enum WalkingPace {
    enum Source: String { case health, steps }
    static let sourceKey = "walkingSpeedSource"

    static var healthAvailable: Bool {
        #if canImport(HealthKit)
        return HKHealthStore.isHealthDataAvailable()
        #else
        return false
        #endif
    }

    static var pedometerAvailable: Bool { CMPedometer.isDistanceAvailable() && CMPedometer.isStepCountingAvailable() }
    static var isAvailable: Bool { healthAvailable || pedometerAvailable }

    static var source: Source? { UserDefaults.standard.string(forKey: sourceKey).flatMap(Source.init(rawValue:)) }

    /// Measures and stores the speed in m/s; nil without enough walking or without access.
    @MainActor
    static func measure() async -> Double? {
        var found: (speed: Double, source: Source)?
        if let v = await healthSpeed(), (0.6...2.2).contains(v) { found = (v, .health) }
        if found == nil, let v = await pedometerSpeed(), (0.6...2.2).contains(v) { found = (v, .steps) }
        guard let found else { return nil }
        UserDefaults.standard.set(found.speed, forKey: SmartRouting.measuredSpeedKey)
        UserDefaults.standard.set(found.source.rawValue, forKey: sourceKey)
        return found.speed
    }

    /// Apple Health's walking speed (recorded by the iPhone while you walk with it).
    @MainActor
    private static func healthSpeed() async -> Double? {
        #if canImport(HealthKit)
        guard healthAvailable else { return nil }
        let store = HKHealthStore()
        let type = HKQuantityType(.walkingSpeed)
        do { try await store.requestAuthorization(toShare: [], read: [type]) } catch { return nil }
        let end = Date()
        let predicate = HKQuery.predicateForSamples(withStart: end.addingTimeInterval(-30 * 86_400), end: end)
        let query = HKStatisticsQueryDescriptor(predicate: .quantitySample(type: type, predicate: predicate), options: .discreteAverage)
        guard let stats = try? await query.result(for: store), let average = stats.averageQuantity() else { return nil }
        return average.doubleValue(for: HKUnit.meter().unitDivided(by: .second()))
        #else
        return nil
        #endif
    }

    @MainActor
    private static func pedometerSpeed() async -> Double? {
        guard pedometerAvailable else { return nil }
        let pedometer = CMPedometer()
        let end = Date()
        let start = end.addingTimeInterval(-7 * 86_400)
        let result: (pace: Double?, distance: Double?, steps: Double?) = await withCheckedContinuation { c in
            pedometer.queryPedometerData(from: start, to: end) { data, _ in
                _ = pedometer
                c.resume(returning: (data?.averageActivePace?.doubleValue, data?.distance?.doubleValue, data?.numberOfSteps.doubleValue))
            }
        }
        if let pace = result.pace, pace > 0 { return 1 / pace }
        // No pace in the history: step length at a usual walking cadence (about 1.8 steps a second).
        if let d = result.distance, let s = result.steps, s > 2_000 { return d / s * 1.8 }
        return nil
    }
}

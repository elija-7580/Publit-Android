#if canImport(MetricKit) && canImport(UIKit)
import MachO
#if canImport(MetricKit)
import MetricKit
#endif
#if canImport(UIKit)
import UIKit
#endif

/// Crashes and hangs that iOS itself recorded (MetricKit), written into the diagnostics with the
/// crashed thread's call stack. iOS hands them over at the next launch (from iOS 15 right away),
/// also for crashes the app could not note itself. Plus memory warnings and heat as steps.
final class CrashReports: NSObject, MXMetricManagerSubscriber, @unchecked Sendable {
    static let shared = CrashReports()
    /// End time of the newest payload already written, so a relaunch does not repeat it.
    private let seenKey = "diagnosticsSeenUntil"

    func start() {
        MXMetricManager.shared.add(self)
        handle(MXMetricManager.shared.pastDiagnosticPayloads)
        let center = NotificationCenter.default
        center.addObserver(forName: UIApplication.didReceiveMemoryWarningNotification, object: nil, queue: nil) { _ in
            HangLog.shared.report("MEMORY", "iOS warned that memory is low.")
        }
        center.addObserver(forName: ProcessInfo.thermalStateDidChangeNotification, object: nil, queue: nil) { _ in
            let names = ["nominal", "fair", "serious", "critical"]
            HangLog.shared.note("thermal \(names[min(ProcessInfo.processInfo.thermalState.rawValue, 3)])")
        }
    }

    func didReceive(_ payloads: [MXDiagnosticPayload]) { handle(payloads) }

    private func handle(_ payloads: [MXDiagnosticPayload]) {
        let defaults = UserDefaults.standard
        let seen = defaults.double(forKey: seenKey)
        var newest = seen
        for p in payloads.sorted(by: { $0.timeStampEnd < $1.timeStampEnd }) {
            let end = p.timeStampEnd.timeIntervalSince1970
            guard end > seen else { continue }
            newest = max(newest, end)
            for c in p.crashDiagnostics ?? [] {
                var text = "iOS recorded a crash in build \(c.metaData.applicationBuildVersion) (\(c.metaData.osVersion))."
                if let t = c.exceptionType { text += " Exception \(t)" }
                if let code = c.exceptionCode { text += " code \(code)" }
                if let sig = c.signal { text += " signal \(sig)" }
                if let reason = c.terminationReason { text += "\nReason: \(reason)" }
                if #available(iOS 17.0, *), let objc = c.exceptionReason {
                    text += "\n\(objc.className): \(objc.composedMessage)"
                }
                HangLog.shared.report("CRASH", text, at: p.timeStampEnd, steps: [], stack: Self.stack(c.callStackTree, build: c.metaData.applicationBuildVersion))
            }
            for h in p.hangDiagnostics ?? [] {
                let seconds = h.hangDuration.converted(to: .seconds).value
                HangLog.shared.report("HANG", String(format: "iOS recorded a hang of %.1f s (build %@).", seconds, h.metaData.applicationBuildVersion),
                                      at: p.timeStampEnd, steps: [], stack: Self.stack(h.callStackTree, build: h.metaData.applicationBuildVersion))
            }
            for e in p.cpuExceptionDiagnostics ?? [] {
                HangLog.shared.report("CPU", "iOS noted heavy CPU use: \(e.totalCPUTime) over \(e.totalSampledTime).",
                                      at: p.timeStampEnd, steps: [], stack: Self.stack(e.callStackTree, build: e.metaData.applicationBuildVersion))
            }
        }
        if newest > seen { defaults.set(newest, forKey: seenKey) }
    }

    /// The attributed (crashed or hung) thread, innermost frame first. Names are looked up here when
    /// the report is from the build that is running; otherwise binary and offset (for a dSYM).
    static func stack(_ tree: MXCallStackTree, build: String) -> String {
        guard let json = try? JSONSerialization.jsonObject(with: tree.jsonRepresentation()) as? [String: Any],
              let stacks = json["callStacks"] as? [[String: Any]], !stacks.isEmpty else { return "  (no call stack)\n" }
        let thread = stacks.first { ($0["threadAttributed"] as? Bool) == true } ?? stacks[0]
        var frames: [[String: Any]] = []
        var next = (thread["callStackRootFrames"] as? [[String: Any]])?.first
        while let f = next, frames.count < 60 {
            frames.append(f)
            next = (f["subFrames"] as? [[String: Any]])?.first
        }
        let sameBuild = build == AppInfo.build
        var out = "Call stack:\n"
        for f in frames {
            let binary = f["binaryName"] as? String ?? "?"
            let offset = (f["offsetIntoBinaryTextSegment"] as? NSNumber)?.uintValue ?? 0
            let name = sameBuild ? symbol(binary: binary, offset: offset) : nil
            out += "    \(binary)  " + (name ?? String(format: "+0x%lx", offset)) + "\n"
        }
        return out
    }

    /// Function name at `offset` in a loaded image of this process (same build only).
    private static func symbol(binary: String, offset: UInt) -> String? {
        for i in 0..<_dyld_image_count() {
            guard let cName = _dyld_get_image_name(i), let header = _dyld_get_image_header(i),
                  (String(cString: cName) as NSString).lastPathComponent == binary else { continue }
            var info = Dl_info()
            let address = UnsafeRawPointer(header).advanced(by: Int(offset))
            guard dladdr(address, &info) != 0, let s = info.dli_sname else { return nil }
            let base = info.dli_saddr.map { UnsafeRawPointer($0).distance(to: address) } ?? 0
            return MainThreadSampler.tidy("0 \(binary) 0x0 \(String(cString: s)) + \(base)")
                .replacingOccurrences(of: "\(binary)  ", with: "")
        }
        return nil
    }
}
#elseif os(Android)
import Foundation
/// Crashes, hangs (ANRs) and low-memory ends that Android recorded for the app (ApplicationExitInfo,
/// Android 11+), plus the call stack of the last Kotlin/Java exception, written into the diagnostics
/// at the next launch, like MetricKit's reports on iOS. Memory warnings and heat as steps.
final class CrashReports {
    static let shared = CrashReports()
    /// End time of the newest report already written, so a relaunch does not repeat it.
    private let seenKey = "diagnosticsSeenUntil"

    private struct Report: Decodable {
        let kind: String
        let time: Double
        let text: String
        let stack: String
    }

    func start() {
        Droid.call("startDiagnostics", AppInfo.build)
        let defaults = UserDefaults.standard
        let seen = defaults.double(forKey: seenKey)
        guard let json = Droid.string("diagnosticReports", seen * 1000), let data = json.data(using: .utf8),
              let reports = try? JSONDecoder().decode([Report].self, from: data) else { return }
        var newest = seen
        for r in reports.sorted(by: { $0.time < $1.time }) {
            let at = Date(timeIntervalSince1970: r.time / 1000)
            newest = max(newest, at.timeIntervalSince1970)
            HangLog.shared.report(r.kind, r.text, at: at, steps: [], stack: r.stack.isEmpty ? "  (no call stack)\n" : "Call stack:\n" + r.stack)
        }
        if newest > seen { defaults.set(newest, forKey: seenKey) }
    }
}
#else
final class CrashReports {
    static let shared = CrashReports()
    func start() {}
}
#endif

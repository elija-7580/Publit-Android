import Foundation

/// Diagnostics on the device: freezes (main thread blocked 2 s or more, with its call stack),
/// failed requests and unreadable answers, crashes and hangs reported by iOS (MetricKit), and
/// sessions that ended while the app was open (crash, watchdog or memory kill). Each entry has the
/// last steps before it (screens, tabs, requests by type). Only fixed screen names, request types,
/// error kinds, the app version and times are written — no places, stops, lines or tickets.
/// The file stays on this device until the user shares it from Settings.
final class HangLog: @unchecked Sendable {
    static let shared = HangLog()

    /// Seconds without a reply from the main thread before a freeze is noted, then while it lasts.
    /// iOS ends an app that has been blocked for about 10 s, so the last note comes before that.
    static let marks: [TimeInterval] = [2, 5, 8]
    static let maxTrail = 40
    static let maxBytes = 400_000

    let fileURL: URL
    private let lock = NSLock()
    private var trail: [String] = []
    private var waitingSince: Date?
    private var lastTick: Date?
    private var notedMarks = 0
    private var timer: DispatchSourceTimer?
    private let queue = DispatchQueue(label: "io.github.publit.hanglog", qos: .utility)

    init(directory: URL? = nil) {
        let dir = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Diagnostics", isDirectory: true)
        fileURL = dir.appendingPathComponent("publit-freezes.txt")
    }

    var hasEntries: Bool {
        Self.io.sync { }
        return ((try? FileManager.default.attributesOfItem(atPath: fileURL.path)[.size] as? Int) ?? 0) > 0
    }

    func clear() {
        try? FileManager.default.removeItem(at: fileURL)
        lock.lock(); recentErrors.removeAll(); lock.unlock()
    }

    // MARK: - Other problems

    /// The same error again within this time only goes into the steps, not into a new entry.
    static let errorRepeatWindow: TimeInterval = 300
    private var recentErrors: [String: Date] = [:]
    private var sessionActive = false
    private var pendingSession: String?
    private var sessionWriteScheduled = false
    private var timerSuspended = false
    private static let sessionWriteDelay: TimeInterval = 2
    private var sessionURL: URL { fileURL.deletingLastPathComponent().appendingPathComponent("session.txt") }

    /// A failed request, an unreadable answer or another error worth a look. `context` is a fixed name
    /// such as "plan" or "refresh"; the error is reduced to its kind and code.
    func error(_ context: String, _ error: Error, at now: Date = Date()) {
        guard !Self.isCancellation(error) else { return }
        let detail = Self.describe(error)
        let key = context + "|" + detail
        lock.lock()
        let repeated = recentErrors[key].map { now.timeIntervalSince($0) < Self.errorRepeatWindow } ?? false
        if !repeated { recentErrors[key] = now }
        if recentErrors.count > 50 { recentErrors = recentErrors.filter { now.timeIntervalSince($0.value) < Self.errorRepeatWindow } }
        lock.unlock()
        note("error \(context): \(detail)", at: now)
        guard !repeated else { return }
        report("ERROR", "\(context): \(detail)", at: now)
    }

    /// An entry of `kind` (ERROR, CRASH, HANG, EXIT, MEMORY …) with the last steps.
    func report(_ kind: String, _ text: String, at now: Date = Date(), steps: [String]? = nil, stack: String = "") {
        lock.lock()
        let trailNow = steps ?? trail
        lock.unlock()
        append(Self.header(kind, now) + text + "\n" + Self.steps(trailNow) + stack)
    }

    /// The scene became active or went to the background. While active, the steps are kept on disk,
    /// so the next launch can tell that the app ended while it was open.
    func scene(active: Bool, at now: Date = Date()) {
        lock.lock()
        sessionActive = active
        // The watchdog sleeps while Publit is in the background: nothing to watch, and no wake-ups.
        if let timer, timerSuspended == active {
            if active { timer.resume() } else { timer.suspend() }
            timerSuspended = !active
        }
        lock.unlock()
        saveSession(at: now, immediately: true)
    }

    /// Called at launch: notes when the previous session ended while Publit was open.
    func checkPreviousSession(at now: Date = Date()) {
        guard let text = try? String(contentsOf: sessionURL, encoding: .utf8) else { return }
        try? FileManager.default.removeItem(at: sessionURL)
        var lines = text.components(separatedBy: "\n")
        guard lines.first == "active" else { return }
        lines.removeFirst()
        let build = lines.isEmpty ? "?" : lines.removeFirst()
        let seen = lines.isEmpty ? "?" : lines.removeFirst()
        let steps = lines.filter { !$0.isEmpty }
        report("EXIT", "The last session (build \(build)) ended while Publit was open, last seen \(seen): "
               + "a crash, or iOS stopped the app (frozen too long, or out of memory).", at: now, steps: steps)
    }

    /// Steps are written at most every two seconds; scene changes are written right away.
    private func saveSession(at now: Date, immediately: Bool = false) {
        lock.lock()
        let active = sessionActive, steps = trail
        pendingSession = ([active ? "active" : "background", AppInfo.build, Self.dateTime(now)] + steps)
            .joined(separator: "\n")
        let schedule = !immediately && !sessionWriteScheduled
        if schedule { sessionWriteScheduled = true }
        lock.unlock()
        if immediately {
            queue.async { self.writePendingSession() }
        } else if schedule {
            queue.asyncAfter(deadline: .now() + Self.sessionWriteDelay) {
                self.lock.lock(); self.sessionWriteScheduled = false; self.lock.unlock()
                self.writePendingSession()
            }
        }
    }

    /// Runs on `queue`.
    private func writePendingSession() {
        lock.lock()
        let text = pendingSession
        pendingSession = nil
        lock.unlock()
        guard let text else { return }
        let url = sessionURL
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? text.write(to: url, atomically: true, encoding: .utf8)
    }

    /// Writes pending session steps now and waits until they are on disk (tests).
    func flush() { queue.sync { writePendingSession() }; Self.io.sync { } }

    static func isCancellation(_ error: Error) -> Bool {
        if error is CancellationError { return true }
        if let u = error as? URLError, u.code == .cancelled { return true }
        return false
    }

    /// Kind and code, plus the field path of a decoding error ("legs[2].from.lat: missing").
    static func describe(_ error: Error) -> String {
        if let u = error as? URLError { return "URLError \(u.code.rawValue)" }
        if let d = error as? DecodingError {
            func path(_ c: DecodingError.Context) -> String {
                c.codingPath.map { $0.intValue.map { "[\($0)]" } ?? ".\($0.stringValue)" }.joined()
                    .trimmingCharacters(in: CharacterSet(charactersIn: "."))
            }
            switch d {
            case .keyNotFound(let k, let c): return "decoding: \(path(c)).\(k.stringValue) missing"
            case .valueNotFound(let t, let c): return "decoding: \(path(c)) null (\(t))"
            case .typeMismatch(let t, let c): return "decoding: \(path(c)) not \(t)"
            case .dataCorrupted(let c): return "decoding: \(path(c)) corrupted"
            @unknown default: return "decoding error"
            }
        }
        // The app's own errors carry a short message ("HTTP 503").
        if let l = error as? LocalizedError, let d = l.errorDescription, !d.isEmpty { return "\(type(of: error)): \(d)" }
        let ns = error as NSError
        return "\(type(of: error)) \(ns.domain) \(ns.code)"
    }

    // MARK: - Summary and report

    struct Summary: Equatable {
        var freezes = 0, errors = 0, crashes = 0, exits = 0, other = 0
        var last: Date?
        var total: Int { freezes + errors + crashes + exits + other }
    }

    var summary: Summary {
        Self.io.sync { }
        guard let text = try? String(contentsOf: fileURL, encoding: .utf8) else { return Summary() }
        var s = Summary()
        for line in text.split(separator: "\n") where line.hasPrefix("— ") {
            let parts = line.dropFirst(2).components(separatedBy: " · ")
            switch parts.first ?? "" {
            case "FREEZE": s.freezes += 1
            case "ERROR": s.errors += 1
            case "CRASH": s.crashes += 1
            case "EXIT": s.exits += 1
            default:
                // Entries from before build 21 start with the date.
                if parts.first?.first?.isNumber == true { s.freezes += 1 } else { s.other += 1 }
            }
            if parts.count > 1, let d = Self.parseDateTime(parts[parts.first?.first?.isNumber == true ? 0 : 1]) { s.last = d }
        }
        return s
    }

    /// One file to send: device and app, counts, the current steps, then every entry.
    func writeReport(at now: Date = Date()) -> URL {
        let url = fileURL.deletingLastPathComponent().appendingPathComponent("publit-diagnostics.txt")
        let s = summary  // also waits for pending entries
        lock.lock()
        let steps = trail
        lock.unlock()
        var out = "Publit diagnostics · \(Self.dateTime(now))\n"
        out += "App: Publit \(AppInfo.version) (\(AppInfo.build))\n"
        out += "Device: \(Self.device) · \(ProcessInfo.processInfo.operatingSystemVersionString)\n"
        out += "Language: \(Locale.preferredLanguages.prefix(3).joined(separator: ", ")) · time zone \(TimeZone.current.identifier)\n"
        out += "Memory: \(ProcessInfo.processInfo.physicalMemory / 1_048_576) MB · \(Self.powerState)\n"
        out += "Entries: \(s.freezes) freezes, \(s.errors) errors, \(s.crashes) crashes, \(s.exits) unexpected exits"
        out += s.other > 0 ? ", \(s.other) other\n" : "\n"
        out += "\nSteps until now:\n" + (steps.isEmpty ? "  (none)\n" : steps.map { "  \($0)\n" }.joined())
        out += "\n" + ((try? String(contentsOf: fileURL, encoding: .utf8)) ?? "(no entries)\n")
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? out.write(to: url, atomically: true, encoding: .utf8)
        return url
    }

    private static var powerState: String {
        #if canImport(Darwin)
        let p = ProcessInfo.processInfo
        let thermal = ["nominal", "fair", "serious", "critical"][min(p.thermalState.rawValue, 3)]
        return "low power \(p.isLowPowerModeEnabled ? "on" : "off") · thermal \(thermal)"
        #else
        return "power state unknown"
        #endif
    }

    private static func header(_ kind: String, _ date: Date) -> String {
        let os = ProcessInfo.processInfo.operatingSystemVersionString
        return "\n— \(kind) · \(dateTime(date)) · Publit \(AppInfo.version) (\(AppInfo.build)) · \(device) · \(os)\n"
    }

    private static func steps(_ trail: [String]) -> String {
        "Last steps:\n" + (trail.isEmpty ? "  (none)\n" : trail.map { "  \($0)\n" }.joined())
    }

    /// A step such as "open Route" or "tab plan". Callers pass fixed names only.
    func note(_ event: String, at now: Date = Date()) {
        lock.lock()
        trail.append("\(Self.clock(now)) \(event)")
        if trail.count > Self.maxTrail { trail.removeFirst(trail.count - Self.maxTrail) }
        let active = sessionActive
        lock.unlock()
        if active { saveSession(at: now) }
    }

    /// Starts the watchdog: every half second it asks the main thread for a reply.
    func start() {
        #if canImport(Darwin)
        MainThreadSampler.install()
        #endif
        checkPreviousSession()
        lock.lock(); defer { lock.unlock() }
        guard timer == nil else { return }
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + 1, repeating: 0.5, leeway: .milliseconds(100))
        t.setEventHandler { [weak self] in
            guard let self, self.tick(now: Date()) else { return }
            DispatchQueue.main.async { self.answered(now: Date()) }
        }
        timer = t
        t.resume()
    }

    /// One watchdog step. Returns true when a new question should be sent to the main thread.
    @discardableResult
    func tick(now: Date) -> Bool {
        lock.lock()
        // A long gap means the app was suspended, not frozen: start counting again.
        if let last = lastTick, now.timeIntervalSince(last) > 3, waitingSince != nil {
            waitingSince = now
            notedMarks = 0
        }
        lastTick = now
        guard let since = waitingSince else {
            waitingSince = now
            lock.unlock()
            return true
        }
        let blocked = now.timeIntervalSince(since)
        var entry: String?
        if notedMarks < Self.marks.count, blocked >= Self.marks[notedMarks] {
            let mark = Int(Self.marks[notedMarks])
            if notedMarks == 0 {
                entry = Self.header("FREEZE", since) + "No response for \(mark) s. " + Self.steps(trail)
            } else {
                entry = "Still no response after \(mark) s\n"
            }
            notedMarks += 1
        }
        lock.unlock()
        if let entry { append(entry + stack()) }
        return false
    }

    /// What the main thread is doing right now; repeated frames are folded.
    private func stack() -> String {
        #if canImport(Darwin)
        guard let lines = MainThreadSampler.sample(), !lines.isEmpty else { return "  (no call stack)\n" }
        var out = "Main thread:\n"
        var previous: String?, repeats = 0
        func flush() { if repeats > 0 { out += "      … same ×\(repeats)\n" }; repeats = 0 }
        for line in lines {
            if line == previous { repeats += 1; continue }
            flush()
            out += "    \(line)\n"
            previous = line
        }
        flush()
        return out
        #else
        return ""
        #endif
    }

    static var device: String {
        #if os(Android)
        return UIDevice.current.model
        #else
        var info = utsname()
        uname(&info)
        return withUnsafeBytes(of: &info.machine) { raw in
            String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self)
        }
        #endif
    }

    /// The main thread replied.
    func answered(now: Date) {
        lock.lock()
        let since = waitingSince, noted = notedMarks
        waitingSince = nil
        notedMarks = 0
        lock.unlock()
        if noted > 0, let since {
            append(String(format: "Responding again after %.1f s\n", now.timeIntervalSince(since)))
        }
    }

    /// Entries are written in order on their own queue: errors and "responding again" are reported
    /// from the main thread, and reading and rewriting up to 400 KB there would itself cause a hitch.
    private static let io = DispatchQueue(label: "io.github.publit.hanglog.io", qos: .utility)

    private func append(_ text: String) {
        Self.io.async { self.appendNow(text) }
    }

    /// Runs on `io`.
    private func appendNow(_ text: String) {
        let fm = FileManager.default
        try? fm.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        var data = (try? Data(contentsOf: fileURL)) ?? Data()
        data.append(Data(text.utf8))
        if data.count > Self.maxBytes {
            // Keep the newest half, starting at a whole line.
            var tail = data.suffix(Self.maxBytes / 2)
            if let nl = tail.firstIndex(of: 0x0A) { tail = tail[tail.index(after: nl)...] }
            data = Data(tail)
        }
        try? data.write(to: fileURL, options: .atomic)
    }

    private static func clock(_ date: Date) -> String { format(date, "HH:mm:ss") }
    private static func dateTime(_ date: Date) -> String { format(date, "yyyy-MM-dd HH:mm:ss") }
    private static func parseDateTime(_ s: String) -> Date? { posix("yyyy-MM-dd HH:mm:ss").date(from: s) }
    private static func format(_ date: Date, _ pattern: String) -> String { posix(pattern).string(from: date) }

    /// One formatter per pattern (a step is noted on every screen change; creating formatters is slow).
    private static let formatterLock = NSLock()
    private static var formatters: [String: DateFormatter] = [:]
    private static func posix(_ pattern: String) -> DateFormatter {
        formatterLock.lock(); defer { formatterLock.unlock() }
        if let f = formatters[pattern] { return f }
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = pattern
        formatters[pattern] = f
        return f
    }
}

#if canImport(SwiftUI)
import SwiftUI

extension View {
    /// Notes opening and closing this screen in the freeze log. Takes a fixed name only.
    func screenTrail(_ name: StaticString) -> some View {
        onAppear { HangLog.shared.note("open \(name)") }
            .onDisappear { HangLog.shared.note("close \(name)") }
    }
}
#endif

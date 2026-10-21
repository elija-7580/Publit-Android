#if canImport(Darwin)
import Darwin

/// The main thread's call stack while it is blocked, for the freeze log. A signal makes the main
/// thread write its own return addresses into a fixed buffer (nothing else happens in the
/// handler); names are looked up afterwards on the watchdog's queue. Only function names from the
/// app and iOS are written, no data.
enum MainThreadSampler {
    static let capacity: Int32 = 128
    static let frames = UnsafeMutablePointer<UnsafeMutableRawPointer?>.allocate(capacity: 128)
    static let count = UnsafeMutablePointer<Int32>.allocate(capacity: 1)
    private static var mainThread: pthread_t?

    /// Call once, on the main thread.
    static func install() {
        guard mainThread == nil else { return }
        _ = frames; count.pointee = -1
        mainThread = pthread_self()
        var action = sigaction()
        action.__sigaction_u.__sa_handler = samplerSignalHandler
        action.sa_flags = SA_RESTART
        action.sa_mask = sigset_t() // empty: no other signals blocked
        sigaction(SIGUSR2, &action, nil)
    }

    /// Stack lines, innermost first; nil when not installed, under a debugger (it would stop on
    /// the signal) or when the main thread did not record in time.
    static func sample() -> [String]? {
        guard let main = mainThread, !debuggerAttached else { return nil }
        count.pointee = -1
        guard pthread_kill(main, SIGUSR2) == 0 else { return nil }
        var waited = 0
        while count.pointee < 0, waited < 100 { usleep(2_000); waited += 2 }
        let n = count.pointee
        guard n > 2, let symbols = backtrace_symbols(frames, n) else { return nil }
        defer { free(symbols) }
        // Frame 0 is the handler, frame 1 the signal trampoline.
        return (2..<Int(n)).compactMap { i in symbols[i].map { tidy(String(cString: $0)) } }
    }

    /// "12  SwiftUI  0x1a2b3c  $s7SwiftUI… + 44" → "SwiftUI  <readable name> + 44".
    static func tidy(_ line: String) -> String {
        let parts = line.split(separator: " ", omittingEmptySubsequences: true)
        guard parts.count >= 4 else { return line }
        let module = String(parts[1])
        var symbol = parts[3...].joined(separator: " ")
        if let plus = symbol.range(of: " + ", options: .backwards) {
            let name = String(symbol[..<plus.lowerBound])
            symbol = demangle(name) + symbol[plus.lowerBound...]
        }
        return "\(module)  \(symbol)"
    }

    private typealias Demangle = @convention(c) (UnsafePointer<CChar>?, Int, UnsafeMutablePointer<CChar>?, UnsafeMutablePointer<Int>?, UInt32) -> UnsafeMutablePointer<CChar>?
    private static let swiftDemangle: Demangle? = {
        guard let sym = dlsym(UnsafeMutableRawPointer(bitPattern: -2), "swift_demangle") else { return nil }
        return unsafeBitCast(sym, to: Demangle.self)
    }()

    private static func demangle(_ name: String) -> String {
        guard name.hasPrefix("$s") || name.hasPrefix("_$s"), let swiftDemangle else { return name }
        return name.withCString { c in
            guard let out = swiftDemangle(c, strlen(c), nil, nil, 0) else { return name }
            defer { free(out) }
            return String(cString: out)
        }
    }

    private static var debuggerAttached: Bool {
        var info = kinfo_proc()
        var size = MemoryLayout<kinfo_proc>.stride
        var mib: [Int32] = [CTL_KERN, KERN_PROC, KERN_PROC_PID, getpid()]
        guard sysctl(&mib, 4, &info, &size, nil, 0) == 0 else { return false }
        return (info.kp_proc.p_flag & P_TRACED) != 0
    }
}

/// Runs on the main thread, interrupting whatever blocks it: records return addresses only.
private func samplerSignalHandler(_ signal: Int32) {
    MainThreadSampler.count.pointee = backtrace(MainThreadSampler.frames, MainThreadSampler.capacity)
}
#endif

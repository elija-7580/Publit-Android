import Foundation
import SkipFuse
import Observation

/// How long a response may be reused, and whether a copy is kept on disk for offline use.
struct CachePolicy: Sendable {
    /// Reuse without asking the server when younger than this (seconds). 0 = always ask.
    var maxAge: TimeInterval
    /// Keep a copy on disk so the screen still works without a connection.
    var persist: Bool
    /// Fixed slot instead of the URL, e.g. "nearby" (the GPS position changes on every request).
    var slot: String? = nil

    static let live = CachePolicy(maxAge: 20, persist: true)
    static let search = CachePolicy(maxAge: 24 * 3600, persist: true)
    static let noStore = CachePolicy(maxAge: 0, persist: false)
}

/// Responses kept in memory (instant back-navigation) and on disk in Caches (offline).
/// The system may delete Caches at any time; nothing here is the only copy of user data.
final class ResponseCache: @unchecked Sendable {
    struct Entry: @unchecked Sendable {
        let data: Data
        let date: Date
        let url: String
        /// The decoded answer, so going back to a screen does not decode the same JSON again.
        /// Immutable value types only (the response models).
        var decoded: Any? = nil

        /// Decoded models take about three times the JSON size in memory.
        var cost: Int { decoded == nil ? data.count : data.count * 3 }
    }

    private let lock = NSLock()
    private var memory: [String: Entry] = [:]
    private var memoryCost = 0
    /// When each key last went to disk, to space out offline copies of answers that change every few seconds.
    private var diskWrites: [String: Date] = [:]
    private var bytesSincePrune = 0
    private let directory: URL?
    static let maxMemoryEntries = 200
    /// Route and map responses are 100–600 KB each; keep memory bounded by size, not only by count.
    static let maxMemoryBytes = 24 * 1024 * 1024
    /// Disk copies are for offline use; the oldest go first above this size.
    static let maxDiskBytes = 60 * 1024 * 1024
    /// An offline copy of a live board (20 s fresh) does not need to be newer than this.
    static let minDiskInterval: TimeInterval = 120

    init(directory: URL? = ResponseCache.defaultDirectory) {
        self.directory = directory
        if let directory { try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true) }
    }

    static var defaultDirectory: URL? {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first?
            .appendingPathComponent("Publit/responses", isDirectory: true)
    }

    /// Stable short file name for a key (FNV-1a, 64 bit).
    static func fileName(_ key: String) -> String {
        var h: UInt64 = 0xcbf29ce484222325
        for b in key.utf8 { h ^= UInt64(b); h = h &* 0x100000001b3 }
        return String(h, radix: 16) + ".cache"
    }

    func fresh(_ key: String, maxAge: TimeInterval, now: Date = Date()) -> Entry? {
        guard maxAge > 0 else { return nil }
        lock.lock(); defer { lock.unlock() }
        guard let e = memory[key], now.timeIntervalSince(e.date) < maxAge else { return nil }
        return e
    }

    /// - Parameters:
    ///   - decoded: the answer already decoded from `data`, kept with it in memory.
    ///   - diskInterval: a disk copy written less than this ago is not replaced (0 = always write).
    func store(_ key: String, data: Data, url: String, persist: Bool, now: Date = Date(), decoded: Any? = nil,
               diskInterval: TimeInterval = 0) {
        var entry = Entry(data: data, date: now, url: url, decoded: decoded)
        lock.lock()
        if memory[key] == nil, memory.count >= Self.maxMemoryEntries {
            // Drop the oldest half; cheaper than tracking exact LRU order.
            let keep = memory.sorted { $0.value.date > $1.value.date }.prefix(Self.maxMemoryEntries / 2)
            memory = Dictionary(uniqueKeysWithValues: keep.map { ($0.key, $0.value) })
            memoryCost = memory.values.reduce(0) { $0 + $1.cost }
        }
        if entry.cost > Self.maxMemoryBytes / 2 { entry.decoded = nil }
        if let old = memory[key] { memoryCost -= old.cost }
        if entry.cost <= Self.maxMemoryBytes {
            memory[key] = entry
            memoryCost += entry.cost
        } else {
            memory[key] = nil
        }
        if memoryCost > Self.maxMemoryBytes {
            for (k, e) in memory.sorted(by: { $0.value.date < $1.value.date }) where memoryCost > Self.maxMemoryBytes / 2 && k != key {
                memory[k] = nil
                memoryCost -= e.cost
            }
        }
        // Offline copies of answers that change every few seconds are spaced out.
        var write = persist && directory != nil
        if write, let last = diskWrites[key], now.timeIntervalSince(last) < diskInterval { write = false }
        if write {
            diskWrites[key] = now
            if diskWrites.count > 400 { diskWrites = diskWrites.filter { now.timeIntervalSince($0.value) < Self.minDiskInterval } }
            bytesSincePrune += data.count
        }
        let prune = bytesSincePrune > Self.maxDiskBytes / 3
        if prune { bytesSincePrune = 0 }
        lock.unlock()
        guard write, let directory else { return }
        let header = "\(now.timeIntervalSince1970)\n\(url)\n"
        let file = directory.appendingPathComponent(Self.fileName(key))
        // The answer is already in memory; the offline copy does not have to delay it.
        Self.io.async {
            var blob = Data(header.utf8)
            blob.append(data)
            try? blob.write(to: file, options: .atomic)
            // A long session can write a lot; keep the folder within its size without waiting for the next launch.
            if prune { self.prune(olderThan: 7 * 24 * 3600) }
        }
    }

    /// Keeps a decoded answer next to the memory copy it came from.
    func remember(_ key: String, decoded: Any, for entry: Entry) {
        lock.lock(); defer { lock.unlock() }
        guard let current = memory[key], current.date == entry.date, current.url == entry.url, current.decoded == nil else { return }
        var updated = current
        updated.decoded = decoded
        guard updated.cost <= Self.maxMemoryBytes / 2 else { return }
        memoryCost += updated.cost - current.cost
        memory[key] = updated
    }

    /// Frees memory: everything on a memory warning; in the background all but the newest few MB,
    /// without decoded copies (a small app is less likely to be ended by iOS while suspended).
    func releaseMemory(keepingBytes: Int = 0) {
        lock.lock(); defer { lock.unlock() }
        guard keepingBytes > 0 else { memory.removeAll(); memoryCost = 0; return }
        var kept: [String: Entry] = [:]
        var total = 0
        for (k, e) in memory.sorted(by: { $0.value.date > $1.value.date }) {
            guard total + e.data.count <= keepingBytes else { break }
            var light = e
            light.decoded = nil
            kept[k] = light
            total += e.data.count
        }
        memory = kept
        memoryCost = total
    }

    var memoryBytes: Int { lock.lock(); defer { lock.unlock() }; return memoryCost }

    /// Disk copies are written in order, off the request path.
    private static let io = DispatchQueue(label: "io.github.publit.response-cache", qos: .utility)

    /// Waits for pending disk copies (tests, and before reading a key that is not in memory).
    static func flushWrites() { io.sync { } }

    /// Last known response, from memory or disk, however old.
    func stale(_ key: String) -> Entry? {
        lock.lock()
        let m = memory[key]
        lock.unlock()
        if let m { return m }
        guard let directory else { return nil }
        Self.flushWrites()
        guard let blob = try? Data(contentsOf: directory.appendingPathComponent(Self.fileName(key))) else { return nil }
        return Self.decode(blob)
    }

    static func decode(_ blob: Data) -> Entry? {
        let newline = UInt8(ascii: "\n")
        guard let first = blob.firstIndex(of: newline),
              let second = blob[blob.index(after: first)...].firstIndex(of: newline),
              let ts = Double(String(decoding: blob[blob.startIndex..<first], as: UTF8.self)) else { return nil }
        let url = String(decoding: blob[blob.index(after: first)..<second], as: UTF8.self)
        return Entry(data: Data(blob[blob.index(after: second)...]), date: Date(timeIntervalSince1970: ts), url: url)
    }

    /// Removes disk copies older than `age` seconds (called at launch).
    func prune(olderThan age: TimeInterval, now: Date = Date()) {
        guard let directory,
              let files = try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.contentModificationDateKey])
        else { return }
        var kept: [(url: URL, date: Date, size: Int)] = []
        for f in files {
            let values = try? f.resourceValues(forKeys: [.contentModificationDateKey, .fileSizeKey])
            let date = values?.contentModificationDate ?? .distantPast
            if now.timeIntervalSince(date) > age { try? FileManager.default.removeItem(at: f) }
            else { kept.append((f, date, values?.fileSize ?? 0)) }
        }
        var total = kept.reduce(0) { $0 + $1.size }
        for f in kept.sorted(by: { $0.date < $1.date }) where total > Self.maxDiskBytes {
            try? FileManager.default.removeItem(at: f.url)
            total -= f.size
        }
    }
}

/// Whether the last request reached the server. Screens show a small "Offline" note while
/// they display saved responses.
@MainActor
@Observable
final class Connectivity {
    static let shared = Connectivity()
    /// Set while screens show saved data; the time that data was fetched.
    private(set) var offlineDataFrom: Date?
    var isOffline: Bool { offlineDataFrom != nil }

    func reachedServer() { if offlineDataFrom != nil { offlineDataFrom = nil } }
    func servedOffline(from date: Date) { offlineDataFrom = date }
}

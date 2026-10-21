import Foundation

/// Small JSON file persistence in Application Support (kept out of iCloud/iTunes backups is not needed:
/// these are the user's own trips and tickets, so they *should* be backed up with the device).
struct JSONFile<Value: Codable>: Sendable {
    let url: URL

    init(name: String, directory: URL? = nil) {
        let dir = directory ?? JSONFile.defaultDirectory
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        url = dir.appendingPathComponent(name)
    }

    static var defaultDirectory: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return base.appendingPathComponent("Publit", isDirectory: true)
    }

    static var encoder: JSONEncoder {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .custom { date, enc in
            var c = enc.singleValueContainer()
            try c.encode(DateParsing.format(date))
        }
        return e
    }

    func load() -> Value? {
        JSONPersistence.flush()
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? TransitousClient.makeDecoder().decode(Value.self, from: data)
    }

    /// Immutable snapshots are encoded and written in order, away from the UI thread.
    func saveInBackground(_ value: Value, completion: (@Sendable (Result<Void, Error>) -> Void)? = nil) where Value: Sendable {
        JSONPersistence.queue.async {
            do { try saveChecked(value); completion?(.success(())) }
            catch { completion?(.failure(error)) }
        }
    }

    func save(_ value: Value) { try? saveChecked(value) }

    /// Critical imports use this throwing path; a failed write must never report success.
    func saveChecked(_ value: Value) throws {
        let trace = PerformanceTrace.begin("Save local data")
        defer { PerformanceTrace.end(trace, "Save local data") }
        let data = try Self.encoder.encode(value)
        try data.write(to: url, options: .atomic)
    }
}

/// Reads on launch/reload wait for pending snapshots. Ordinary writes never block the UI.
enum JSONPersistence {
    static let queue = DispatchQueue(label: "io.github.publit.persistence", qos: .utility)
    static func flush() { queue.sync { } }
}

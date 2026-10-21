import Foundation

/// "Export my data" in Settings: everything Publit keeps on the device (saved places and lines,
/// trips, search history, tickets without their images) in one readable JSON file.
enum DataExport {
    static let defaultsKeys = ["places": "publit.favorites.v1", "lines": "publit.lines.v1"]
    static let files = ["trips": "trips.json", "searchHistory": "search-history.json",
                        "tickets": "tickets.json", "ticketApps": "ticket-apps.json", "passes": "passes.json"]

    /// The combined JSON; sections without data are left out.
    static func json(defaults: UserDefaults = .standard, directory: URL = JSONFile<[String]>.defaultDirectory,
                     now: Date = Date()) -> Data {
        JSONPersistence.flush()
        var root: [String: Any] = ["app": "Publit", "format": 1, "exported": ISO8601DateFormatter().string(from: now)]
        for (name, key) in defaultsKeys {
            if let data = defaults.data(forKey: key), let value = try? JSONSerialization.jsonObject(with: data) {
                root[name] = value
            }
        }
        for (name, file) in files {
            let url = directory.appendingPathComponent(file)
            if let data = try? Data(contentsOf: url), let value = try? JSONSerialization.jsonObject(with: data) {
                root[name] = value
            }
        }
        return (try? JSONSerialization.data(withJSONObject: root, options: [.prettyPrinted, .sortedKeys])) ?? Data("{}".utf8)
    }

    /// Writes the export to a temporary file named after the day, for the share sheet.
    static func write(defaults: UserDefaults = .standard, directory: URL = JSONFile<[String]>.defaultDirectory,
                      now: Date = Date()) -> URL? {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("Publit-\(f.string(from: now)).json")
        do {
            try json(defaults: defaults, directory: directory, now: now).write(to: url, options: .atomic)
            return url
        } catch { return nil }
    }
}

/// The tab Publit opens with (Settings → Start screen).
enum StartTab: String, CaseIterable, Identifiable {
    case nearby, plan
    static let defaultsKey = "startTab"
    var id: String { rawValue }
}

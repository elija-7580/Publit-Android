import Foundation
#if canImport(UserNotifications)
import UserNotifications
#endif
#if canImport(BackgroundTasks)
import BackgroundTasks
#endif

/// Notifications about alerts and cancellations on saved lines. Checked when the app opens and,
/// when iOS allows it, in the background (at most every 30 minutes; iOS decides when).
enum LineAlertMonitor {
    static let taskId = "io.github.elija7580.publit.lines"
    static let enabledKey = "lineAlerts"
    static let lastCheckKey = "lineAlerts.lastCheck"
    /// Requests per check stay few: one per stop, at most this many stops.
    static let maxStops = 8

    static var isEnabled: Bool { UserDefaults.standard.bool(forKey: enabledKey) }

    /// Saved lines, read straight from storage (the background task has no view or store).
    static func savedLines(_ defaults: UserDefaults = .standard) -> [SavedLine] {
        defaults.data(forKey: "publit.lines.v1").flatMap { try? JSONDecoder().decode([SavedLine].self, from: $0) } ?? []
    }

    static func requestPermission() async -> Bool {
        (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])) ?? false
    }

    /// Checks saved lines and posts one notification per new disruption. Returns how many were posted.
    @discardableResult
    static func check(minInterval: TimeInterval = 15 * 60, now: Date = .now) async -> Int {
        let defaults = UserDefaults.standard
        guard isEnabled else { return 0 }
        let last = defaults.double(forKey: lastCheckKey)
        guard now.timeIntervalSince1970 - last >= minInterval else { return 0 }
        defaults.set(now.timeIntervalSince1970, forKey: lastCheckKey)
        let lines = savedLines(defaults)
        guard !lines.isEmpty else { return 0 }

        var found: [LineDisruption] = []
        var stopOf: [String: SavedLine] = [:]
        let byStop = Dictionary(grouping: lines, by: \.stopId)
        for (stopId, saved) in byStop.prefix(maxStops) {
            guard let res = try? await TransitousClient.shared.departures(stopId: stopId, count: 60, radius: 150) else { continue }
            for line in saved {
                let list = LineDisruption.detect(line, in: res.stopTimes, now: now)
                for d in list { stopOf[d.fingerprint] = line }
                found += list
            }
        }
        var ledger = DisruptionLedger.load(defaults)
        let fresh = ledger.takeNew(found, now: now)
        ledger.save(defaults)
        let center = UNUserNotificationCenter.current()
        for d in fresh.prefix(5) {
            let content = UNMutableNotificationContent()
            content.title = d.title
            content.body = d.body
            content.sound = .default
            content.threadIdentifier = "lineAlerts"
            if let line = stopOf[d.fingerprint] {
                content.userInfo = ["url": DeepLink.stop(id: line.stopId, name: line.stopName, lat: line.lat, lon: line.lon).url.absoluteString]
            }
            try? await center.add(UNNotificationRequest(identifier: "line|\(d.fingerprint)", content: content, trigger: nil))
        }
        return fresh.count
    }

    /// Asks iOS for the next background check.
    static func scheduleBackgroundCheck() {
        #if canImport(BackgroundTasks) && os(iOS)
        guard isEnabled, !savedLines().isEmpty else { return }
        let request = BGAppRefreshTaskRequest(identifier: taskId)
        request.earliestBeginDate = Date().addingTimeInterval(30 * 60)
        try? BGTaskScheduler.shared.submit(request)
        #endif
    }
}

/// After an arrival time was shared: a notification when it moves by 3 minutes or more,
/// with the new text ready to send again from the trip.
enum ArrivalUpdates {
    private static let key = "sharedArrivals"

    static func recordShare(tripKey: String, arrival: Date) {
        var map = UserDefaults.standard.dictionary(forKey: key) as? [String: Double] ?? [:]
        let now = Date().timeIntervalSince1970
        map = map.filter { now - $0.value < 24 * 3600 }
        map[tripKey] = arrival.timeIntervalSince1970
        UserDefaults.standard.set(map, forKey: key)
    }

    /// Call after a live refresh of a saved trip.
    static func follow(_ trip: SavedTrip) async {
        var map = UserDefaults.standard.dictionary(forKey: key) as? [String: Double] ?? [:]
        guard let shared = map[trip.id] else { return }
        let arrival = trip.itinerary.endTime
        guard arrival > .now, ArrivalShare.shifted(sharedArrival: Date(timeIntervalSince1970: shared), current: arrival) else { return }
        map[trip.id] = arrival.timeIntervalSince1970
        UserDefaults.standard.set(map, forKey: key)
        let content = UNMutableNotificationContent()
        content.title = L10n.f("New arrival time: %@", Fmt.time(arrival, tz: trip.itinerary.legs.last?.to.tz))
        content.body = L10n.f("You shared your arrival at %@. Tap to send the new time.", trip.toName)
        content.sound = .default
        content.userInfo = ["url": trip.deepLink.absoluteString]
        try? await UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: "arrival|\(trip.id)", content: content, trigger: nil))
    }
}

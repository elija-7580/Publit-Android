import Foundation
#if canImport(UserNotifications)
import UserNotifications
#endif

/// Local notification shortly before the vehicle reaches the stop where you get off.
/// Nothing leaves the device.
enum GetOffReminder {
    /// Lead time before the (real-time) arrival.
    static let leadTime: TimeInterval = 120

    static func identifier(for leg: Leg) -> String {
        "getoff|\(leg.tripId ?? leg.lineName)|\(leg.to.name)|\(Int(leg.scheduledEndTime.timeIntervalSince1970))"
    }

    enum Result: Equatable { case scheduled(Date), tooLate, denied }

    static func schedule(for leg: Leg) async -> Result {
        let fireDate = max(leg.endTime.addingTimeInterval(-leadTime), leg.startTime)
        guard fireDate > Date().addingTimeInterval(5) else { return .tooLate }
        let center = UNUserNotificationCenter.current()
        let granted = (try? await center.requestAuthorization(options: [.alert, .sound])) ?? false
        guard granted else { return .denied }

        let content = UNMutableNotificationContent()
        content.title = L10n.t("Get off soon")
        content.body = L10n.f("%@ reaches %@ at %@.", leg.lineName, leg.to.name, Fmt.time(leg.endTime, tz: leg.to.tz))
        content.sound = .default
        content.interruptionLevel = .active

        let comps = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second], from: fireDate)
        let request = UNNotificationRequest(identifier: identifier(for: leg), content: content,
                                            trigger: UNCalendarNotificationTrigger(dateMatching: comps, repeats: false))
        do {
            try await center.add(request)
            return .scheduled(fireDate)
        } catch {
            return .denied
        }
    }

    static func cancel(for leg: Leg) {
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [identifier(for: leg)])
    }

    /// A started trip: a reminder for every ride, only when notifications are already allowed
    /// (starting a trip does not ask; "Remind me to get off" does).
    static func scheduleAll(_ it: Itinerary) async {
        let status = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
        guard status == .authorized || status == .provisional || status == .ephemeral else { return }
        for leg in it.transitLegs where leg.endTime > Date().addingTimeInterval(GetOffReminder.leadTime + 5) {
            _ = await schedule(for: leg)
        }
    }

    static func cancelAll(_ it: Itinerary) {
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: it.transitLegs.map(identifier(for:)))
    }

    /// A live refresh moved an arrival: a pending reminder moves with it.
    static func follow(from old: Itinerary, to new: Itinerary) async {
        guard old.legs.count == new.legs.count else { return }
        for (a, b) in zip(old.legs, new.legs) where b.isTransit && abs(b.endTime.timeIntervalSince(a.endTime)) >= 60 {
            if identifier(for: a) != identifier(for: b) { continue }
            if await isScheduled(a) { _ = await schedule(for: b) }
        }
    }

    static func isScheduled(_ leg: Leg) async -> Bool {
        let pending = await UNUserNotificationCenter.current().pendingNotificationRequests()
        return pending.contains { $0.identifier == identifier(for: leg) }
    }
}

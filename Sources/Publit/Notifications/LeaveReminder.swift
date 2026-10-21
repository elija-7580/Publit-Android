import Foundation
#if canImport(UserNotifications)
import UserNotifications
#endif

/// "Time to leave" notification for a saved trip, 5 minutes before the walk to the first stop starts.
/// Tapping it opens the trip; the Live Activity takes over from there.
enum LeaveReminder {
    static func identifier(_ tripId: String) -> String { "leave|\(tripId)" }
    static func followIdentifier(_ tripId: String) -> String { "follow|\(tripId)" }

    enum Result: Equatable { case scheduled(Date), tooLate, denied }

    static func schedule(_ trip: SavedTrip) async -> Result {
        let fire = TripStatus.reminderTime(trip.itinerary)
        guard trip.startTime > Date().addingTimeInterval(30) else { return .tooLate }
        let center = UNUserNotificationCenter.current()
        let granted = (try? await center.requestAuthorization(options: [.alert, .sound])) ?? false
        guard granted else { return .denied }

        let status = TripStatus.at(fire, trip.itinerary)
        let content = UNMutableNotificationContent()
        content.title = L10n.f("Leave at %@ for %@", Fmt.time(trip.startTime, tz: trip.itinerary.legs.first?.from.tz), trip.toName)
        content.body = status.detail
        content.sound = .default
        content.interruptionLevel = .active
        content.userInfo = ["url": trip.deepLink.absoluteString]
        let date = max(fire, Date().addingTimeInterval(5))
        try? await center.add(request(identifier(trip.id), content, at: date))

        // Live Activities can only start from the app and last 8 hours: for trips further ahead,
        // a second notification an hour before leaving opens the app, which then starts it.
        if trip.startTime.timeIntervalSinceNow > 8 * 3600 {
            let follow = UNMutableNotificationContent()
            follow.title = L10n.f("%@ in 1 hour", trip.title)
            follow.body = L10n.t("Open to follow it live on the lock screen.")
            follow.userInfo = ["url": trip.deepLink.absoluteString]
            try? await center.add(request(followIdentifier(trip.id), follow, at: trip.startTime.addingTimeInterval(-3600)))
        }
        return .scheduled(date)
    }

    /// Moves a pending reminder when live times shifted the departure (delay, or an earlier train).
    /// Nothing happens when no reminder is pending, e.g. it already fired or was switched off.
    static func follow(_ old: SavedTrip, to new: SavedTrip) async {
        guard TripStatus.reminderMoved(from: old.itinerary, to: new.itinerary), await isScheduled(new.id) else { return }
        _ = await schedule(new)
    }

    static func cancel(_ tripId: String) {
        UNUserNotificationCenter.current().removePendingNotificationRequests(
            withIdentifiers: [identifier(tripId), followIdentifier(tripId)])
    }

    static func isScheduled(_ tripId: String) async -> Bool {
        let pending = await UNUserNotificationCenter.current().pendingNotificationRequests()
        return pending.contains { $0.identifier == identifier(tripId) }
    }

    private static func request(_ id: String, _ content: UNNotificationContent, at date: Date) -> UNNotificationRequest {
        let comps = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        return UNNotificationRequest(identifier: id, content: content,
                                     trigger: UNCalendarNotificationTrigger(dateMatching: comps, repeats: false))
    }
}

#if os(Android)
/// Android: notification taps arrive as intents (Droid.kt → DroidEvents.openURL); this keeps the
/// same `open` hook as on iOS.
final class NotificationRouter: @unchecked Sendable {
    static let shared = NotificationRouter()
    var open: (@MainActor (URL) -> Void)?
}
#else
/// Opens `publit://` links attached to notifications and shows banners while the app is open.
final class NotificationRouter: NSObject, UNUserNotificationCenterDelegate, @unchecked Sendable {
    static let shared = NotificationRouter()
    var open: (@MainActor (URL) -> Void)?

    // Completion-handler versions on purpose: with the async ones, iOS finished the tap on a
    // background thread and UIKit aborted the app (crash when opening a reminder).
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        DispatchQueue.main.async { completionHandler([.banner, .sound]) }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let url = (response.notification.request.content.userInfo["url"] as? String).flatMap(URL.init(string:))
        DispatchQueue.main.async {
            if let url { MainActor.assumeIsolated { self.open?(url) } }
            completionHandler()
        }
    }
}
#endif

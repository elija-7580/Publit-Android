import Foundation
#if canImport(UserNotifications)
import UserNotifications
#endif

/// A notification when a live refresh of a followed trip brings a new platform or a cancelled ride.
/// Only when notifications are already allowed; nothing leaves the device.
enum TripChangeAlert {
    static func follow(_ tripId: String, from old: Itinerary, to new: Itinerary) async {
        let changes = TripChanges.between(old, new)
        guard !changes.isEmpty else { return }
        let center = UNUserNotificationCenter.current()
        let status = await center.notificationSettings().authorizationStatus
        guard status == .authorized || status == .provisional || status == .ephemeral else { return }
        for change in changes {
            let content = UNMutableNotificationContent()
            let id: String
            switch change {
            case .platform(let i, let line, let c):
                content.title = L10n.t("Platform changed")
                content.body = L10n.f("%@ now leaves from platform %@ (instead of %@).", line, c.now, c.scheduled)
                id = "change|\(tripId)|\(i)|pl|\(c.now)"
            case .cancelled(let i, let line, let stop):
                content.title = L10n.t("Connection cancelled")
                content.body = L10n.f("%@ from %@ does not run.", line, stop)
                id = "change|\(tripId)|\(i)|x"
            }
            content.sound = .default
            content.interruptionLevel = .active
            content.userInfo = ["url": DeepLink.trip(tripId).url.absoluteString]
            try? await center.add(UNNotificationRequest(identifier: id, content: content, trigger: nil))
        }
    }
}

import Foundation
#if canImport(UserNotifications)
import UserNotifications
#endif

/// A change on the running trip has become unlikely: a notification right away, with the backup.
/// Only when notifications are already allowed; this never asks.
enum ChangeRiskNotifier {
    static func notify(_ change: SmartRouting.Change, message: String) {
        let id = "changerisk|\(change.stopName)|\(change.outgoing.tripId ?? change.outgoing.lineName)"
        Task {
            let center = UNUserNotificationCenter.current()
            let settings = await center.notificationSettings()
            guard settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional else { return }
            let content = UNMutableNotificationContent()
            content.title = L10n.t("Change at risk")
            content.body = message
            content.sound = .default
            content.interruptionLevel = .active
            try? await center.add(UNNotificationRequest(identifier: id, content: content, trigger: nil))
        }
    }
}

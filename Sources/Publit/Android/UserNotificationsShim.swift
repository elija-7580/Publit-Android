#if os(Android)
import Foundation

// Android: the part of UserNotifications Publit uses (time-based local notifications with a
// publit:// link), posted by Droid.kt with AlarmManager + NotificationManager. The reminder code
// (LeaveReminder, GetOffReminder, TripChangeAlert, LineAlertMonitor …) runs unchanged on top of it.

struct UNAuthorizationOptions: OptionSet, Sendable {
    let rawValue: Int
    static let alert = UNAuthorizationOptions(rawValue: 1)
    static let sound = UNAuthorizationOptions(rawValue: 2)
    static let badge = UNAuthorizationOptions(rawValue: 4)
}

enum UNAuthorizationStatus: Int, Sendable { case notDetermined, denied, authorized, provisional, ephemeral }

enum UNNotificationInterruptionLevel: Int, Sendable { case passive, active, timeSensitive, critical }

struct UNNotificationSound: Sendable {
    static let `default` = UNNotificationSound()
    static let defaultCritical = UNNotificationSound()
}

struct UNNotificationSettings: Sendable {
    let authorizationStatus: UNAuthorizationStatus
}

class UNNotificationContent: @unchecked Sendable {
    var title = ""
    var subtitle = ""
    var body = ""
    var sound: UNNotificationSound?
    var interruptionLevel: UNNotificationInterruptionLevel = .active
    var userInfo: [AnyHashable: Any] = [:]
    var threadIdentifier = ""
    var relevanceScore = 0.0
}

final class UNMutableNotificationContent: UNNotificationContent, @unchecked Sendable {}

class UNNotificationTrigger: @unchecked Sendable {
    var fireDate: Date? { nil }
}

final class UNCalendarNotificationTrigger: UNNotificationTrigger, @unchecked Sendable {
    let dateComponents: DateComponents
    let repeats: Bool
    init(dateMatching: DateComponents, repeats: Bool) {
        dateComponents = dateMatching
        self.repeats = repeats
    }
    override var fireDate: Date? { Calendar.current.date(from: dateComponents) }
    func nextTriggerDate() -> Date? { fireDate }
}

final class UNTimeIntervalNotificationTrigger: UNNotificationTrigger, @unchecked Sendable {
    let timeInterval: TimeInterval
    let repeats: Bool
    private let created = Date()
    init(timeInterval: TimeInterval, repeats: Bool) {
        self.timeInterval = timeInterval
        self.repeats = repeats
    }
    override var fireDate: Date? { created.addingTimeInterval(timeInterval) }
    func nextTriggerDate() -> Date? { fireDate }
}

final class UNNotificationRequest: @unchecked Sendable {
    let identifier: String
    let content: UNNotificationContent
    let trigger: UNNotificationTrigger?
    init(identifier: String, content: UNNotificationContent, trigger: UNNotificationTrigger?) {
        self.identifier = identifier
        self.content = content
        self.trigger = trigger
    }
}

final class UNUserNotificationCenter: @unchecked Sendable {
    private static let instance = UNUserNotificationCenter()
    static func current() -> UNUserNotificationCenter { instance }

    func requestAuthorization(options: UNAuthorizationOptions = []) async throws -> Bool {
        if Droid.bool("notificationsAllowed") { return true }
        let answer = await DroidRequests.shared.run { id in Droid.call("requestNotificationPermission", id) }
        return answer == "true"
    }

    func notificationSettings() async -> UNNotificationSettings {
        UNNotificationSettings(authorizationStatus: Droid.bool("notificationsAllowed") ? .authorized
                               : (Droid.bool("notificationsAsked") ? .denied : .notDetermined))
    }

    func add(_ request: UNNotificationRequest) async throws {
        let c = request.content
        let fire = request.trigger?.fireDate ?? Date()
        let url = c.userInfo["url"] as? String ?? ""
        Droid.call("scheduleNotification", request.identifier, c.title, c.body, url,
                   fire.timeIntervalSince1970 * 1000, c.interruptionLevel == .timeSensitive || c.interruptionLevel == .critical,
                   c.sound != nil)
    }

    func removePendingNotificationRequests(withIdentifiers ids: [String]) {
        for id in ids { Droid.call("cancelNotification", id) }
    }

    func removeDeliveredNotifications(withIdentifiers ids: [String]) {
        for id in ids { Droid.call("removeDeliveredNotification", id) }
    }

    func removeAllPendingNotificationRequests() {
        Droid.call("cancelAllNotifications")
    }

    func pendingNotificationRequests() async -> [UNNotificationRequest] {
        let ids = Droid.string("pendingNotificationIds")?.split(separator: "\n").map(String.init) ?? []
        return ids.map { UNNotificationRequest(identifier: $0, content: UNNotificationContent(), trigger: nil) }
    }
}
#endif

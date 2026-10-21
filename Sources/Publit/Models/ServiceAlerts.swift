import Foundation

struct AlertPeriod: Codable, Hashable, Sendable {
    let start: Date?
    let end: Date?
    func contains(_ date: Date) -> Bool { (start == nil || start! <= date) && (end == nil || date < end!) }
    func overlaps(_ first: Date, _ last: Date) -> Bool { (start == nil || start! <= last) && (end == nil || first < end!) }
}

extension Alert {
    var priority: Int { severityLevel == "SEVERE" || effect == "NO_SERVICE" ? 0 : severityLevel == "WARNING" ? 1 : 2 }
    var isReplacementInfo: Bool {
        let text = (headerText + " " + descriptionText).lowercased()
        return ["schienenersatz", "ersatzverkehr", "replacement bus", "rail replacement"].contains { text.contains($0) }
    }
    var kind: String {
        if isReplacementInfo { return L10n.t("Replacement service information") }
        switch effect {
        case "NO_SERVICE": return L10n.t("No service")
        case "DETOUR", "STOP_MOVED": return L10n.t("Diversion or stop change")
        case "SIGNIFICANT_DELAYS": return L10n.t("Major delays")
        case "REDUCED_SERVICE", "MODIFIED_SERVICE": return L10n.t("Service changes")
        default: return L10n.t("Service alert")
        }
    }
    func visible(now: Date = .now, from: Date? = nil, to: Date? = nil) -> Bool {
        if let periods = communicationPeriod, !periods.isEmpty, !periods.contains(where: { $0.contains(now) }) { return false }
        if let periods = impactPeriod, !periods.isEmpty,
           !periods.contains(where: { $0.overlaps(from ?? now, to ?? from ?? now) }) { return false }
        return true
    }
    static func relevant(_ alerts: [Alert], now: Date = .now, from: Date? = nil, to: Date? = nil) -> [Alert] {
        Array(Set(alerts.filter { $0.visible(now: now, from: from, to: to) }))
            // A fixed order: a Set's order changes between calls, and a list whose rows reorder
            // on every redraw keeps updating.
            .sorted {
                if $0.priority != $1.priority { return $0.priority < $1.priority }
                if $0.headerText != $1.headerText { return $0.headerText < $1.headerText }
                return $0.descriptionText < $1.descriptionText
            }
    }
}

extension Leg {
    var serviceAlerts: [Alert] {
        Alert.relevant((alerts ?? []) + from.alertsList + to.alertsList + (intermediateStops ?? []).flatMap(\.alertsList),
                       from: startTime, to: endTime)
    }
    var isReplacementBus: Bool {
        guard mode == "BUS" || mode == "COACH" else { return false }
        let name = (routeShortName ?? "").uppercased()
        return name == "SEV" || name.hasPrefix("SEV ") || (displayName ?? "").lowercased().contains("schienenersatz")
    }
}

extension StopTime {
    var serviceAlerts: [Alert] { Alert.relevant(place.alertsList, from: departureTime) }
    var isReplacementBus: Bool {
        guard mode == "BUS" || mode == "COACH" else { return false }
        let name = (routeShortName ?? "").uppercased()
        return name == "SEV" || name.hasPrefix("SEV ") || (displayName ?? "").lowercased().contains("schienenersatz")
    }
}

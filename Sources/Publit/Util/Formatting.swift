import Foundation

enum Fmt {
    static func duration(seconds: Int) -> String {
        let minutes = max(1, Int((Double(seconds) / 60).rounded()))
        if minutes < 60 { return L10n.f("%lld min", minutes) }
        let h = minutes / 60, m = minutes % 60
        return m == 0 ? L10n.f("%lld h", h) : L10n.f("%lld h %lld min", h, m)
    }

    static func distance(meters: Double) -> String {
        // 999.6 m is "1.0 km", not "1000 m".
        if meters.rounded() < 1000 { return "\(Int(max(0, meters).rounded())) m" }
        numberLock.lock(); defer { numberLock.unlock() }
        let locale = Locale.current
        let f: NumberFormatter
        if let cached = numberFormatters[locale.identifier] { f = cached }
        else {
            f = NumberFormatter()
            f.locale = locale
            f.minimumFractionDigits = 1
            f.maximumFractionDigits = 1
            if numberFormatters.count >= 8 { numberFormatters.removeAll() }
            numberFormatters[locale.identifier] = f
        }
        return (f.string(from: NSNumber(value: meters / 1000)) ?? String(format: "%.1f", meters / 1000)) + " km"
    }

    static func changes(_ n: Int) -> String {
        n == 0 ? L10n.t("Direct") : n == 1 ? L10n.t("1 change") : L10n.f("%lld changes", n)
    }

    static func stops(_ n: Int) -> String {
        n == 0 ? L10n.t("No stops") : n == 1 ? L10n.t("1 stop") : L10n.f("%lld stops", n)
    }

    private static let numberLock = NSLock()
    private static var numberFormatters: [String: NumberFormatter] = [:]
    private static let lock = NSLock()
    private static var formatters: [String: DateFormatter] = [:]

    /// Keyed by the zone's name as sent by the server: no TimeZone lookup per row and redraw.
    private static func formatter(zoneID: String?) -> DateFormatter {
        lock.lock(); defer { lock.unlock() }
        let locale = Locale.current
        let key = locale.identifier + "|" + (zoneID ?? "")
        // The device zone can change while the app runs (travelling); stop zones cannot.
        if let f = formatters[key], zoneID != nil || f.timeZone == TimeZone.current { return f }
        let f = DateFormatter()
        f.locale = locale
        f.timeZone = timeZone(zoneID)
        f.setLocalizedDateFormatFromTemplate("jmm")
        if formatters.count >= 32 { formatters.removeAll() }
        formatters[key] = f
        return f
    }

    /// Clock time in the stop's own time zone, so a trip in Istanbul shows Istanbul time
    /// even when the phone is still set to Vienna.
    static func time(_ date: Date, tz: String? = nil) -> String {
        formatter(zoneID: tz).string(from: date)
    }

    static func timeZone(_ id: String?) -> TimeZone {
        id.flatMap(TimeZone.init(identifier:)) ?? .current
    }

    /// Non-nil when the place's time zone differs from the device's at `date`.
    static func foreignZoneNote(tz: String?, at date: Date, device: TimeZone = .current) -> String? {
        guard let id = tz, let zone = TimeZone(identifier: id),
              zone.secondsFromGMT(for: date) != device.secondsFromGMT(for: date) else { return nil }
        let offset = zone.secondsFromGMT(for: date) / 60
        let sign = offset >= 0 ? "+" : "−"
        let h = abs(offset) / 60, m = abs(offset) % 60
        let gmt = m == 0 ? "GMT\(sign)\(h)" : String(format: "GMT\(sign)%d:%02d", h, m)
        let city = id.split(separator: "/").last.map { $0.replacingOccurrences(of: "_", with: " ") } ?? id
        return L10n.f("Local time %@ (%@)", city, gmt)
    }
}

#if canImport(Darwin)
extension Fmt {
    /// "5 minutes ago" to the minute; under a minute "now". A plain string instead of
    /// `Text(date, style: .relative)`, which redraws itself every second.
    static func ago(_ date: Date, now: Date = .now, locale: Locale = .current) -> String {
        let f = RelativeDateTimeFormatter()
        f.locale = locale
        f.dateTimeStyle = .named
        f.unitsStyle = .full
        let seconds = now.timeIntervalSince(date)
        guard seconds >= 60 else { return f.localizedString(fromTimeInterval: 0) }
        let rounded = now.addingTimeInterval(-(seconds / 60).rounded(.down) * 60)
        return f.localizedString(for: rounded, relativeTo: now)
    }
}
#endif

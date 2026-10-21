import Foundation
import SkipFuse

/// Kotlin → Swift work without a screen (Android): the home screen widget and its stop picker.
/// Kotlin calls `DroidJobs.shared.…` (suspend functions for the async ones); results are JSON.
/* SKIP @bridge */public final class DroidJobs: @unchecked Sendable {
    /* SKIP @bridge */public static let shared = DroidJobs()
    private init() {}

    /// The next departures at a stop for the widget (the iOS `DeparturesProvider`).
    /* SKIP @bridge */public func widgetDepartures(_ stopId: String) async -> String {
        #if os(Android)
        return await AndroidWidgetData.departures(stopId)
        #else
        return "{}"
        #endif
    }

    /// Stops for the widget's picker: the saved stops, or matches for the search text.
    /* SKIP @bridge */public func widgetStops(_ query: String) async -> String {
        #if os(Android)
        return await AndroidWidgetData.stops(query)
        #else
        return "[]"
        #endif
    }

    /// The widget's texts in the app's language.
    /* SKIP @bridge */public func widgetTexts() -> String {
        #if os(Android)
        return AndroidWidgetData.texts()
        #else
        return "{}"
        #endif
    }
}

#if os(Android)
enum AndroidWidgetData {
    struct Row: Encodable {
        let line: String
        let background: String
        let foreground: String
        let time: Double
        let timeText: String
        let headsign: String
        let circular: Bool
        let cancelled: Bool
        let delay: Int
        let realTime: Bool
    }

    struct Payload: Encodable {
        var ok: Bool
        var rows: [Row] = []
        var savedData: String? = nil
    }

    struct Stop: Encodable {
        let id: String
        let stopId: String
        let name: String
        let link: String
    }

    static func departures(_ stopId: String) async -> String {
        var payload = Payload(ok: false)
        do {
            let res = try await TransitousClient.shared.departures(stopId: stopId, count: 30, radius: 150)
            let list = StopTime.upcoming(res.stopTimes, now: Date()).prefix(12)
            payload.ok = true
            payload.rows = list.map { st in
                Row(line: st.lineName, background: st.lineColors.background, foreground: st.lineColors.text,
                    time: st.departureTime.timeIntervalSince1970 * 1000, timeText: Fmt.time(st.departureTime, tz: st.place.tz),
                    headsign: st.shownDirection ?? "", circular: st.isCircular, cancelled: st.isCancelled,
                    delay: st.delayMinutes, realTime: st.realTime)
            }
            if let from = await Connectivity.shared.offlineDataFrom {
                payload.savedData = L10n.f("Saved data: %@", Fmt.time(from))
            }
        } catch {
            logger.error("widget departures failed: \(error)")
        }
        return json(payload) ?? "{\"ok\":false}"
    }

    static func stops(_ query: String) async -> String {
        let text = query.trimmingCharacters(in: .whitespaces)
        let found: [StopEntity]
        if text.isEmpty {
            found = StopQuery.savedStops()
        } else {
            found = (try? await StopQuery().entities(matching: text)) ?? []
        }
        return json(found.prefix(30).map { Stop(id: $0.id, stopId: $0.stopId, name: $0.name, link: $0.deepLink.absoluteString) }) ?? "[]"
    }

    static func texts() -> String {
        let t: [String: String] = [
            "title": L10n.t("Departures"),
            "description": L10n.t("Next departures at a stop of your choice."),
            "chooseStop": L10n.t("Choose a stop"),
            "unavailable": L10n.t("Departures unavailable"),
            "noneSoon": L10n.t("No departures soon"),
            "search": L10n.t("Search"),
            "saved": L10n.t("Saved"),
            "stops": L10n.t("Stops"),
        ]
        return json(t) ?? "{}"
    }

    private static func json<T: Encodable>(_ value: T) -> String? {
        guard let data = try? JSONEncoder().encode(value) else { return nil }
        return String(data: data, encoding: .utf8)
    }
}
#endif

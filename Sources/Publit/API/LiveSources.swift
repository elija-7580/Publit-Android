import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

// Extra real-time sources for regions where Transitous has timetables but (almost) no live data:
// Austria (ÖBB/VAO), Vienna (Wiener Linien), Switzerland (search.ch), in Germany North Rhine-Westphalia
// (VRR), Berlin/Brandenburg (VBB), Hesse (RMV), Lower Saxony/Bremen (VBN), Schleswig-Holstein (NAH.SH)
// and Rhineland-Palatinate (VRN), Venice (ACTV), Stockholm (SL) and London (TfL line status).
// Transitous stays the router; these only correct times, cancellations, platforms and add alerts
// to rides it already found. A live time from Transitous always wins.

/// One ride to look up: a vehicle leaving a stop at a timetable time.
struct LiveRide: Sendable, Hashable {
    let point: LatLon
    let stopId: String?
    let stopName: String
    /// Line names as Transitous gives them (short name, display name).
    let lines: [String]
    let headsign: String?
    let scheduled: Date
    let mode: String
    /// Time zone of the stop, when known: tells Austria from Bavaria next to the border.
    var tz: String? = nil
    /// Transitous already has a live time for it.
    var live = false
}

/// A departure as an extra source reports it.
struct LiveDeparture: Sendable, Hashable {
    /// Names to match against (line, product name, train number).
    var lines: [String]
    var destination: String? = nil
    var scheduled: Date? = nil
    /// Live time; nil when the source has only the timetable for it.
    var expected: Date? = nil
    var cancelled = false
    var track: String? = nil
    var alerts: [Alert] = []
}

/// What a source knows about one ride.
struct LiveFix: Sendable, Hashable {
    var departure: Date? = nil
    var arrival: Date? = nil
    var cancelled = false
    var track: String? = nil
    var alerts: [Alert] = []
    var source: String

    var hasTimes: Bool { departure != nil || cancelled }
}

protocol LiveSource: Sendable {
    /// Shown as "Live times: …".
    var name: String { get }
    /// How far ahead the source knows anything useful.
    var horizon: TimeInterval { get }
    func covers(_ ride: LiveRide) -> Bool
    func fix(for ride: LiveRide, now: Date) async throws -> LiveFix?
    /// Only asked for rides without a live time from Transitous (it adds no alerts worth a request).
    var onlyWithoutLive: Bool { get }
}

extension LiveSource {
    var onlyWithoutLive: Bool { false }

    /// Covers the ride and is worth asking about it now.
    func asks(_ ride: LiveRide, now: Date) -> Bool {
        !(onlyWithoutLive && ride.live) && ride.scheduled < now.addingTimeInterval(horizon) && covers(ride)
    }
}

/// A latitude/longitude rectangle.
struct GeoBox: Sendable {
    let minLat: Double, maxLat: Double, minLon: Double, maxLon: Double
    func contains(_ p: LatLon) -> Bool { p.lat >= minLat && p.lat <= maxLat && p.lon >= minLon && p.lon <= maxLon }
}

/// A rough outline (lon, lat), for areas a rectangle would overshoot.
struct GeoOutline: Sendable {
    let points: [(lon: Double, lat: Double)]
    func contains(_ p: LatLon) -> Bool {
        var inside = false
        var j = points.count - 1
        for i in points.indices {
            let a = points[i], b = points[j]
            if (a.lat > p.lat) != (b.lat > p.lat), p.lon < (b.lon - a.lon) * (p.lat - a.lat) / (b.lat - a.lat) + a.lon { inside.toggle() }
            j = i
        }
        return inside
    }
}

// MARK: Matching

enum LiveMatch {
    /// "Bus 404" → "404", "S 4 (Zug-Nr. 5197)" → "s4", "13_UN" → "13", "Elizabeth line" → "elizabeth".
    static func key(_ name: String) -> String {
        var t = name.lowercased().folding(options: .diacriticInsensitive, locale: Locale(identifier: "en_US_POSIX"))
        if let r = t.range(of: "(") { t = String(t[..<r.lowerBound]) }
        if let r = t.range(of: "_") { t = String(t[..<r.lowerBound]) }
        t = t.trimmingCharacters(in: .whitespaces)
        for prefix in ["bus ", "tram ", "str ", "strassenbahn ", "linie ", "line ", "zug-nr. ", "u-bahn ", "night bus "] where t.hasPrefix(prefix) {
            t.removeFirst(prefix.count)
            break
        }
        t = String(t.unicodeScalars.filter { CharacterSet.alphanumerics.contains($0) }.map(Character.init))
        if t.count > 4, t.hasSuffix("line") { t.removeLast(4) }
        return t
    }

    /// Runs of 3+ digits: train numbers ("REX 7 2345" → 2345).
    static func numbers(_ name: String) -> Set<String> {
        var out = Set<String>(), run = ""
        for c in name + " " {
            if c.isASCII, c.isNumber { run.append(c) } else { if run.count >= 3 { out.insert(run) }; run = "" }
        }
        return out
    }

    static func sameLine(_ a: [String], _ b: [String]) -> Bool {
        let ka = Set(a.map(key).filter { !$0.isEmpty }), kb = Set(b.map(key).filter { !$0.isEmpty })
        if !ka.isDisjoint(with: kb) { return true }
        let na = a.reduce(into: Set<String>()) { $0.formUnion(numbers($1)) }
        let nb = b.reduce(into: Set<String>()) { $0.formUnion(numbers($1)) }
        return !na.isEmpty && !na.isDisjoint(with: nb)
    }

    /// The departure that is this ride: same line, timetable time within `tolerance`. Without a
    /// timetable time (some boards only count down), the nearest live time not long before it.
    static func best(for ride: LiveRide, in list: [LiveDeparture], tolerance: TimeInterval = 90) -> LiveDeparture? {
        let same = list.filter { sameLine(ride.lines, $0.lines) }
        let timed = same.compactMap { d -> (LiveDeparture, TimeInterval)? in
            guard let s = d.scheduled else { return nil }
            let gap = abs(s.timeIntervalSince(ride.scheduled))
            return gap <= tolerance ? (d, gap) : nil
        }
        if let hit = timed.min(by: { $0.1 < $1.1 }) { return hit.0 }
        let counted = same.compactMap { d -> (LiveDeparture, TimeInterval)? in
            guard d.scheduled == nil, let e = d.expected else { return nil }
            let late = e.timeIntervalSince(ride.scheduled)
            return late >= -tolerance && late <= 20 * 60 ? (d, abs(late)) : nil
        }
        return counted.min(by: { $0.1 < $1.1 })?.0
    }

    static func fix(_ d: LiveDeparture, source: String) -> LiveFix {
        LiveFix(departure: d.expected, cancelled: d.cancelled, track: d.track, alerts: d.alerts, source: source)
    }
}

// MARK: Merging

enum LiveMerge {
    static func merge(_ leg: Leg, _ fix: LiveFix) -> Leg {
        var l = leg
        if !leg.realTime {
            if let dep = fix.departure {
                let delay = dep.timeIntervalSince(leg.scheduledStartTime)
                let arrival = fix.arrival ?? leg.scheduledEndTime.addingTimeInterval(max(0, delay))
                l.startTime = dep
                l.endTime = max(arrival, dep)
                l.duration = Int(l.endTime.timeIntervalSince(l.startTime))
                l.from.departure = l.startTime
                l.to.arrival = l.endTime
                if let stops = leg.intermediateStops {
                    let shift = max(0, delay)
                    l.intermediateStops = stops.map { s in
                        var s = s
                        s.arrival = (s.scheduledArrival ?? s.arrival).map { $0.addingTimeInterval(shift) }
                        s.departure = (s.scheduledDeparture ?? s.departure).map { $0.addingTimeInterval(shift) }
                        return s
                    }
                }
                l.realTime = true
                l.liveSource = fix.source
            }
            if fix.cancelled {
                l.cancelled = true
                l.liveSource = fix.source
            }
            if let t = fix.track, !t.isEmpty { l.from.track = t }
        }
        l.alerts = adding(fix.alerts, to: leg.alerts)
        return l
    }

    static func adding(_ new: [Alert], to old: [Alert]?) -> [Alert]? {
        guard !new.isEmpty else { return old }
        var list = old ?? []
        var seen = Set(list.map { LiveMatch.key($0.headerText) + "|" + LiveMatch.key(String($0.descriptionText.prefix(80))) })
        for a in new where seen.insert(LiveMatch.key(a.headerText) + "|" + LiveMatch.key(String(a.descriptionText.prefix(80)))).inserted {
            list.append(a)
        }
        return list
    }

    static func shifted(_ leg: Leg, by delta: TimeInterval) -> Leg {
        guard delta != 0 else { return leg }
        var l = leg
        l.startTime = leg.startTime.addingTimeInterval(delta)
        l.endTime = leg.endTime.addingTimeInterval(delta)
        l.from.departure = leg.from.departure?.addingTimeInterval(delta)
        l.to.arrival = leg.to.arrival?.addingTimeInterval(delta)
        return l
    }

    /// Fixes by leg index. Walks after a corrected ride move with its arrival; the walk to the
    /// first ride moves with its departure.
    static func merge(_ it: Itinerary, fixes: [Int: LiveFix]) -> Itinerary {
        guard !fixes.isEmpty else { return it }
        var out = it
        let firstTransit = it.legs.firstIndex(where: \.isTransit)
        var carry: TimeInterval = 0
        for i in it.legs.indices {
            let old = it.legs[i]
            if let fix = fixes[i], old.isTransit {
                let new = merge(old, fix)
                out.legs[i] = new
                if i == firstTransit {
                    let lead = new.startTime.timeIntervalSince(old.startTime)
                    for j in 0..<i where !it.legs[j].isTransit { out.legs[j] = shifted(it.legs[j], by: lead) }
                }
                carry = new.endTime.timeIntervalSince(old.endTime)
            } else if old.isTransit {
                carry = 0
            } else if carry != 0, firstTransit.map({ i > $0 }) ?? false {
                out.legs[i] = shifted(old, by: carry)
            }
        }
        if let first = out.legs.first, let last = out.legs.last {
            out.startTime = first.startTime
            out.endTime = last.endTime
            out.duration = Int(last.endTime.timeIntervalSince(first.startTime))
        }
        return out
    }

    static func merge(_ st: StopTime, _ fix: LiveFix) -> StopTime {
        var s = st
        if !st.realTime {
            if let dep = fix.departure {
                s.place.departure = dep
                if let a = st.place.scheduledArrival ?? st.place.arrival, let sd = st.scheduledDepartureTime {
                    s.place.arrival = a.addingTimeInterval(max(0, dep.timeIntervalSince(sd)))
                }
                s.realTime = true
                s.liveSource = fix.source
            }
            if fix.cancelled { s.cancelled = true; s.liveSource = fix.source }
            if let t = fix.track, !t.isEmpty { s.place.track = t }
        }
        s.place.alerts = adding(fix.alerts, to: st.place.alerts)
        return s
    }
}

// MARK: Overlay

enum LiveOverlay {
    static let defaultsKey = "extraLiveSources"
    static var enabled: Bool { UserDefaults.standard.object(forKey: defaultsKey) as? Bool ?? true }

    static let sources: [any LiveSource] = [WienerLinienSource(), HafasSource.oebb, SearchChSource(), EFASource.vrr,
                                            HafasSource.vbb, HafasSource.rmv, HafasSource.vbn, HafasSource.nahsh, EFASource.vrn,
                                            ACTVSource(), SLSource(), TfLStatusSource()]

    /// Names of all extra sources, for the credits in Settings.
    static var sourceNames: [String] { sources.map { $0.name } }

    static func ride(_ leg: Leg) -> LiveRide {
        LiveRide(point: leg.from.coordinate, stopId: leg.from.stopId, stopName: leg.from.name,
                 lines: [leg.routeShortName, leg.displayName].compactMap { $0 }.filter { !$0.isEmpty },
                 headsign: leg.headsign, scheduled: leg.scheduledStartTime, mode: leg.mode, tz: leg.from.tz, live: leg.realTime)
    }

    static func ride(_ st: StopTime) -> LiveRide {
        LiveRide(point: st.place.coordinate, stopId: st.place.stopId, stopName: st.place.name,
                 lines: [st.routeShortName, st.displayName].compactMap { $0 }.filter { !$0.isEmpty },
                 headsign: st.headsign, scheduled: st.scheduledDepartureTime ?? st.departureTime, mode: st.mode, tz: st.place.tz,
                 live: st.realTime)
    }

    static func wanted(_ ride: LiveRide, now: Date) -> Bool {
        guard ride.scheduled > now.addingTimeInterval(-30 * 60) else { return false }
        return sources.contains { $0.asks(ride, now: now) }
    }

    /// Everything the sources know about one ride: times from the first that has them,
    /// alerts from all that cover it.
    static func fix(for ride: LiveRide, now: Date) async -> LiveFix? {
        var result: LiveFix?
        var alerts: [Alert] = []
        for source in sources where source.asks(ride, now: now) {
            guard let f = try? await source.fix(for: ride, now: now) else { continue }
            alerts += f.alerts
            if result == nil, f.hasTimes { result = f }
            if result != nil { break }
        }
        if result == nil, !alerts.isEmpty { result = LiveFix(source: "") }
        result?.alerts = LiveMerge.adding(alerts, to: nil) ?? []
        return result
    }

    /// Looks up rides side by side and returns what arrived within `budget`. Late answers
    /// still land in the caches, so the next refresh has them.
    static func lookup(_ rides: [LiveRide], now: Date, budget: TimeInterval) async -> [LiveRide: LiveFix] {
        guard !rides.isEmpty else { return [:] }
        let box = Collector()
        for r in rides {
            Task.detached(priority: .utility) {
                let found = await LiveOverlay.fix(for: r, now: now)
                await box.put(r, found)
            }
        }
        let deadline = Date().addingTimeInterval(budget)
        while Date() < deadline, await box.count < rides.count, !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
        return await box.results
    }

    private actor Collector {
        var results: [LiveRide: LiveFix] = [:]
        var count = 0
        func put(_ r: LiveRide, _ f: LiveFix?) { count += 1; if let f { results[r] = f } }
    }

    static func apply(_ list: [Itinerary], now: Date = Date(), budget: TimeInterval = 4, maxRides: Int = 24) async -> [Itinerary] {
        guard enabled else { return list }
        var rides: [LiveRide] = []
        var seen = Set<LiveRide>()
        for it in list {
            for leg in it.legs where leg.isTransit {
                let r = ride(leg)
                if wanted(r, now: now), rides.count < maxRides, seen.insert(r).inserted { rides.append(r) }
            }
        }
        guard !rides.isEmpty else { return list }
        let fixes = await lookup(rides, now: now, budget: budget)
        guard !fixes.isEmpty else { return list }
        return list.map { it in
            var byLeg: [Int: LiveFix] = [:]
            for (i, leg) in it.legs.enumerated() where leg.isTransit {
                if let f = fixes[ride(leg)] { byLeg[i] = f }
            }
            return LiveMerge.merge(it, fixes: byLeg)
        }
    }

    static func apply(_ itinerary: Itinerary, now: Date = Date(), budget: TimeInterval = 4) async -> Itinerary {
        await apply([itinerary], now: now, budget: budget).first ?? itinerary
    }

    static func apply(_ list: [StopTime], now: Date = Date(), budget: TimeInterval = 2, maxRides: Int = 40) async -> [StopTime] {
        guard enabled else { return list }
        var rides: [LiveRide] = []
        var seen = Set<LiveRide>()
        for st in list {
            let r = ride(st)
            if wanted(r, now: now), rides.count < maxRides, seen.insert(r).inserted { rides.append(r) }
        }
        guard !rides.isEmpty else { return list }
        let fixes = await lookup(rides, now: now, budget: budget)
        guard !fixes.isEmpty else { return list }
        return list.map { st in fixes[ride(st)].map { LiveMerge.merge(st, $0) } ?? st }
    }

    static func apply(_ res: StopTimesResponse, now: Date = Date(), budget: TimeInterval = 2) async -> StopTimesResponse {
        var out = res
        out.stopTimes = await apply(res.stopTimes, now: now, budget: budget)
        return out
    }

    /// `enriched` copies replace their originals; routes added meanwhile (next page) stay.
    static func replace(_ current: [Itinerary], with enriched: [Itinerary]) -> [Itinerary] {
        let byId = Dictionary(enriched.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        return current.map { byId[$0.id] ?? $0 }
    }

    /// Sources that corrected a route, for "Live times: ÖBB".
    static func sourceNames(_ it: Itinerary) -> [String] {
        var seen = Set<String>()
        return it.legs.compactMap(\.liveSource).filter { !$0.isEmpty && seen.insert($0).inserted }
    }
}

// MARK: Plumbing

/// Answers kept for a short time; one download for overlapping identical requests; a failed
/// request is not repeated for a minute (a dead server must not slow every refresh).
actor TTLCache<Value: Sendable> {
    private var store: [String: (expires: Date, value: Value)] = [:]
    private var flights: [String: Task<Value, Error>] = [:]
    private var failed: [String: Date] = [:]
    private let limit: Int

    init(limit: Int = 200) { self.limit = limit }

    func value(_ key: String, ttl: TimeInterval, _ load: @escaping @Sendable () async throws -> Value) async throws -> Value {
        let now = Date()
        if let hit = store[key], hit.expires > now { return hit.value }
        if let until = failed[key], until > now { throw URLError(.resourceUnavailable) }
        if let flight = flights[key] { return try await flight.value }
        let task = Task { try await load() }
        flights[key] = task
        do {
            let v = try await task.value
            flights[key] = nil
            if store.count >= limit {
                store = store.filter { $0.value.expires > now }
                if store.count >= limit { store.removeAll() }
            }
            store[key] = (now.addingTimeInterval(ttl), v)
            return v
        } catch {
            flights[key] = nil
            failed[key] = Date().addingTimeInterval(60)
            throw error
        }
    }
}

enum LiveHTTP {
    static let session: URLSession = {
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest = 6
        cfg.requestCachePolicy = .reloadIgnoringLocalCacheData
        cfg.urlCache = nil
        return URLSession(configuration: cfg)
    }()

    static func request(_ url: URL, userAgent: String = AppInfo.userAgent) -> URLRequest {
        var r = URLRequest(url: url)
        r.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        r.setValue("application/json", forHTTPHeaderField: "Accept")
        return r
    }

    static func json(_ req: URLRequest) async throws -> Any {
        let (data, response) = try await session.data(for: req)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw URLError(.badServerResponse) }
        return try JSONSerialization.jsonObject(with: data)
    }
}

/// Small helpers for loosely typed JSON.
enum J {
    static func dict(_ v: Any?) -> [String: Any] { v as? [String: Any] ?? [:] }
    static func list(_ v: Any?) -> [Any] { v as? [Any] ?? [] }
    static func str(_ v: Any?) -> String? {
        if let s = v as? String { return s }
        if let n = v as? NSNumber { return n.stringValue }
        return nil
    }
    static func int(_ v: Any?) -> Int? { (v as? NSNumber)?.intValue ?? (v as? String).flatMap { Int($0) } }
    static func double(_ v: Any?) -> Double? { (v as? NSNumber)?.doubleValue ?? (v as? String).flatMap { Double($0) } }
    static func bool(_ v: Any?) -> Bool { (v as? Bool) ?? ((v as? NSNumber)?.boolValue ?? false) }

    /// Plain text from operator HTML ("<br>", "<b>", "&#60;").
    static func plain(_ html: String) -> String {
        var s = html.replacingOccurrences(of: "<br>", with: "\n", options: .caseInsensitive)
            .replacingOccurrences(of: "<br/>", with: "\n", options: .caseInsensitive)
            .replacingOccurrences(of: "<br />", with: "\n", options: .caseInsensitive)
        while let open = s.range(of: "<"), let close = s.range(of: ">", range: open.upperBound..<s.endIndex) {
            s.removeSubrange(open.lowerBound..<close.upperBound)
        }
        for (entity, char) in [("&nbsp;", " "), ("&amp;", "&"), ("&lt;", "<"), ("&gt;", ">"), ("&quot;", "\""), ("&#39;", "'"), ("&#60;", "<"), ("&#62;", ">")] {
            s = s.replacingOccurrences(of: entity, with: char)
        }
        return s.split(separator: "\n").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }.joined(separator: "\n")
    }

    static func formatter(_ format: String, _ tz: String) -> DateFormatter {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: tz)
        f.dateFormat = format
        return f
    }
}

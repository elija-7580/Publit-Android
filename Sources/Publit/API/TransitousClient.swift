import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

struct APIError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

private struct ServerError: Decodable { let error: String }

/// Client for the Transitous MOTIS 2 API (https://transitous.org/api/).
///
/// Usage policy: open-source, non-commercial, identifying User-Agent, visible attribution.
final class TransitousClient: @unchecked Sendable {
    static let shared = TransitousClient()

    private let base: URL
    /// Asked only while `base` cannot be reached or fails (see `ServerFailover`).
    private let backups: [URL]
    let failover = ServerFailover()
    private let session: URLSession
    /// Same settings with a 45 s idle timeout, for Park & Ride and sharing searches.
    private let slowSession: URLSession
    private let userAgent: String
    let decoder: JSONDecoder
    let cache: ResponseCache
    private let mapStopCache = MapStopCache()

    /// The MOTIS project's Europe server: the same engine (and so the same API), the same data
    /// sets and IDs as Transitous, run separately at TU Darmstadt. Saved stops and trips stay valid.
    static let backupServer = URL(string: "https://europe.motis-project.de")!

    init(base: URL = URL(string: "https://api.transitous.org")!,
         // No fallback by default: Transitous' policy allows apps only on api.transitous.org,
         // not on the servers behind it (*.motis-project.de). `backupServer` stays for tests.
         backups: [URL] = [],
         userAgent: String = AppInfo.userAgent,
         cache: ResponseCache = ResponseCache()) {
        self.base = base
        self.backups = backups
        self.userAgent = userAgent
        self.cache = cache
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest = 20
        cfg.requestCachePolicy = .reloadIgnoringLocalCacheData
        // Responses are kept by `ResponseCache`; a second copy in URLCache would only cost memory and disk.
        cfg.urlCache = nil
        session = URLSession(configuration: cfg)
        let slow = cfg.copy() as! URLSessionConfiguration
        slow.timeoutIntervalForRequest = 45
        slowSession = URLSession(configuration: slow)
        decoder = TransitousClient.makeDecoder()
    }

    static func makeDecoder() -> JSONDecoder {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .custom { dec in
            let c = try dec.singleValueContainer()
            let s = try c.decode(String.self)
            if let date = DateParsing.parse(s) { return date }
            throw DecodingError.dataCorruptedError(in: c, debugDescription: "Invalid date: \(s)")
        }
        return d
    }

    // MARK: Endpoints

    /// - Parameter type: `STOP`, `ADDRESS` or `PLACE` to search only those; nil for everything.
    func geocode(_ text: String, near: LatLon?, type: String? = nil,
                 language: String? = Locale.current.language.languageCode?.identifier) async throws -> [GeocodeMatch] {
        var q = [URLQueryItem(name: "text", value: text), URLQueryItem(name: "numResults", value: "15")]
        if let type { q.append(URLQueryItem(name: "type", value: type)) }
        if let near {
            q.append(URLQueryItem(name: "place", value: near.coarseAPIValue))
            q.append(URLQueryItem(name: "placeBias", value: "5"))
        }
        if let language { q.append(URLQueryItem(name: "language", value: language)) }
        return try await get("/api/v1/geocode", q, cache: .search)
    }

    /// Walking limits for the first and last leg and for walk-only routes (seconds).
    /// The server default of 15 min drops many real connections, e.g. to airports or
    /// stations at the edge of town; 30 min matches what people actually walk.
    static let walkLimits = (prePost: 1800, direct: 3600)
    /// Second attempt when nothing was found; further steps in `Reach.walkSteps`.
    static var widerWalkLimit: Int { Reach.walkSteps[1] }

    func plan(from: LatLon, to: LatLon, time: Date?, arriveBy: Bool, pageCursor: String? = nil,
              maxWalk: Int = TransitousClient.walkLimits.prePost, options: RouteOptions = RouteOptions()) async throws -> PlanResponse {
        // "Now" searches change by the minute; planned ones can be reused a little longer.
        try await get("/api/v6/plan", Self.planQuery(from: from, to: to, time: time, arriveBy: arriveBy,
                                                     pageCursor: pageCursor, maxWalk: maxWalk, options: options),
                      cache: CachePolicy(maxAge: time == nil ? 20 : 60, persist: true),
                      timeout: options.isSlowSearch ? 45 : nil)
    }

    static func planQuery(from: LatLon, to: LatLon, time: Date?, arriveBy: Bool,
                          pageCursor: String? = nil, maxWalk: Int = walkLimits.prePost,
                          options: RouteOptions = RouteOptions(),
                          smart: SmartRouting.Request = .current) -> [URLQueryItem] {
        var q = [
            URLQueryItem(name: "fromPlace", value: from.apiValue),
            URLQueryItem(name: "toPlace", value: to.apiValue),
            URLQueryItem(name: "arriveBy", value: arriveBy ? "true" : "false"),
            // Footpaths between platforms and stations routed on OpenStreetMap, not just feed transfers.
            URLQueryItem(name: "useRoutedTransfers", value: "true"),
            URLQueryItem(name: "maxPreTransitTime", value: String(maxWalk)),
            URLQueryItem(name: "withFares", value: "true"),
            URLQueryItem(name: "maxPostTransitTime", value: String(maxWalk)),
            URLQueryItem(name: "maxDirectTime", value: String(walkLimits.direct)),
        ]
        // Walking, cycling and driving the whole way come back in `direct`, for comparison.
        q.append(URLQueryItem(name: "directModes", value: options.directModes.joined(separator: ",")))
        // Own bike / car to the first stop, shared bikes and scooters at either end
        // (car and sharing are much slower to compute on the server).
        if options.preTransitModes != ["WALK"] {
            q.append(URLQueryItem(name: "preTransitModes", value: options.preTransitModes.joined(separator: ",")))
        }
        if options.postTransitModes != ["WALK"] {
            q.append(URLQueryItem(name: "postTransitModes", value: options.postTransitModes.joined(separator: ",")))
        }
        if let modes = options.modes.apiValue { q.append(URLQueryItem(name: "transitModes", value: modes)) }
        if let n = options.maxTransfers { q.append(URLQueryItem(name: "maxTransfers", value: String(n))) }
        if options.wheelchair { q.append(URLQueryItem(name: "pedestrianProfile", value: "WHEELCHAIR")) }
        if let time { q.append(URLQueryItem(name: "time", value: DateParsing.format(time))) }
        if let pageCursor { q.append(URLQueryItem(name: "pageCursor", value: pageCursor)) }
        q += smart.queryItems
        return q
    }

    /// Cycling or driving the whole way, for the comparison under the routes. Without transit
    /// (`transitModes` empty), so the server only routes on the streets. Street routes do not
    /// follow the timetable: they are reused for a quarter of an hour.
    func streetRoutes(from: LatLon, to: LatLon, time: Date?, arriveBy: Bool, mode: String) async throws -> [Itinerary] {
        let res: PlanResponse = try await get("/api/v6/plan", Self.streetQuery(from: from, to: to, time: time, arriveBy: arriveBy, mode: mode),
                                              cache: CachePolicy(maxAge: 900, persist: true))
        return res.direct
    }

    static func streetQuery(from: LatLon, to: LatLon, time: Date?, arriveBy: Bool, mode: String) -> [URLQueryItem] {
        var q = [
            URLQueryItem(name: "fromPlace", value: from.apiValue),
            URLQueryItem(name: "toPlace", value: to.apiValue),
            URLQueryItem(name: "arriveBy", value: arriveBy ? "true" : "false"),
            URLQueryItem(name: "directModes", value: mode),
            URLQueryItem(name: "transitModes", value: ""),
            URLQueryItem(name: "maxDirectTime", value: String(walkLimits.direct)),
        ]
        if let time { q.append(URLQueryItem(name: "time", value: DateParsing.format(time))) }
        return q
    }

    /// Fresh real-time state of a saved route (same vehicles, current delays and cancellations).
    /// Without fares: they do not change, `TripStore.update` keeps the ones saved with the trip,
    /// and the answer is smaller (this runs every minute or two for a Live Activity).
    func refresh(itineraryId: String) async throws -> Itinerary {
        let fresh: Itinerary = try await get("/api/v6/refresh-itinerary", [URLQueryItem(name: "itineraryId", value: itineraryId),
                                                                            URLQueryItem(name: "withFares", value: "false")], cache: .noStore)
        // Where the feed has no live times (Austria, Venice, Stockholm …), other operators' data.
        return await LiveOverlay.apply(fresh, budget: 3)
    }

    /// Stops inside a map rectangle, for the interactive station map.
    func mapStops(min: LatLon, max: LatLon, zoom: Int) async throws -> [MapStop] {
        if let cached = mapStopCache.covering(min: min, max: max, zoom: zoom) { return cached }
        let stops: [MapStop] = try await get("/api/v6/map/stops", [
            URLQueryItem(name: "min", value: min.apiValue),
            URLQueryItem(name: "max", value: max.apiValue),
            URLQueryItem(name: "zoom", value: String(zoom)),
        ], cache: .search)
        let valid = stops.filter { $0.coordinate.isValidCoordinate }
        mapStopCache.store(valid, min: min, max: max, zoom: zoom)
        return valid
    }

    /// - Parameter modes: only these modes (`ModeFilter.apiValue`); nil for all.
    func departures(near center: LatLon, radius: Int, count: Int, modes: String? = nil) async throws -> StopTimesResponse {
        var q = [
            URLQueryItem(name: "center", value: center.apiValue),
            URLQueryItem(name: "withAlerts", value: "true"),
            URLQueryItem(name: "radius", value: String(radius)),
            URLQueryItem(name: "n", value: String(count)),
        ]
        if let modes { q.append(URLQueryItem(name: "mode", value: modes)) }
        // A filtered board has its own slot: "only trams" must not come back as the full board offline.
        let res: StopTimesResponse = try await get("/api/v6/stoptimes", q,
                                                   cache: CachePolicy(maxAge: 20, persist: true, slot: modes.map { "nearby|\($0)" } ?? "nearby"))
        return await LiveOverlay.apply(res)
    }

    /// Departures at a stop. `radius` (metres) also includes nearby platforms of the same station.
    func departures(stopId: String, count: Int, radius: Int? = nil, modes: String? = nil) async throws -> StopTimesResponse {
        var q = [
            URLQueryItem(name: "stopId", value: stopId),
            URLQueryItem(name: "n", value: String(count)),
            URLQueryItem(name: "withAlerts", value: "true"),
        ]
        if let radius { q.append(URLQueryItem(name: "radius", value: String(radius))) }
        if let modes { q.append(URLQueryItem(name: "mode", value: modes)) }
        let res: StopTimesResponse = try await get("/api/v6/stoptimes", q, cache: .live)
        return await LiveOverlay.apply(res)
    }

    /// Departures over a time span, e.g. a whole day for a line timetable or the lines around a place.
    /// - Parameters:
    ///   - window: seconds from `time`; the server returns every departure in it.
    ///   - modes: only these modes; keeps busy stations' answers small.
    func departures(stopId: String? = nil, center: LatLon? = nil, radius: Int? = nil, time: Date, window: Int,
                    modes: String? = nil, count: Int = 20, maxAge: TimeInterval) async throws -> StopTimesResponse {
        var q: [URLQueryItem] = []
        if let stopId { q.append(URLQueryItem(name: "stopId", value: stopId)) }
        if let center { q.append(URLQueryItem(name: "center", value: center.apiValue)) }
        if let radius { q.append(URLQueryItem(name: "radius", value: String(radius))) }
        q += [
            URLQueryItem(name: "time", value: DateParsing.format(time)),
            URLQueryItem(name: "window", value: String(window)),
            URLQueryItem(name: "n", value: String(count)),
            URLQueryItem(name: "withAlerts", value: "false"),
        ]
        if let modes { q.append(URLQueryItem(name: "mode", value: modes)) }
        return try await get("/api/v6/stoptimes", q, cache: CachePolicy(maxAge: maxAge, persist: maxAge >= 600))
    }

    /// Vehicles running in a map rectangle between `start` and `end`, stop to stop with their shapes.
    /// Answers are a few hundred KB: asked only for what is on screen, a few minutes ahead.
    func mapTrips(min: LatLon, max: LatLon, zoom: Int, start: Date, end: Date) async throws -> [TripSegment] {
        try await get("/api/v6/map/trips", [
            URLQueryItem(name: "min", value: min.apiValue),
            URLQueryItem(name: "max", value: max.apiValue),
            URLQueryItem(name: "zoom", value: String(zoom)),
            URLQueryItem(name: "startTime", value: DateParsing.format(start)),
            URLQueryItem(name: "endTime", value: DateParsing.format(end)),
            URLQueryItem(name: "precision", value: zoom >= 14 ? "5" : "4"),
        ], cache: CachePolicy(maxAge: 15, persist: false))
    }

    /// Bike and scooter sharing stations and free vehicles in a map rectangle.
    func rentals(min: LatLon, max: LatLon) async throws -> RentalsResponse {
        try await get("/api/v1/rentals", [
            URLQueryItem(name: "min", value: min.apiValue),
            URLQueryItem(name: "max", value: max.apiValue),
            URLQueryItem(name: "withProviders", value: "false"),
            URLQueryItem(name: "withStations", value: "true"),
            URLQueryItem(name: "withVehicles", value: "true"),
            URLQueryItem(name: "withZones", value: "false"),
        ], cache: CachePolicy(maxAge: 60, persist: false))
    }

    /// The whole trip of one vehicle, with all stops and real-time times.
    func trip(id: String) async throws -> Itinerary {
        try await get("/api/v6/trip", [URLQueryItem(name: "tripId", value: id)], cache: CachePolicy(maxAge: 15, persist: true))
    }

    // MARK: Transport

    /// Memory warning: drop all kept answers. Background: keep only the newest few MB.
    func releaseMemory(background: Bool) {
        cache.releaseMemory(keepingBytes: background ? 4 * 1024 * 1024 : 0)
        mapStopCache.clear()
    }

    /// Last "departures near me" response and where it was asked, for an instant first screen.
    func lastNearby(maxAge: TimeInterval, modes: String? = nil) async -> (response: StopTimesResponse, center: LatLon, date: Date)? {
        guard let e = cache.stale(modes.map { "nearby|\($0)" } ?? "nearby"), Date().timeIntervalSince(e.date) < maxAge,
              let res = try? decoder.decode(StopTimesResponse.self, from: e.data),
              let center = URLComponents(string: e.url)?.queryItems?.first(where: { $0.name == "center" })?.value
                .flatMap(LatLon.init(apiValue:)) else { return nil }
        return (res, center, e.date)
    }

    private func get<T: Decodable>(_ path: String, _ query: [URLQueryItem], cache policy: CachePolicy,
                                   timeout: TimeInterval? = nil) async throws -> T {
        let trace = PerformanceTrace.begin("Transit request")
        defer { PerformanceTrace.end(trace, "Transit request") }
        var comps = URLComponents(url: base.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        comps.queryItems = query
        // URLComponents leaves "+" as it is, and the server reads it as a space: itinerary and trip
        // IDs (base64) then come back as HTTP 400. Encoded, "+" stays a plus.
        comps.percentEncodedQuery = Self.encodePlus(comps.percentEncodedQuery)
        let url = comps.url!
        let key = policy.slot ?? url.absoluteString
        if let hit = cache.fresh(key, maxAge: policy.maxAge),
           hit.url == url.absoluteString || (policy.slot?.hasPrefix("nearby") == true && Self.sameNearbyRequest(savedURL: hit.url, requestedURL: url)) {
            // Decoded once per answer: back navigation and a second screen reuse the models.
            if let value = hit.decoded as? T { return value }
            if let value = try? decoder.decode(T.self, from: hit.data) {
                cache.remember(key, decoded: value, for: hit)
                return value
            }
        }
        // Stations/search data are static enough to reuse a fresh disk copy after relaunch.
        if policy.persist, policy.maxAge >= 600, let hit = cache.stale(key), hit.url == url.absoluteString,
           Date().timeIntervalSince(hit.date) < policy.maxAge,
           let value = try? decoder.decode(T.self, from: hit.data) {
            cache.store(key, data: hit.data, url: hit.url, persist: false, now: hit.date, decoded: value)
            return value
        }
        var req = URLRequest(url: url)
        // The server computes Park & Ride before sending a byte; the default 20 s would cut it off.
        if let timeout { req.timeoutInterval = timeout }
        req.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        req.setValue("application/json", forHTTPHeaderField: "Accept")

        let data: Data
        let status: Int
        // Diagnostics: the request type only ("plan", "stoptimes"), never its places or IDs.
        let kind = Self.requestKind(path)
        let started = Date()
        do {
            (data, status) = try await fetchFailingOver(req, kind: kind)
        } catch {
            HangLog.shared.error("request \(kind)", error)
            if Self.isOffline(error), let value: T = await offlineCopy(key, url: url, policy: policy) { return value }
            throw error
        }
        HangLog.shared.note(String(format: "net %@ %d %.1f s", kind, status, Date().timeIntervalSince(started)))
        guard (200..<300).contains(status) else {
            HangLog.shared.error("request \(kind)", APIError(message: "HTTP \(status)"))
            if status >= 500, let value: T = await offlineCopy(key, url: url, policy: policy) { return value }
            if let err = try? JSONDecoder().decode(ServerError.self, from: data) {
                throw APIError(message: err.error)
            }
            throw APIError(message: L10n.f("Server error (HTTP %lld).", status))
        }
        let value: T
        do {
            let decodeTrace = PerformanceTrace.begin("Decode transit response")
            defer { PerformanceTrace.end(decodeTrace, "Decode transit response") }
            value = try decoder.decode(T.self, from: data)
        } catch {
            HangLog.shared.error("answer \(kind)", error)
            throw APIError(message: L10n.t("Unexpected response from the routing service."))
        }
        // Live answers (fresh for seconds) go to disk at most every two minutes; the offline copy
        // does not need to be newer, and the flash is spared a write on every refresh.
        cache.store(key, data: data, url: url.absoluteString, persist: policy.persist, decoded: value,
                    diskInterval: policy.maxAge < 600 ? ResponseCache.minDiskInterval : 0)
        await Connectivity.shared.reachedServer()
        return value
    }

    static func encodePlus(_ query: String?) -> String? {
        query?.replacingOccurrences(of: "+", with: "%2B")
    }

    /// "/api/v5/plan" → "plan": what kind of request, for the diagnostics steps.
    static func requestKind(_ path: String) -> String {
        path.split(separator: "/").last.map(String.init) ?? path
    }

    private let running = RequestCoalescer()

    /// One download for identical requests that overlap (two screens or a quick reload asking for
    /// the same board), and one retry for a dropped connection or a busy server.
    private func fetch(_ req: URLRequest) async throws -> (Data, Int) {
        let session = req.timeoutInterval > 20 && req.timeoutInterval < 60 ? slowSession : session
        return try await running.run(req.url?.absoluteString ?? "") {
            try await Self.withRetry {
                let (d, response) = try await session.data(for: req)
                return (d, (response as? HTTPURLResponse)?.statusCode ?? 0)
            }
        }
    }

    /// Transitous first; while it is down the backup first (for `ServerFailover.holdOff`), so not
    /// every request waits for a dead server. Cache keys and offline copies keep Transitous' URL.
    private func fetchFailingOver(_ req: URLRequest, kind: String) async throws -> (Data, Int) {
        guard let url = req.url, !backups.isEmpty else { return try await fetch(req) }
        let servers = failover.primaryFirst() ? [base] + backups : backups + [base]
        var lastAnswer: (Data, Int)?
        var lastError: Error?
        for (index, server) in servers.enumerated() {
            var attempt = req
            attempt.url = Self.moved(url, to: server)
            // A second server is a fallback, not a reason to keep someone waiting twice as long.
            if index > 0 { attempt.timeoutInterval = min(req.timeoutInterval, Self.backupTimeout) }
            let isPrimary = server == base
            do {
                let answer = try await fetch(attempt)
                if Self.serverFailed(status: answer.1) {
                    if isPrimary { failover.primaryFailed() }
                    lastAnswer = answer
                    continue
                }
                if isPrimary { failover.primaryAnswered() } else { HangLog.shared.note("backup server \(kind)") }
                return answer
            } catch {
                guard Self.shouldFailOver(error) else { throw error }
                if isPrimary { failover.primaryFailed() }
                lastError = error
            }
        }
        if let lastAnswer { return lastAnswer }
        // No server could be reached: most likely the iPhone's connection, not Transitous.
        // Asking the backup first next time would only add its wait to every request.
        failover.reset()
        throw lastError ?? URLError(.cannotConnectToHost)
    }

    static let backupTimeout: TimeInterval = 15

    /// Same path and query on another server.
    static func moved(_ url: URL, to server: URL) -> URL {
        guard var c = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return url }
        c.scheme = server.scheme
        c.host = server.host
        c.port = server.port
        return c.url ?? url
    }

    /// The server is overloaded or broken; another one may answer. 4xx is the request's fault.
    static func serverFailed(status: Int) -> Bool { status == 429 || status >= 500 }

    /// The server could not be reached. Not when the iPhone itself is offline or the request was
    /// cancelled: another server would fail the same way.
    static func shouldFailOver(_ error: Error) -> Bool {
        guard let e = error as? URLError else { return false }
        switch e.code {
        case .cannotConnectToHost, .cannotFindHost, .dnsLookupFailed, .timedOut, .networkConnectionLost,
             .secureConnectionFailed, .badServerResponse, .cannotParseResponse, .serverCertificateUntrusted,
             .serverCertificateHasBadDate, .serverCertificateNotYetValid, .serverCertificateHasUnknownRoot:
            return true
        default:
            return false
        }
    }

    /// Tries once more after `delay` when the connection dropped or the server answered 502–504.
    /// Not for timeouts: the user has waited long enough by then.
    static func withRetry(delay: Duration = .milliseconds(800),
                          _ op: @Sendable () async throws -> (Data, Int)) async throws -> (Data, Int) {
        do {
            let r = try await op()
            guard [502, 503, 504].contains(r.1) else { return r }
        } catch let e as URLError where [.networkConnectionLost, .cannotConnectToHost, .cannotFindHost, .dnsLookupFailed].contains(e.code) {
            // Fall through to the second attempt.
        }
        try await Task.sleep(for: delay)
        return try await op()
    }

    /// Saved response for the same request (or the same slot, e.g. the last nearby board).
    private func offlineCopy<T: Decodable>(_ key: String, url: URL, policy: CachePolicy) async -> T? {
        guard policy.persist, let e = cache.stale(key),
              Date().timeIntervalSince(e.date) <= (policy.maxAge >= 3600 ? 7 * 24 * 3600 : 24 * 3600),
              Self.sameOfflineRequest(savedURL: e.url, requestedURL: url, slot: policy.slot),
              let value = try? decoder.decode(T.self, from: e.data) else { return nil }
        await Connectivity.shared.servedOffline(from: e.date)
        return value
    }

    /// Tiny GPS refinements do not invalidate a 20-second board. All other parameters must match.
    static func sameNearbyRequest(savedURL: String, requestedURL: URL, maxDistance: Double = 150) -> Bool {
        func parts(_ url: URL) -> (LatLon, [String: String])? {
            guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
                  let center = components.queryItems?.first(where: { $0.name == "center" })?.value.flatMap(LatLon.init(apiValue:)) else { return nil }
            var fields: [String: String] = [:]
            for item in components.queryItems ?? [] where item.name != "center" { fields[item.name] = item.value ?? "" }
            return (center, fields)
        }
        guard let saved = URL(string: savedURL), saved.host == requestedURL.host, saved.path == requestedURL.path,
              let a = parts(saved), let b = parts(requestedURL), a.1 == b.1 else { return false }
        return a.0.distance(to: b.0) < maxDistance
    }

    static func sameOfflineRequest(savedURL: String, requestedURL: URL, slot: String?) -> Bool {
        if savedURL == requestedURL.absoluteString { return true }
        guard slot?.hasPrefix("nearby") == true else { return false }
        func center(_ url: String) -> LatLon? {
            URLComponents(string: url)?.queryItems?.first { $0.name == "center" }?.value.flatMap(LatLon.init(apiValue:))
        }
        guard let a = center(savedURL), let b = center(requestedURL.absoluteString) else { return false }
        return a.distance(to: b) < 400
    }

    /// No connection (as opposed to a cancelled request or a server answer).
    static func isOffline(_ error: Error) -> Bool {
        guard let e = error as? URLError else { return false }
        switch e.code {
        case .notConnectedToInternet, .networkConnectionLost, .timedOut, .cannotFindHost, .cannotConnectToHost,
             .dnsLookupFailed, .dataNotAllowed, .internationalRoamingOff:
            return true
        default:
            return false
        }
    }
}

enum DateParsing {
    private static let plain: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()
    private static let fractional: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    static func parse(_ s: String) -> Date? { fastUTC(s) ?? plain.date(from: s) ?? fractional.date(from: s) }

    /// `2026-10-12T14:05:00Z` (optionally with fractional seconds), the form MOTIS sends for every time.
    /// A route response has thousands of these; reading them directly is many times faster than
    /// ISO8601DateFormatter. Anything else (offsets, odd lengths) falls back to the formatters.
    static func fastUTC(_ s: String) -> Date? {
        s.utf8.withContiguousStorageIfAvailable { b -> Date? in
            let n = b.count
            guard n >= 20, b[4] == 45, b[7] == 45, b[10] == 84, b[13] == 58, b[16] == 58, b[n - 1] == 90 else { return nil }
            func num(_ from: Int, _ len: Int) -> Int? {
                var v = 0
                for i in from..<(from + len) {
                    let d = Int(b[i]) - 48
                    guard d >= 0, d <= 9 else { return nil }
                    v = v * 10 + d
                }
                return v
            }
            guard let y = num(0, 4), let mo = num(5, 2), let d = num(8, 2),
                  let h = num(11, 2), let mi = num(14, 2), let sec = num(17, 2),
                  (1...12).contains(mo), (1...31).contains(d), h < 24, mi < 60, sec < 61 else { return nil }
            var fraction = 0.0
            if n > 20 {
                // ".123Z"
                guard b[19] == 46, n > 21 else { return nil }
                var scale = 0.1
                for i in 20..<(n - 1) {
                    let digit = Int(b[i]) - 48
                    guard digit >= 0, digit <= 9 else { return nil }
                    fraction += Double(digit) * scale
                    scale /= 10
                }
            }
            // Days from civil (Howard Hinnant), proleptic Gregorian, UTC.
            let yy = mo <= 2 ? y - 1 : y
            let era = (yy >= 0 ? yy : yy - 399) / 400
            let yoe = yy - era * 400
            let mp = (mo + 9) % 12
            let doy = (153 * mp + 2) / 5 + d - 1
            let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            let days = era * 146097 + doe - 719468
            let seconds = Double(days * 86400 + h * 3600 + mi * 60 + sec) + fraction
            return Date(timeIntervalSince1970: seconds)
        } ?? nil
    }
    static func format(_ d: Date) -> String { plain.string(from: d) }
}

/// Shares downloads by URL. Each caller can leave immediately on cancellation;
/// only abandoning the last waiter cancels the underlying URLSession request.
actor RequestCoalescer {
    typealias Result = (Data, Int)
    private struct Flight {
        let id: UUID
        let task: Task<Void, Never>
        var waiters: [UUID: CheckedContinuation<Result, Error>]
    }
    private var running: [String: Flight] = [:]

    func run(_ key: String, _ load: @escaping @Sendable () async throws -> Result) async throws -> Result {
        try Task.checkCancellation()
        let waiter = UUID()
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                guard !Task.isCancelled else { continuation.resume(throwing: CancellationError()); return }
                if running[key] != nil {
                    running[key]!.waiters[waiter] = continuation
                } else {
                    let id = UUID()
                    let task = Task {
                        let result: Swift.Result<Result, Error>
                        do { result = .success(try await load()) } catch { result = .failure(error) }
                        finish(key, id: id, result: result)
                    }
                    running[key] = Flight(id: id, task: task, waiters: [waiter: continuation])
                }
            }
        } onCancel: {
            Task { await self.cancel(key, waiter: waiter) }
        }
    }

    private func finish(_ key: String, id: UUID, result: Swift.Result<Result, Error>) {
        guard let flight = running[key], flight.id == id else { return }
        running[key] = nil
        for waiter in flight.waiters.values { waiter.resume(with: result) }
    }

    private func cancel(_ key: String, waiter: UUID) {
        guard let continuation = running[key]?.waiters.removeValue(forKey: waiter) else { return }
        continuation.resume(throwing: CancellationError())
        if let flight = running[key], flight.waiters.isEmpty {
            running[key] = nil
            flight.task.cancel()
        }
    }

    var count: Int { running.count }
}

/// Remembers that Transitous failed, so the next requests go to the backup server first.
/// After `holdOff` Transitous is asked first again; an answer from it ends the failover.
final class ServerFailover: @unchecked Sendable {
    static let holdOff: TimeInterval = 180
    private let lock = NSLock()
    private var primaryDownSince: Date?

    func primaryFirst(now: Date = Date()) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard let since = primaryDownSince else { return true }
        return now.timeIntervalSince(since) >= Self.holdOff
    }

    func primaryFailed(now: Date = Date()) {
        lock.lock(); primaryDownSince = now; lock.unlock()
    }

    func primaryAnswered() {
        lock.lock(); primaryDownSince = nil; lock.unlock()
    }

    func reset() { primaryAnswered() }

    /// Requests go to the backup server right now.
    var onBackup: Bool { !primaryFirst() }
}

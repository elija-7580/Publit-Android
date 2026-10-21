import Foundation
import XCTest
@testable import Publit

private struct TestRoute: RouteStyled {
    var mode = "SUBWAY"
    var routeColor: String? = nil
    var routeTextColor: String? = nil
    var routeShortName: String? = nil
    var displayName: String? = nil
}

final class TransitEnhancementTests: XCTestCase {
    private func fixture(_ name: String) throws -> Data {
        try Data(contentsOf: URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("Fixtures/\(name).json"))
    }
    private func itinerary() throws -> Itinerary {
        try TransitousClient.makeDecoder().decode(PlanResponse.self, from: fixture("plan")).itineraries[0]
    }
    private func place(_ n: Int) -> SavedPlace {
        SavedPlace(id: "p\(n)", name: "Place \(n)", subtitle: nil, lat: 48, lon: 11, stopId: nil)
    }
    private func covered(_ trip: Itinerary, valid: Bool = true) -> Itinerary {
        var legs = trip.legs
        for i in legs.indices where legs[i].isTransit { legs[i].fareTransferIndex = valid ? 0 : nil; legs[i].effectiveFareLegIndex = valid ? 0 : nil }
        var result = Itinerary(id: trip.id, duration: trip.duration, startTime: trip.startTime, endTime: trip.endTime,
                               transfers: trip.transfers, legs: legs)
        result.fareTransfers = trip.fareTransfers
        return result
    }
    private func product(_ amount: Decimal, currency: String = "EUR", child: Bool = false) -> FareProduct {
        FareProduct(name: "Single ticket", amount: amount, currency: currency,
                    riderCategory: child ? .init(riderCategoryName: "Child", isDefaultFareCategory: false) : nil, media: nil)
    }

    func testLineAndSpecificTrainIdentityAreSeparate() {
        let route = TestRoute(mode: "RAIL", routeShortName: " ICE ", displayName: "ICE 615")
        XCTAssertEqual(route.lineName, "ICE")
        XCTAssertEqual(route.serviceDetail, "ICE 615")
        XCTAssertNil(TestRoute(routeShortName: "RE3", displayName: "RE 3").serviceDetail)
    }
    func testMissingColoursUseModeColourAndSmallTextHasAAContrast() {
        XCTAssertEqual(TestRoute(routeShortName: "U1").lineColors.background, ModeStyle.defaultHex("SUBWAY"))
        XCTAssertNotEqual(TestRoute(mode: "REGIONAL_RAIL").lineColors.background, "4D4D59")
        let pair = LineColors.resolve(routeColor: "777777", routeTextColor: "FFFFFF", fallback: "000000")
        XCTAssertEqual(pair.text, "000000")
        XCTAssertGreaterThanOrEqual(LineColors.contrast(LineColors.RGB(hex: pair.background)!, LineColors.RGB(hex: pair.text)!), 4.5)
    }
    func testMissingDirectionIsNotReplacedWithAlightingStop() throws {
        var data = try XCTUnwrap(JSONSerialization.jsonObject(with: fixture("plan")) as? [String: Any])
        var trips = try XCTUnwrap(data["itineraries"] as? [[String: Any]])
        var legs = try XCTUnwrap(trips[0]["legs"] as? [[String: Any]])
        for i in legs.indices { legs[i]["headsign"] = "  " }
        trips[0]["legs"] = legs; data["itineraries"] = trips
        let trip = try TransitousClient.makeDecoder().decode(PlanResponse.self, from: JSONSerialization.data(withJSONObject: data)).itineraries[0]
        XCTAssertTrue(trip.transitLegs.allSatisfy { $0.directionName == nil })
    }
    func testAlertsDeduplicateAndRespectCommunicationAndImpactWindows() {
        let now = Date(timeIntervalSince1970: 10_000)
        var closure = Alert(headerText: "Track closed", descriptionText: "Use replacement bus", url: nil)
        closure.effect = "NO_SERVICE"; closure.severityLevel = "SEVERE"
        closure.communicationPeriod = [.init(start: now.addingTimeInterval(-10), end: now.addingTimeInterval(20))]
        closure.impactPeriod = [.init(start: now.addingTimeInterval(100), end: now.addingTimeInterval(200))]
        XCTAssertFalse(closure.visible(now: now))
        XCTAssertTrue(closure.visible(now: now, from: now.addingTimeInterval(110), to: now.addingTimeInterval(130)))
        XCTAssertFalse(closure.visible(now: now.addingTimeInterval(30), from: now.addingTimeInterval(110)))
        XCTAssertEqual(Alert.relevant([closure, closure], now: now, from: now.addingTimeInterval(110)).count, 1)
        XCTAssertEqual(closure.priority, 0)
        XCTAssertTrue(closure.isReplacementInfo)
    }
    func testFareEstimateDoesNotDoubleCountCoveredTransferLegs() throws {
        var trip = try itinerary()
        trip = covered(trip)
        trip.fareTransfers = [.init(rule: "A_AB", transferProducts: [product(1)], effectiveFareLegProducts: [[[product(3)]], [[product(99)]]])]
        XCTAssertEqual(trip.fareEstimate, .init(amount: 4, currency: "EUR"))
        trip.fareTransfers = [.init(rule: "AB", transferProducts: [product(4)], effectiveFareLegProducts: [[[product(99)]]])]
        XCTAssertEqual(trip.fareEstimate?.amount, 4)
    }
    func testFareEstimateRejectsChildOnlyAmbiguousAndUncoveredPrices() throws {
        var trip = try itinerary()
        trip = covered(trip)
        trip.fareTransfers = [.init(rule: nil, transferProducts: nil, effectiveFareLegProducts: [[[product(1, child: true)]]])]
        XCTAssertNil(trip.fareEstimate)
        trip.fareTransfers = [.init(rule: nil, transferProducts: nil, effectiveFareLegProducts: [[[product(3)], [product(4)]]])]
        XCTAssertNil(trip.fareEstimate)
        trip.fareTransfers = [.init(rule: nil, transferProducts: nil, effectiveFareLegProducts: [[[product(3), product(4, currency: "CHF")]]])]
        XCTAssertNil(trip.fareEstimate)
        trip = covered(trip, valid: false)
        XCTAssertNil(trip.fareEstimate)
    }
    @MainActor
    func testHistoryIsBoundedDeduplicatedPersistedAndClearable() async {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { JSONPersistence.flush(); try? FileManager.default.removeItem(at: dir) }
        let history = SearchHistoryStore(directory: dir)
        for n in 1...25 { history.record(from: .currentLocation, to: .place(place(n)), mode: .now, date: .now) }
        XCTAssertEqual(history.entries.count, 20)
        history.record(from: .currentLocation, to: .place(place(25)), mode: .now, date: .now)
        XCTAssertEqual(history.entries.count, 20)
        XCTAssertEqual(SearchHistoryStore(directory: dir).entries.first?.to, .place(place(25)))
        history.clear()
        XCTAssertTrue(SearchHistoryStore(directory: dir).entries.isEmpty)
    }
    @MainActor
    func testReplayingAnOldSearchGetsFreshDepartures() async {
        let plan = PlanModel()
        let now = Date()
        let recent = RecentSearch(id: "x", from: .currentLocation, to: .place(place(1)), mode: .arriveBy,
                                  date: now.addingTimeInterval(-86400), searchedAt: now.addingTimeInterval(-86400))
        recent.apply(to: plan, now: now)
        XCTAssertEqual(plan.timeMode, .now)
        XCTAssertEqual(plan.date, now)
        XCTAssertEqual(plan.to, recent.to)
    }
    @MainActor
    func testUnsavedTripStaysUntilTheScreenCloses() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { JSONPersistence.flush(); try? FileManager.default.removeItem(at: dir) }
        let store = TripStore(directory: dir)
        let it = try itinerary()
        let trip = store.save(it, from: "A", to: "B")
        store.markForRemoval(trip.id)
        XCTAssertNotNil(store.trip(trip.id), "an accidental unsave must not delete at once")
        XCTAssertTrue(store.isPendingRemoval(trip.id))
        store.keep(trip.id)
        XCTAssertEqual(store.removePending(), [], "saved again: nothing is deleted on close")
        XCTAssertNotNil(store.trip(trip.id))
        store.markForRemoval(trip.id)
        JSONPersistence.flush()
        XCTAssertNotNil(TripStore(directory: dir).trip(trip.id), "pending is not persisted: a relaunch keeps the trip")
        XCTAssertEqual(store.removePending(), [trip.id])
        XCTAssertNil(store.trip(trip.id))
        XCTAssertFalse(store.isPendingRemoval(trip.id))
        JSONPersistence.flush()
        XCTAssertNil(TripStore(directory: dir).trip(trip.id))
    }

    @MainActor
    func testStartedTripIsPersistentAndOnlyOneTripIsActive() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { JSONPersistence.flush(); try? FileManager.default.removeItem(at: dir) }
        let store = TripStore(directory: dir)
        var first = try itinerary()
        let a = store.save(first, from: "A", to: "B")
        first = Itinerary(id: "another", duration: first.duration, startTime: first.startTime.addingTimeInterval(3600),
                          endTime: first.endTime.addingTimeInterval(3600), transfers: 0, legs: [])
        let b = store.save(first, from: "C", to: "D")
        store.start(a.id); store.start(b.id)
        XCTAssertEqual(store.activeTripId, b.id)
        XCTAssertFalse(store.wantsLiveActivity(a.id))
        XCTAssertEqual(TripStore(directory: dir).activeTripId, b.id)
        store.stop(b.id)
        XCTAssertNil(TripStore(directory: dir).activeTripId)
    }
    @MainActor
    func testLiveBarShowsStartedTripOtherwiseTheSavedTripUnderWay() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { JSONPersistence.flush(); try? FileManager.default.removeItem(at: dir) }
        let store = TripStore(directory: dir)
        let it = try itinerary()
        let a = store.save(it, from: "A", to: "B")
        let later = Itinerary(id: "later", duration: it.duration, startTime: it.startTime.addingTimeInterval(3600),
                              endTime: it.endTime.addingTimeInterval(3600), transfers: 0, legs: [])
        let b = store.save(later, from: "C", to: "D")
        XCTAssertNil(store.current(now: a.startTime.addingTimeInterval(-TripStore.liveLead - 60)), "too early: no bar")
        XCTAssertEqual(store.current(now: a.startTime.addingTimeInterval(-10 * 60))?.id, a.id, "shortly before departure")
        XCTAssertEqual(store.current(now: a.endTime.addingTimeInterval(4 * 60))?.id, a.id, "still shows Arrived briefly")
        XCTAssertEqual(store.current(now: b.startTime.addingTimeInterval(-60))?.id, a.id, "two overlap: the earlier one")
        XCTAssertEqual(store.current(now: a.endTime.addingTimeInterval(10 * 60))?.id, b.id)
        XCTAssertNil(store.current(now: b.endTime.addingTimeInterval(10 * 60)), "after arrival the bar goes away")
        store.start(b.id)
        XCTAssertEqual(store.current(now: a.startTime)?.id, b.id, "a trip started by hand wins")
        store.markForRemoval(b.id)
        XCTAssertEqual(store.current(now: a.startTime)?.id, a.id, "an unsaved trip is not shown")
    }
    @MainActor
    func testAsyncTicketImportPreservesExactImageBytes() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { JSONPersistence.flush(); try? FileManager.default.removeItem(at: dir) }
        let store = TicketStore(directory: dir)
        let original = Data([0, 1, 2, 3, 254, 255])
        let ticket = try await store.addAsync(name: "Test", kind: .pass, image: original, appLink: nil, validUntil: nil)
        XCTAssertEqual(try Data(contentsOf: XCTUnwrap(store.imageURL(ticket))), original)
        XCTAssertEqual(TicketStore(directory: dir).tickets.count, 1)
    }
    func testConcurrentLiveRefreshesShareOneRequestAndFailuresCanRetry() async throws {
        actor Counter {
            var value = 0
            func next() -> Int { value += 1; return value }
        }
        let counter = Counter(); let trip = try itinerary()
        let coordinator = TripRefreshCoordinator { _ in
            _ = await counter.next()
            try await Task.sleep(for: .milliseconds(40))
            return trip
        }
        try await withThrowingTaskGroup(of: Itinerary.self) { group in
            for _ in 0..<12 { group.addTask { try await coordinator.refresh("same") } }
            for try await value in group { XCTAssertEqual(value.id, trip.id) }
        }
        let calls = await counter.value
        XCTAssertEqual(calls, 1)
        _ = try await coordinator.refresh("same", now: .now.addingTimeInterval(10))
        let afterExpiry = await counter.value
        XCTAssertEqual(afterExpiry, 2)
        let failed = TripRefreshCoordinator { _ in
            if await counter.next() == 3 { throw URLError(.notConnectedToInternet) }
            return trip
        }
        do { _ = try await failed.refresh("fail"); XCTFail("expected error") } catch { }
        _ = try await failed.refresh("fail")
    }
    func testGroupingPerformanceAndResultParity() throws {
        let sample = try TransitousClient.makeDecoder().decode(StopTimesResponse.self, from: fixture("stoptimes_marienplatz")).stopTimes
        let input = (0..<30).flatMap { _ in sample }
        func legacy() -> [StopGroup] {
            var groups: [StopGroup] = []
            for st in input.sorted(by: { $0.departureTime < $1.departureTime }) {
                let name = StopGrouping.normalizedName(st.place.name); let c = st.place.coordinate
                if let i = groups.firstIndex(where: { StopGrouping.normalizedName($0.name) == name && $0.coordinate.distance(to: c) <= StopGrouping.mergeRadius }) {
                    groups[i].departures.append(st)
                    if let sid = st.place.stopId, !groups[i].stopIds.contains(sid) { groups[i].stopIds.append(sid) }
                } else {
                    groups.append(.init(id: st.place.stationId, name: st.place.name, coordinate: c,
                                        stopIds: st.place.stopId.map { [$0] } ?? [], departures: [st], distance: nil))
                }
            }
            return groups
        }
        let old = legacy(); let new = StopGrouping.group(input, around: nil, separatePoles: false)
        XCTAssertEqual(old.map(\.id), new.map(\.id)); XCTAssertEqual(old.map { $0.departures.count }, new.map { $0.departures.count })
        func timing(_ work: () -> Void) -> Double {
            let begin = Date(); for _ in 0..<5 { work() }; return Date().timeIntervalSince(begin) * 1000 / 5
        }
        let oldMS = timing { _ = legacy() }; let newMS = timing { _ = StopGrouping.group(input, around: nil, separatePoles: false) }
        print(String(format: "PERF grouping records=%d legacy_ms=%.3f indexed_ms=%.3f ratio=%.2f", input.count, oldMS, newMS, oldMS / max(newMS, 0.001)))
    }
}

final class RouteFromStopTests: XCTestCase {
    private let stop = SavedPlace(id: "s1", name: "Stop", subtitle: nil, lat: 48.1, lon: 11.5, stopId: "s1")
    private let other = SavedPlace(id: "p2", name: "Other", subtitle: nil, lat: 48.2, lon: 11.6, stopId: nil)

    @MainActor func testRouteFromStopKeepsOtherDestinationAndOpensPlan() async {
        let app = AppState()
        app.plan.to = .place(other)
        app.route(from: stop)
        XCTAssertEqual(app.plan.from, .place(stop))
        XCTAssertEqual(app.plan.to, .place(other))
        XCTAssertEqual(app.tab, .plan)
    }

    @MainActor func testRouteFromAndToSameStopNeverBothEnds() async {
        let app = AppState()
        app.route(to: stop)
        app.route(from: stop)
        XCTAssertEqual(app.plan.from, .place(stop))
        XCTAssertNil(app.plan.to)
        app.route(to: stop)
        XCTAssertEqual(app.plan.from, .currentLocation)
        XCTAssertEqual(app.plan.to, .place(stop))
    }

    @MainActor func testClearingDestinationNeedsSearchAgain() async {
        let app = AppState()
        app.route(to: stop)
        XCTAssertFalse(app.plan.searchStarted, "routes load only after Search routes")
        app.plan.searchStarted = true
        app.plan.to = .place(other)
        XCTAssertTrue(app.plan.searchStarted, "a new destination after a search searches again")
        app.plan.to = nil
        XCTAssertFalse(app.plan.searchStarted)
    }
}

import XCTest
@testable import Publit

final class ModeFilterTests: XCTestCase {
    func testGroupsCoverTheMOTISModes() {
        XCTAssertEqual(ModeGroup.of("HIGHSPEED_RAIL"), .train)
        XCTAssertEqual(ModeGroup.of("REGIONAL_RAIL"), .train)
        XCTAssertEqual(ModeGroup.of("SUBURBAN"), .suburban)
        XCTAssertEqual(ModeGroup.of("METRO"), .subway)
        XCTAssertEqual(ModeGroup.of("COACH"), .bus)
        XCTAssertEqual(ModeGroup.of("AERIAL_LIFT"), .other)
        for group in ModeGroup.allCases {
            for mode in group.apiModes { XCTAssertEqual(ModeGroup.of(mode), group, mode) }
        }
    }

    func testEmptyMeansAll() {
        let all = ModeFilter()
        XCTAssertFalse(all.isActive)
        XCTAssertNil(all.apiValue)
        XCTAssertTrue(all.allows("BUS"))
        XCTAssertFalse(ModeFilter(included: Set(ModeGroup.allCases)).isActive)
    }

    func testChipsShowOnlyTheChosenModes() {
        var f = ModeFilter()
        f.toggleOnly(.tram)
        XCTAssertEqual(f.included, [.tram])
        XCTAssertTrue(f.allows("TRAM"))
        XCTAssertFalse(f.allows("BUS"))
        XCTAssertTrue(f.allows("WALK"), "walking is never filtered")
        f.toggleOnly(.bus)
        XCTAssertEqual(f.apiValue, "TRAM,BUS,COACH")
        f.toggleOnly(.tram)
        f.toggleOnly(.bus)
        XCTAssertFalse(f.isActive, "removing the last chip goes back to all")
    }

    func testSwitchesKeepAtLeastOneMode() {
        var f = ModeFilter()
        f.set(.bus, on: false)
        XCTAssertTrue(f.isActive)
        XCTAssertFalse(f.allows(.bus))
        XCTAssertTrue(f.allows(.train))
        for g in ModeGroup.allCases where g != .train { f.set(g, on: false) }
        f.set(.train, on: false)
        XCTAssertEqual(f.included, [.train])
        for g in ModeGroup.allCases { f.set(g, on: true) }
        XCTAssertFalse(f.isActive)
        XCTAssertEqual(f.included, [])
    }

    func testStorageRoundTrip() {
        let f = ModeFilter(included: [.bus, .train])
        XCTAssertEqual(f.storageValue, "train,bus")
        XCTAssertEqual(ModeFilter(storageValue: f.storageValue), f)
        XCTAssertEqual(ModeFilter(storageValue: "nonsense,tram"), ModeFilter(included: [.tram]))
    }

    func testSummaryNamesTheShorterSide() {
        var f = ModeFilter()
        f.set(.bus, on: false)
        XCTAssertEqual(f.summary, "Without Bus")
        XCTAssertEqual(ModeFilter(included: [.tram]).summary, "Tram")
    }

    func testPresentGroupsInFixedOrder() {
        XCTAssertEqual(ModeGroup.present(in: ["BUS", "WALK", "SUBURBAN", "BUS", "ICE_UNKNOWN"]), [.train, .suburban, .bus])
    }
}

final class RouteOptionsTests: XCTestCase {
    private func query(_ o: RouteOptions) -> [String: String] {
        let q = TransitousClient.planQuery(from: LatLon(lat: 48.137, lon: 11.575), to: LatLon(lat: 48.353, lon: 11.786),
                                           time: nil, arriveBy: false, maxWalk: o.maxWalkMinutes * 60, options: o)
        return Dictionary(uniqueKeysWithValues: q.map { ($0.name, $0.value ?? "") })
    }

    func testDefaultsAskForTheComparisonOnly() {
        let d = query(RouteOptions())
        // Walking comes with the routes; cycling and driving are asked apart (driving is slow).
        XCTAssertEqual(d["directModes"], "WALK")
        XCTAssertEqual(RouteOptions().comparisonModes, ["BIKE", "CAR"])
        XCTAssertNil(d["transitModes"])
        XCTAssertNil(d["maxTransfers"])
        XCTAssertNil(d["pedestrianProfile"])
        XCTAssertTrue(RouteOptions().isDefault)
        XCTAssertNil(RouteOptions().summary)
    }

    func testFiltersBecomeQueryParameters() {
        var o = RouteOptions()
        o.modes = ModeFilter(included: [.train, .suburban])
        o.maxTransfers = 1
        o.maxWalkMinutes = 10
        o.wheelchair = true
        o.compareStreetModes = false
        let d = query(o)
        XCTAssertEqual(d["transitModes"], "HIGHSPEED_RAIL,LONG_DISTANCE,NIGHT_RAIL,REGIONAL_FAST_RAIL,REGIONAL_RAIL,RAIL,SUBURBAN")
        XCTAssertEqual(d["maxTransfers"], "1")
        XCTAssertEqual(d["maxPreTransitTime"], "600")
        XCTAssertEqual(d["maxPostTransitTime"], "600")
        XCTAssertEqual(d["pedestrianProfile"], "WHEELCHAIR")
        XCTAssertEqual(d["directModes"], "WALK")
        XCTAssertEqual(o.comparisonModes, [])
        XCTAssertEqual(o.activeCount, 4)
        XCTAssertEqual(o.summary, "Train, S-Bahn · Changes: up to 1 · Walk up to 10 min · Step-free")
    }

    func testStreetComparisonAsksForStreetsOnly() {
        let q = TransitousClient.streetQuery(from: LatLon(lat: 48.8443, lon: 2.3744), to: LatLon(lat: 48.8809, lon: 2.3553),
                                             time: nil, arriveBy: false, mode: "CAR")
        let d = Dictionary(uniqueKeysWithValues: q.map { ($0.name, $0.value ?? "") })
        XCTAssertEqual(d["directModes"], "CAR")
        XCTAssertEqual(d["transitModes"], "", "an empty transitModes keeps the server from searching transit")
        XCTAssertNil(d["withFares"])
        XCTAssertNil(d["time"])
    }

    func testOptionsChangeTheQueryKey() async {
        await MainActor.run {
            let m = PlanModel()
            let before = m.queryKey
            m.options.maxTransfers = 0
            XCTAssertNotEqual(m.queryKey, before)
        }
    }

    func testStoredBetweenLaunches() throws {
        let defaults = try XCTUnwrap(UserDefaults(suiteName: "publit-test-\(UUID().uuidString)"))
        XCTAssertEqual(RouteOptions.load(defaults), RouteOptions())
        var o = RouteOptions()
        o.maxTransfers = 2
        o.modes = ModeFilter(included: [.bus])
        o.save(defaults)
        XCTAssertEqual(RouteOptions.load(defaults), o)
    }
}

final class StreetComparisonTests: XCTestCase {
    private func directModes() throws -> PlanResponse {
        try TransitousClient.makeDecoder().decode(PlanResponse.self, from: Fixtures.data("plan_direct_modes"))
    }

    func testWalkBikeAndCarInThatOrder() throws {
        let c = StreetComparison.from(try directModes().direct)
        XCTAssertEqual(c.map(\.mode), ["WALK", "BIKE", "CAR"])
        XCTAssertLessThan(c[2].duration, c[1].duration)
        XCTAssertNotNil(c[0].distance)
    }

    func testCarAndBikeNeverJoinTheRouteList() throws {
        let p = try directModes()
        let merged = PlanModel.merge(transit: p.itineraries, direct: p.direct)
        XCTAssertFalse(merged.contains { ["CAR", "BIKE"].contains(StreetComparison.streetMode($0) ?? "") })
        let compare = PlanModel.comparisons(direct: p.direct, listed: merged, enabled: true)
        XCTAssertTrue(compare.contains { $0.mode == "CAR" })
        // Walking appears once: in the list when it is worth it, otherwise in the comparison.
        let walkListed = merged.contains { StreetComparison.streetMode($0) == "WALK" }
        XCTAssertEqual(compare.contains { $0.mode == "WALK" }, !walkListed)
        XCTAssertTrue(PlanModel.comparisons(direct: p.direct, listed: merged, enabled: false).isEmpty)
    }
}

final class LineGroupingTests: XCTestCase {
    func testOneEntryPerLineWithDirections() throws {
        let res = try TransitousClient.makeDecoder().decode(StopTimesResponse.self, from: Fixtures.data("stoptimes_marienplatz"))
        let lines = LineGrouping.lines(res.stopTimes)
        XCTAssertFalse(lines.isEmpty)
        XCTAssertEqual(Set(lines.map(\.key)).count, lines.count)
        XCTAssertEqual(lines.reduce(0) { $0 + $1.departures.count }, res.stopTimes.count, "no departure lost")
        for line in lines {
            XCTAssertEqual(line.departures.map(\.departureTime), line.departures.map(\.departureTime).sorted())
            XCTAssertTrue(line.byDirection(perDirection: 2).allSatisfy { $0.departures.count <= 2 })
        }
        // Trains before buses.
        let order = lines.map { ModeGroup.allCases.firstIndex(of: $0.group)! }
        XCTAssertEqual(order, order.sorted())
    }

    func testFilterLeavesOutOtherModes() throws {
        let res = try TransitousClient.makeDecoder().decode(StopTimesResponse.self, from: Fixtures.data("stoptimes_marienplatz"))
        let groups = ModeGroup.present(in: res.stopTimes.map(\.mode))
        guard let only = groups.first else { return XCTFail("fixture has no transit") }
        let lines = LineGrouping.lines(res.stopTimes, filter: ModeFilter(included: [only]))
        XCTAssertFalse(lines.isEmpty)
        XCTAssertTrue(lines.allSatisfy { $0.group == only })
    }

    func testNaturalLineOrder() {
        XCTAssertEqual(["10", "2", "U3"].sorted { $0.compare($1, options: [.numeric]) == .orderedAscending }, ["2", "10", "U3"])
    }
}

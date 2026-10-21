import XCTest
@testable import Publit

private func plan(_ name: String) throws -> PlanResponse {
    try TransitousClient.makeDecoder().decode(PlanResponse.self, from: Fixtures.data(name))
}

private func tempDir() -> URL {
    let u = FileManager.default.temporaryDirectory.appendingPathComponent("publit-\(UUID().uuidString)")
    try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
    return u
}

final class PlanQueryTests: XCTestCase {
    /// Regression: with the server's 15-min default, Bari Centrale → airport found nothing.
    func testPlanAsksForLongerWalksAndRoutedTransfers() {
        let q = TransitousClient.planQuery(from: LatLon(lat: 41.1177, lon: 16.8696), to: LatLon(lat: 41.1375, lon: 16.7605),
                                           time: nil, arriveBy: false)
        let d = Dictionary(uniqueKeysWithValues: q.map { ($0.name, $0.value ?? "") })
        XCTAssertEqual(d["useRoutedTransfers"], "true")
        XCTAssertEqual(d["maxPreTransitTime"], "1800")
        XCTAssertEqual(d["maxPostTransitTime"], "1800")
        XCTAssertEqual(d["maxDirectTime"], "3600")
    }

    func testWalkingListedWhenAboutAsFast() throws {
        let p = try plan("plan_muc")
        let transit = p.itineraries
        let walk = try XCTUnwrap(plan("plan_direct_modes").direct.first { StreetComparison.streetMode($0) == "WALK" })
        XCTAssertEqual(PlanModel.merge(transit: transit, direct: []).count, transit.count)
        XCTAssertEqual(PlanModel.merge(transit: [], direct: [walk]).map(\.id), [walk.id])
    }
}

final class ModelCodingTests: XCTestCase {
    func testItineraryRoundTripsThroughStorage() throws {
        let it = try plan("plan_roma").itineraries[0]
        let data = try JSONFile<Itinerary>.encoder.encode(it)
        let back = try TransitousClient.makeDecoder().decode(Itinerary.self, from: data)
        XCTAssertEqual(back.id, it.id)
        XCTAssertEqual(back.startTime, it.startTime)
        XCTAssertEqual(back.legs.count, it.legs.count)
        XCTAssertEqual(back.legs.first?.legGeometry.coordinates.count, it.legs.first?.legGeometry.coordinates.count)
        XCTAssertEqual(back.transitLegs.first?.agencyUrl, it.transitLegs.first?.agencyUrl)
    }
}

final class TicketLinkTests: XCTestCase {
    func testOperatorWebsiteFromFeed() throws {
        let it = try plan("plan_roma").itineraries[0]
        let links = TicketLinks.links(for: it)
        XCTAssertEqual(links.count, 1, "two Atac legs, one link")
        XCTAssertEqual(links.first?.operatorName, "Atac")
        XCTAssertEqual(links.first?.url.host, "www.atac.roma.it")
        XCTAssertEqual(links.first?.source, .website)
    }

    func testUserLinkedAppWins() throws {
        let it = try plan("plan_roma").itineraries[0]
        let links = TicketLinks.links(for: it, userLinked: [TicketLinks.operatorKey("ATAC"): "myatac://tickets"])
        XCTAssertEqual(links.first?.source, .userLinked)
        XCTAssertEqual(links.first?.url.scheme, "myatac")
    }

    func testLinkForShortOperatorNameAppliesToSubsidiaries() {
        let linked = [TicketLinks.operatorKey("DB"): "https://int.bahn.de", TicketLinks.operatorKey("DB Regio"): "dbregio://"]
        XCTAssertEqual(TicketLinks.userLink(for: "DB Fernverkehr AG", in: linked), "https://int.bahn.de")
        XCTAssertEqual(TicketLinks.userLink(for: "DB Regio AG Bayern", in: linked), "dbregio://")
        XCTAssertNil(TicketLinks.userLink(for: "Deutsche Bahn", in: linked))
    }

    func testURLParsing() {
        XCTAssertEqual(TicketLinks.url("www.oebb.at")?.absoluteString, "https://www.oebb.at")
        XCTAssertNil(TicketLinks.url("  "))
        XCTAssertNil(TicketLinks.url("https://"))
        XCTAssertEqual(TicketLinks.url("dbnavigator://")?.scheme, "dbnavigator")
    }
}

final class DeepLinkTests: XCTestCase {
    func testRoundTrip() {
        for l in [DeepLink.trip("A1B2-C3"), .ticket("T9"), .ticket(nil), .tripTickets("A1")] {
            XCTAssertEqual(DeepLink(url: l.url), l, l.url.absoluteString)
        }
        XCTAssertEqual(DeepLink.trip("x").url.absoluteString, "publit://trip/x")
        XCTAssertNil(DeepLink(url: URL(string: "https://trip/x")!))
        XCTAssertNil(DeepLink(url: URL(string: "publit://trip")!))
    }
}

@MainActor
final class TripStoreTests: XCTestCase {
    func testSaveDedupeUpcomingPastAndPersist() async throws {
        let dir = tempDir()
        let its = try plan("plan_roma").itineraries
        let s = TripStore(directory: dir)
        let a = s.save(its[0], from: "Termini", to: "Trastevere")
        XCTAssertEqual(s.save(its[0], from: "Termini", to: "Trastevere").id, a.id, "same route saved once")
        s.save(its[1], from: "Termini", to: "Trastevere")
        XCTAssertEqual(s.upcoming(now: its[0].startTime.addingTimeInterval(-60)).count, 2)
        let later = its.map(\.endTime).max()!.addingTimeInterval(60)
        XCTAssertEqual(s.upcoming(now: later).count, 0)
        XCTAssertEqual(s.past(now: later).count, 2)
        XCTAssertGreaterThanOrEqual(s.past(now: later)[0].startTime, s.past(now: later)[1].startTime)

        let again = TripStore(directory: dir)
        XCTAssertEqual(again.trips.count, 2)
        XCTAssertNotNil(again.saved(its[0]))
        again.remove(a.id)
        XCTAssertNil(TripStore(directory: dir).trip(a.id))
    }
}

@MainActor
final class TicketStoreTests: XCTestCase {
    func testPassesAndSingleTickets() async {
        let dir = tempDir()
        let s = TicketStore(directory: dir)
        let pass = s.add(name: "KlimaTicket", kind: .pass, image: Data([1, 2, 3]), appLink: nil, validUntil: nil)
        let single = s.add(name: "Roma BIT", kind: .single, image: Data([4]), appLink: nil, validUntil: nil, tripId: "trip-1")
        _ = s.add(name: "Expired", kind: .pass, image: nil, appLink: "https://example.org", validUntil: Date(timeIntervalSince1970: 0))
        XCTAssertEqual(s.tickets(forTrip: "trip-1").map(\.id), [single.id, pass.id], "own ticket first, expired pass hidden")
        XCTAssertEqual(s.tickets(forTrip: nil).map(\.id), [pass.id])
        XCTAssertEqual(try? Data(contentsOf: s.imageURL(pass)!), Data([1, 2, 3]))

        s.linkApp("wienmobil://", forOperator: "Wiener Linien")
        let again = TicketStore(directory: dir)
        XCTAssertEqual(again.tickets.count, 3)
        XCTAssertEqual(again.linkedApps[TicketLinks.operatorKey("wiener  linien")], "wienmobil://")
        again.remove(pass.id)
        XCTAssertNil(again.imageURL(pass).flatMap { try? Data(contentsOf: $0) })
    }
}

final class TripStatusTests: XCTestCase {
    func testPhasesAlongTheTrip() throws {
        let it = try plan("plan_roma").itineraries[0]
        let first = it.firstTransitLeg!
        let before = TripStatus.at(it.startTime.addingTimeInterval(-6 * 60), it)
        XCTAssertEqual(before.phase, .beforeLeaving)
        XCTAssertEqual(before.headline, "Leave in 6 min")
        XCTAssertTrue(before.detail.contains(first.from.name))
        XCTAssertEqual(TripStatus.reminderTime(it), it.startTime.addingTimeInterval(-300))

        if it.legs.first?.isTransit == false, it.legs[0].endTime < first.startTime {
            XCTAssertEqual(TripStatus.at(it.legs[0].startTime.addingTimeInterval(1), it).phase, .walking)
        }
        let riding = TripStatus.at(first.startTime.addingTimeInterval(30), it)
        XCTAssertEqual(riding.phase, .riding)
        XCTAssertEqual(riding.headline, "Get off at \(first.to.name)")
        XCTAssertEqual(TripStatus.at(it.endTime.addingTimeInterval(1), it).phase, .arrived)
    }

    func testCalendarNotesLinkBackToTheApp() throws {
        let it = try plan("plan_roma").itineraries[0]
        let trip = SavedTrip(id: "abc", itinerary: it, fromName: "Termini", toName: "Trastevere", savedAt: .now)
        XCTAssertEqual(CalendarText.url(trip).absoluteString, "publit://trip/abc")
        // One link only (the URL field); the notes list the lines.
        XCTAssertFalse(CalendarText.notes(trip).contains("://"))
        XCTAssertTrue(CalendarText.title(trip).hasPrefix("Trastevere ("))
    }
}

final class MapStationTests: XCTestCase {
    func testPlatformsBecomeOneStation() throws {
        let stops = try JSONDecoder().decode([MapStop].self, from: Fixtures.data("mapstops_marienplatz"))
        let stations = MapStation.group(stops)
        XCTAssertLessThan(stations.count, stops.count)
        let marienplatz = stations.filter { $0.name == "Marienplatz" }
        XCTAssertEqual(marienplatz.count, 1, "S-Bahn, U-Bahn and bus platforms of Marienplatz are one pin")
        XCTAssertTrue(marienplatz[0].isMajor)
        XCTAssertEqual(Set(stations.map(\.id)).count, stations.count, "unique IDs for map selection")
    }

    func testPinsDoNotJumpWhenMorePlatformsLoad() throws {
        let stops = try JSONDecoder().decode([MapStop].self, from: Fixtures.data("mapstops_marienplatz"))
        let full = MapStation.group(stops)
        // Zoomed out, the server leaves out minor platforms; panning loads them in another order.
        let partial = MapStation.group(stops.filter { ($0.importance ?? 0) > 0.2 }.reversed())
        for p in partial {
            let f = try XCTUnwrap(full.first { $0.id == p.id }, "\(p.name) keeps its ID")
            XCTAssertEqual(f.coordinate, p.coordinate, "\(p.name) keeps its position")
        }
        XCTAssertEqual(MapStation.group(stops.shuffled()).map(\.id).sorted(), full.map(\.id).sorted())
    }

    func testStreetStopsBecomeSeparatePinsWhenZoomedIn() throws {
        let stops = try JSONDecoder().decode([MapStop].self, from: Fixtures.data("mapstops_marienplatz"))
        let near = MapStation.group(stops, separatePoles: true)
        let maxi = near.filter { $0.name == "Maximiliansplatz" }
        XCTAssertEqual(maxi.count, 2, "one pin per side of the road")
        XCTAssertTrue(maxi.allSatisfy(\.isPole))
        XCTAssertEqual(Set(maxi.map(\.coordinate)).count, 2)
        XCTAssertEqual(MapStation.group(stops).filter { $0.name == "Maximiliansplatz" }.count, 1, "one pin when zoomed out")
        XCTAssertEqual(near.filter { $0.name == "Marienplatz" && !$0.isPole }.count, 1, "rail platforms stay one station")
        XCTAssertEqual(Set(near.map(\.id)).count, near.count)
    }
}

final class KnownShopTests: XCTestCase {
    func testMunichOperatorsGetAShopButNotTheNonMVVBus() throws {
        let p = try TransitousClient.makeDecoder().decode(PlanResponse.self, from: Fixtures.data("plan_muc"))
        let legs = p.itineraries.flatMap(\.transitLegs)
        let ubahn = legs.first { $0.agencyName == "U-Bahn München" }.flatMap { TicketLinks.link(for: $0) }
        XCTAssertEqual(ubahn?.url.host, "www.mvg.de")
        let sbahn = legs.first { $0.agencyName == "DB Regio AG S-Bahn München" }.flatMap { TicketLinks.link(for: $0) }
        XCTAssertEqual(sbahn?.url.host, "www.mvv-muenchen.de", "most specific entry beats plain DB")
        let bus = legs.first { $0.agencyName == "Nicht in MVV integriert" }.flatMap { TicketLinks.link(for: $0) }
        XCTAssertNil(bus)
    }

    func testMapLabelsDropTheCity() {
        XCTAssertEqual(MapStation.shortName("München, Karolinenplatz"), "Karolinenplatz")
        XCTAssertEqual(MapStation.shortName("Wien Hauptbahnhof"), "Wien Hauptbahnhof")
        XCTAssertEqual(MapStation.shortName("Graz, Jakominiplatz"), "Jakominiplatz")
        XCTAssertEqual(MapStation.shortName("A, B"), "A, B")
    }
}

import XCTest
@testable import Publit

final class CityGuideTests: XCTestCase {
    private func munich() throws -> Itinerary {
        let res = try TransitousClient.makeDecoder().decode(PlanResponse.self, from: Fixtures.data("plan_walk_steps"))
        return try XCTUnwrap(res.itineraries.first)
    }

    /// The same trip with the U6 moved to another platform (and optionally cancelled).
    private func changed(_ it: Itinerary, track: String, cancelled: Bool = false) throws -> Itinerary {
        let enc = JSONFile<Itinerary>.encoder
        var json = try XCTUnwrap(JSONSerialization.jsonObject(with: enc.encode(it)) as? [String: Any])
        var legs = try XCTUnwrap(json["legs"] as? [[String: Any]])
        var from = try XCTUnwrap(legs[3]["from"] as? [String: Any])
        from["track"] = track
        legs[3]["from"] = from
        if cancelled { legs[3]["cancelled"] = true }
        json["legs"] = legs
        return try TransitousClient.makeDecoder().decode(Itinerary.self, from: JSONSerialization.data(withJSONObject: json))
    }

    func testCompassPoints() {
        XCTAssertEqual(Compass.point(0), .north)
        XCTAssertEqual(Compass.point(359), .north)
        XCTAssertEqual(Compass.point(44), .northEast)
        XCTAssertEqual(Compass.point(-90), .west)
        XCTAssertEqual(Compass.point(181), .south)
        XCTAssertEqual(Compass.relative(10, heading: 350), 20, accuracy: 0.001)
        XCTAssertEqual(Compass.relative(350, heading: 10), -20, accuracy: 0.001)
        XCTAssertEqual(Compass.head(.northEast), "Head north-east")
    }

    func testPlatformChange() throws {
        let it = try munich()
        XCTAssertNil(it.legs[3].from.platformChange)
        let moved = try changed(it, track: "52")
        XCTAssertEqual(moved.legs[3].from.platformChange, PlatformChange(scheduled: "51", now: "52"))
        // Spelling alone is no change.
        XCTAssertNil(try changed(it, track: " 51 ").legs[3].from.platformChange)
        XCTAssertEqual(TripStatus.at(moved.legs[2].startTime, moved).detail.contains("52 (changed)"), true)
    }

    func testChangesWorthANotification() throws {
        let it = try munich()
        let before = it.startTime.addingTimeInterval(-60)
        let moved = try changed(it, track: "52")
        XCTAssertEqual(TripChanges.between(it, moved, now: before), [.platform(legIndex: 3, line: "U6", change: PlatformChange(scheduled: "51", now: "52"))])
        // Told once: the next refresh with the same platform says nothing.
        XCTAssertEqual(TripChanges.between(moved, moved, now: before), [])
        // After departure nothing is announced any more.
        XCTAssertEqual(TripChanges.between(it, moved, now: it.legs[3].startTime.addingTimeInterval(10)), [])
        let gone = try changed(it, track: "51", cancelled: true)
        XCTAssertEqual(TripChanges.between(it, gone, now: before), [.cancelled(legIndex: 3, line: "U6", stop: "Odeonsplatz")])
        XCTAssertTrue(TripAlternative.needed(.fine, gone, at: before))
        XCTAssertFalse(TripAlternative.needed(.fine, it, at: before))
        XCTAssertTrue(TripAlternative.needed(.missed, it, at: before))
    }

    func testTransferBetweenTheTwoLines() throws {
        let it = try munich()
        XCTAssertNil(TransferInfo(before: 1, in: it))
        let t = try XCTUnwrap(TransferInfo(before: 3, in: it))
        XCTAssertEqual(t.fromLine, "U4")
        XCTAssertEqual(t.platforms, "From platform 54 to platform 51")
        XCTAssertEqual(t.levels, "level −3 → level −2")
        XCTAssertEqual(t.walkSeconds, 180)
        XCTAssertEqual(t.spareSeconds, 60)
        XCTAssertTrue(t.isTight)
    }

    func testExitWhenComingUpFromTheStation() throws {
        let it = try munich()
        let up = WalkRoute(leg: it.legs[4])
        let i = try XCTUnwrap(up.exitIndex)
        XCTAssertEqual(up.maneuvers[i].kind, .stairsUp)
        XCTAssertEqual(up.maneuvers[i].level, 0)
        XCTAssertNotNil(up.maneuvers[i].exitNote)
        // Going down into a station, or changing underground, is no exit.
        XCTAssertNil(WalkRoute(leg: it.legs[0]).exitIndex)
        XCTAssertNil(WalkRoute(leg: it.legs[2]).exitIndex)
        XCTAssertNotNil(up.bearing(at: 0))
    }

    func testCuesComeInOrderOfImportance() throws {
        let it = try munich()
        let steps = TripGuide.steps(it)
        let wait = try XCTUnwrap(steps.first { $0.kind == .wait && $0.legIndex == 3 })
        let t = it.legs[3].startTime.addingTimeInterval(-90)
        let plain = try XCTUnwrap(GuideCues.current(wait, it, at: t, walk: nil, position: nil, progress: nil))
        XCTAssertEqual(plain.kind, .stage)
        XCTAssertTrue(plain.text.contains("U6"))
        let moved = try changed(it, track: "52")
        let alert = try XCTUnwrap(GuideCues.current(wait, moved, at: t, walk: nil, position: nil, progress: nil))
        XCTAssertEqual(alert.kind, .platform)
        XCTAssertEqual(alert.text, "Platform change: U6 now leaves from platform 52.")
        // While riding the U4, the U6's new platform is already worth saying.
        let ride = try XCTUnwrap(steps.first { $0.kind == .ride && $0.legIndex == 1 })
        XCTAssertEqual(GuideCues.current(ride, moved, at: it.legs[1].startTime, walk: nil, position: nil, progress: .notStarted)?.kind, .platform)

        let walkStep = try XCTUnwrap(steps.first { $0.kind == .walk && $0.legIndex == 4 })
        let route = WalkRoute(leg: it.legs[4])
        let turnAt = try XCTUnwrap(route.maneuvers.dropFirst().first { $0.kind != .arrive })
        let p = try XCTUnwrap(PathGeometry.point(at: max(0, turnAt.along - 15), on: route.path))
        let cue = GuideCues.current(walkStep, it, at: it.legs[4].startTime, walk: route, position: route.position(of: p), progress: nil)
        XCTAssertEqual(cue?.kind, .turn)
    }

    func testAlternativeArrivesFirst() throws {
        let it = try munich()
        let late = try changed(it, track: "51")
        XCTAssertEqual(TripAlternative.pick([it, late], at: it.startTime.addingTimeInterval(-120))?.id, it.id)
        XCTAssertNil(TripAlternative.pick([it], at: it.legs[3].startTime))
    }
}

final class LineContrastTests: XCTestCase {
    func testNearBlackLinesAreLiftedInDarkModeOnly() {
        // Munich S8: 1.2:1 against a dark card.
        XCTAssertTrue(LineColors.blendsIn("2D2B29", dark: true))
        XCTAssertFalse(LineColors.blendsIn("2D2B29", dark: false))
        let lifted = LineColors.RGB(hex: LineColors.strokeHex("2D2B29", dark: true))!
        XCTAssertGreaterThanOrEqual(LineColors.contrast(lifted, LineColors.darkSurface), LineColors.minimumSurfaceContrast)
        XCTAssertGreaterThan(LineColors.contrast(lifted, LineColors.black), 1.5, "lifted towards white")
        XCTAssertEqual(LineColors.strokeHex("2D2B29", dark: false), "2D2B29")
    }

    func testOrdinaryColoursStayExact() {
        for hex in ["0065AE", "E3000F", "008D4F", "#9E2064"] {
            XCTAssertFalse(LineColors.blendsIn(hex, dark: true), hex)
            XCTAssertFalse(LineColors.blendsIn(hex, dark: false), hex)
            XCTAssertEqual(LineColors.strokeHex(hex, dark: true), LineColors.normalized(hex))
        }
    }

    func testWhiteAndPaleLinesAreDarkenedInLightMode() {
        XCTAssertTrue(LineColors.blendsIn("FFFFFF", dark: false))
        XCTAssertTrue(LineColors.blendsIn("FFF200", dark: false))
        let darker = LineColors.RGB(hex: LineColors.strokeHex("FFF200", dark: false))!
        XCTAssertGreaterThanOrEqual(LineColors.contrast(darker, LineColors.lightSurface), LineColors.minimumSurfaceContrast)
        XCTAssertFalse(LineColors.blendsIn(nil, dark: true))
        XCTAssertEqual(LineColors.RGB(r: 1, g: 0, b: 0.5).hex, "FF0080")
    }
}

final class CoverageGapTests: XCTestCase {
    func testMessageNamesTheEndWithoutData() {
        XCTAssertNil(PlanModel.coverageMessage(startCovered: true, destinationCovered: true))
        XCTAssertTrue(PlanModel.coverageMessage(startCovered: false, destinationCovered: false)!.contains("this area"))
        XCTAssertTrue(PlanModel.coverageMessage(startCovered: false, destinationCovered: true)!.contains("start"))
        XCTAssertTrue(PlanModel.coverageMessage(startCovered: true, destinationCovered: false)!.contains("destination"))
    }
}

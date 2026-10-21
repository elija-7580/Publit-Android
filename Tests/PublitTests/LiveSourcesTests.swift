import XCTest
@testable import Publit

final class LiveSourcesTests: XCTestCase {
    private let t0 = 1_791_360_000.0   // 2026-10-07 08:00 UTC

    private func date(_ m: Double) -> Date { Date(timeIntervalSince1970: t0 + m * 60) }

    private func leg(_ mode: String, start: Double, end: Double, name: String? = nil, realTime: Bool = false,
                     cancelled: Bool? = nil) throws -> Leg {
        func iso(_ m: Double) -> String { DateParsing.format(date(m)) }
        let line = name.map { ",\"routeShortName\":\"\($0)\"" } ?? ""
        let cancel = cancelled.map { ",\"cancelled\":\($0)" } ?? ""
        let json = """
        {"mode":"\(mode)","from":{"name":"A","lat":48.2,"lon":16.37},"to":{"name":"B","lat":48.21,"lon":16.38},
         "duration":\(Int((end - start) * 60)),"startTime":"\(iso(start))","endTime":"\(iso(end))",
         "scheduledStartTime":"\(iso(start))","scheduledEndTime":"\(iso(end))",
         "realTime":\(realTime)\(line)\(cancel),"legGeometry":{"points":"","precision":6,"length":0}}
        """
        return try TransitousClient.makeDecoder().decode(Leg.self, from: Data(json.utf8))
    }

    private func itinerary(_ legs: [Leg]) -> Itinerary {
        Itinerary(id: "x", duration: Int(legs.last!.endTime.timeIntervalSince(legs.first!.startTime)),
                  startTime: legs.first!.startTime, endTime: legs.last!.endTime, transfers: 0, legs: legs)
    }

    private func json(_ s: String) throws -> Any { try JSONSerialization.jsonObject(with: Data(s.utf8)) }

    func testLineNamesMatchAcrossOperators() {
        XCTAssertEqual(LiveMatch.key("Bus 404"), "404")
        XCTAssertEqual(LiveMatch.key("S 4 (Zug-Nr. 5197)"), "s4")
        XCTAssertEqual(LiveMatch.key("13_UN"), "13")
        XCTAssertEqual(LiveMatch.key("Elizabeth line"), "elizabeth")
        XCTAssertEqual(LiveMatch.key("Tram 1"), "1")
        XCTAssertTrue(LiveMatch.sameLine(["S4"], ["S 4", "4", "5197"]))
        XCTAssertTrue(LiveMatch.sameLine(["REX 7 2345"], ["REX 7", "2345"]))
        XCTAssertFalse(LiveMatch.sameLine(["U4"], ["U1"]))
        XCTAssertFalse(LiveMatch.sameLine(["13A"], ["13"]))
    }

    func testBestMatchNeedsLineAndTime() {
        let ride = LiveRide(point: LatLon(lat: 48.2, lon: 16.37), stopId: nil, stopName: "A", lines: ["U1"],
                            headsign: nil, scheduled: date(10), mode: "SUBWAY")
        let deps = [LiveDeparture(lines: ["U1"], scheduled: date(5), expected: date(6)),
                    LiveDeparture(lines: ["U1"], scheduled: date(10), expected: date(13)),
                    LiveDeparture(lines: ["U4"], scheduled: date(10), expected: date(10))]
        XCTAssertEqual(LiveMatch.best(for: ride, in: deps)?.expected, date(13))
        XCTAssertNil(LiveMatch.best(for: ride, in: [LiveDeparture(lines: ["U1"], scheduled: date(20))]))
        // Countdown-only boards: the nearest live time not long before the timetable time.
        let counted = [LiveDeparture(lines: ["1"], expected: date(12)), LiveDeparture(lines: ["1"], expected: date(25))]
        let tram = LiveRide(point: ride.point, stopId: nil, stopName: "A", lines: ["1"], headsign: nil, scheduled: date(10), mode: "TRAM")
        XCTAssertEqual(LiveMatch.best(for: tram, in: counted)?.expected, date(12))
    }

    func testMergeMovesRideAndFollowingWalk() throws {
        let it = itinerary([try leg("WALK", start: 0, end: 5), try leg("BUS", start: 5, end: 20, name: "404"),
                            try leg("WALK", start: 20, end: 25)])
        let fix = LiveFix(departure: date(9), alerts: [Alert(headerText: "Umleitung", descriptionText: "Wegen Bauarbeiten", url: nil)],
                          source: "ÖBB")
        let out = LiveMerge.merge(it, fixes: [1: fix])
        XCTAssertEqual(out.legs[1].startTime, date(9))
        XCTAssertEqual(out.legs[1].endTime, date(24))
        XCTAssertTrue(out.legs[1].realTime)
        XCTAssertEqual(out.legs[1].liveSource, "ÖBB")
        XCTAssertEqual(out.legs[1].alerts?.count, 1)
        XCTAssertEqual(out.legs[0].endTime, date(9))
        XCTAssertEqual(out.legs[2].endTime, date(29))
        XCTAssertEqual(out.endTime, date(29))
        XCTAssertEqual(out.legs[0].startTime, date(4))
        XCTAssertEqual(out.duration, 25 * 60)
        XCTAssertEqual(LiveOverlay.sourceNames(out), ["ÖBB"])
        // The same alert twice is listed once.
        XCTAssertEqual(LiveMerge.merge(out.legs[1], fix).alerts?.count, 1)
    }

    func testLiveTimesFromTransitousWin() throws {
        let live = try leg("BUS", start: 5, end: 20, name: "404", realTime: true)
        let out = LiveMerge.merge(live, LiveFix(departure: date(15), cancelled: true, source: "ÖBB"))
        XCTAssertEqual(out.startTime, date(5))
        XCTAssertNotEqual(out.cancelled, true)
        XCTAssertNil(out.liveSource)
    }

    func testCancelledWalkDoesNotCancelTheTrip() throws {
        let it = itinerary([try leg("WALK", start: 0, end: 5, cancelled: true), try leg("BUS", start: 5, end: 20, name: "404")])
        XCTAssertFalse(it.isCancelled)
        let bus = itinerary([try leg("WALK", start: 0, end: 5), try leg("BUS", start: 5, end: 20, name: "404", cancelled: true)])
        XCTAssertTrue(bus.isCancelled)
        XCTAssertEqual(TripStatus.at(date(-10), bus).phase, .cancelled)
    }

    func testHafasBoard() throws {
        let res = try XCTUnwrap(json("""
        {"common":{"prodL":[{"name":"Bus N1","number":"N1","cls":64,"prodCtx":{"line":"N1","num":"910582"}},
                            {"name":"U1","number":"U1","cls":256}],
                   "himL":[{"head":"Bauarbeiten - Zugausfall","text":"Wegen Bauarbeiten <br>zwischen <b>Wien Hbf</b>","prio":100,"prod":560,
                            "sDate":"20260907","sTime":"000000","eDate":"20271031","eTime":"235900"},
                           {"head":"Umleitung","text":"Linie N1 f&auml;hrt um","prio":10,"prod":64}]},
         "jnyL":[{"prodX":0,"date":"20261018","dirTxt":"Sieglanger","stbStop":{"dTimeS":"233000","dTimeR":"233200","dTZOffset":120,
                   "dPltfS":{"type":"ST","txt":"D"}},"msgL":[{"type":"HIM","himX":1}]},
                 {"prodX":1,"date":"20261018","dirTxt":"Oberlaa","stbStop":{"dTimeS":"01001500","dTZOffset":120,"dCncl":true},
                   "msgL":[{"type":"HIM","himX":0,"tagL":["SUM_GLB_HDR_H3"]},{"type":"HIM","himX":0}]}]}
        """) as? [String: Any])
        let deps = HafasSource.parseBoard(res)
        XCTAssertEqual(deps.count, 2)
        XCTAssertEqual(deps[0].scheduled, ISO8601DateFormatter().date(from: "2026-10-18T21:30:00Z"))
        XCTAssertEqual(deps[0].expected, ISO8601DateFormatter().date(from: "2026-10-18T21:32:00Z"))
        XCTAssertEqual(deps[0].track, "D")
        XCTAssertEqual(deps[0].alerts.first?.headerText, "Umleitung")
        XCTAssertEqual(deps[0].alerts.first?.severityLevel, "SEVERE")
        XCTAssertTrue(deps[0].lines.contains("N1"))
        // Next day, cancelled, no live time; the rail works note is not about the U-Bahn (product mask).
        XCTAssertEqual(deps[1].scheduled, ISO8601DateFormatter().date(from: "2026-10-18T22:15:00Z"))
        XCTAssertNil(deps[1].expected)
        XCTAssertTrue(deps[1].cancelled)
        XCTAssertTrue(deps[1].alerts.isEmpty)
        let him = try XCTUnwrap(HafasSource.alert(["head": "Bauarbeiten", "text": "Wegen <b>Bauarbeiten</b><br>zwischen A &#60;=&#62; B"]))
        XCTAssertEqual(him.descriptionText, "Wegen Bauarbeiten\nzwischen A <=> B")
        XCTAssertEqual(HafasSource.parseNearest(["locL": [["lid": "far", "dist": 200], ["lid": "near", "dist": 40]]]), "near")
    }

    func testWienerLinienMonitor() throws {
        XCTAssertEqual(WienerLinienSource.diva("at-PTA-Eastern-Region-Flex-2026_Pat:49:1198"), "60201198")
        XCTAssertNil(WienerLinienSource.diva("de-DELFI_de:09162:2"))
        let root = try XCTUnwrap(json("""
        {"data":{"monitors":[{"lines":[{"name":"U4","towards":"Hütteldorf","departures":{"departure":[
            {"departureTime":{"timePlanned":"2026-10-18T23:40:00.000+0200","timeReal":"2026-10-18T23:41:28.000+0200","countdown":4}},
            {"departureTime":{"timePlanned":"2026-10-18T23:50:00.000+0200","countdown":14}}]}}]}],
         "trafficInfos":[{"refTrafficInfoCategoryId":2,"title":"U4 Störung","description":"Unregelmäßige Intervalle","relatedLines":["U4"]},
                         {"refTrafficInfoCategoryId":1,"title":"Aufzug","description":"außer Betrieb","relatedLines":["U4"]}]}}
        """) as? [String: Any])
        let deps = WienerLinienSource.parseMonitor(root)
        XCTAssertEqual(deps.count, 2)
        XCTAssertEqual(deps[0].expected, ISO8601DateFormatter().date(from: "2026-10-18T21:41:28Z"))
        XCTAssertNil(deps[1].expected)
        XCTAssertEqual(deps[0].alerts.map(\.headerText), ["U4 Störung"])
    }

    func testACTVPassages() throws {
        let now = try XCTUnwrap(ISO8601DateFormatter().date(from: "2026-10-18T21:38:00Z"))   // 23:38 in Venice
        let list = try json("""
        [{"line":"13_UN","destination":"F.TE NOVE","time":"2'","real":true,"timingPoints":[{"time":"23:40","stop":"Treporti"}]},
         {"line":"12_UN","destination":"BURANO","time":"00:10","real":false,"timingPoints":[{"time":"00:10","stop":"Treporti"}]}]
        """)
        let deps = ACTVSource.parsePassages(list, now: now)
        XCTAssertEqual(deps.count, 2)
        XCTAssertEqual(deps[0].scheduled, now.addingTimeInterval(120))
        XCTAssertEqual(deps[0].expected, now.addingTimeInterval(120))
        XCTAssertNil(deps[1].expected)
        XCTAssertEqual(deps[1].scheduled, now.addingTimeInterval(32 * 60))   // after midnight
        XCTAssertEqual(ACTVSource.parseStops(try json("""
        [{"name":"5069-Treporti-nav-web","latitude":45.46,"longitude":12.45}]
        """)).first?.id, "5069-Treporti-nav-web")
    }

    func testSLDepartures() throws {
        let root = try XCTUnwrap(json("""
        {"departures":[{"destination":"Uppsala C","state":"EXPECTED","scheduled":"2026-10-18T23:38:00","expected":"2026-10-18T23:41:00",
                        "stop_point":{"designation":"2"},"line":{"designation":"40","transport_mode":"TRAIN"},
                        "deviations":[{"importance_level":5,"message":"Signalfel"}]},
                       {"destination":"Hjulsta","state":"CANCELLED","scheduled":"2026-10-18T23:45:00","expected":"2026-10-18T23:45:00",
                        "line":{"designation":"10","transport_mode":"METRO"},"deviations":[]}],
         "stop_deviations":[{"importance_level":3,"message":"Hissen ur funktion","scope":{"lines":[{"designation":"40"}]}}]}
        """) as? [String: Any])
        let deps = SLSource.parseDepartures(root)
        XCTAssertEqual(deps[0].expected, ISO8601DateFormatter().date(from: "2026-10-18T21:41:00Z"))
        XCTAssertEqual(deps[0].track, "2")
        XCTAssertEqual(deps[0].alerts.map(\.headerText), ["Signalfel", "Hissen ur funktion"])
        XCTAssertTrue(deps[1].cancelled)
        XCTAssertTrue(deps[1].alerts.isEmpty)
    }

    func testTfLStatus() throws {
        let lines = TfLStatusSource.parseStatus(try json("""
        [{"id":"bakerloo","name":"Bakerloo","lineStatuses":[{"statusSeverity":9,"statusSeverityDescription":"Minor Delays",
           "reason":"Bakerloo Line: Minor delays due to train cancellations. ",
           "validityPeriods":[{"fromDate":"2026-10-18T20:48:42Z","toDate":"2026-10-19T00:29:00Z"}]}]},
         {"id":"victoria","name":"Victoria","lineStatuses":[{"statusSeverity":10,"statusSeverityDescription":"Good Service"}]}]
        """))
        XCTAssertEqual(lines.count, 2)
        XCTAssertEqual(lines[0].alerts.first?.headerText, "Bakerloo: Minor Delays")
        XCTAssertEqual(lines[0].alerts.first?.impactPeriod?.count, 1)
        XCTAssertTrue(lines[1].alerts.isEmpty)
        XCTAssertTrue(LiveMatch.sameLine(["Bakerloo"], lines[0].names))
    }

    func testAustriaOutline() {
        func covered(_ lat: Double, _ lon: Double, tz: String? = nil) -> Bool {
            HafasSource.oebb.covers(LiveRide(point: LatLon(lat: lat, lon: lon), stopId: nil, stopName: "", lines: [], headsign: nil,
                                          scheduled: Date(), mode: "BUS", tz: tz))
        }
        XCTAssertTrue(covered(48.41, 15.60))    // Krems
        XCTAssertTrue(covered(48.21, 16.37))    // Vienna
        XCTAssertTrue(covered(47.80, 13.04))    // Salzburg
        XCTAssertTrue(covered(47.26, 11.39))    // Innsbruck
        XCTAssertTrue(covered(47.50, 9.74))     // Bregenz
        XCTAssertTrue(covered(46.62, 14.31))    // Klagenfurt
        XCTAssertFalse(covered(48.14, 11.58))   // Munich
        XCTAssertFalse(covered(48.57, 13.46))   // Passau
        XCTAssertFalse(covered(48.15, 17.11))   // Bratislava
        XCTAssertFalse(covered(46.50, 11.35))   // Bolzano
        XCTAssertFalse(covered(48.21, 16.37, tz: "Europe/Berlin"))
    }

    func testOverlayStaysOutsideCoveredAreas() async throws {
        // Munich: no extra source, nothing is asked and the routes come back unchanged.
        let json = """
        {"mode":"BUS","from":{"name":"A","lat":48.13,"lon":11.57},"to":{"name":"B","lat":48.14,"lon":11.58},
         "duration":600,"startTime":"\(DateParsing.format(Date()))","endTime":"\(DateParsing.format(Date().addingTimeInterval(600)))",
         "scheduledStartTime":"\(DateParsing.format(Date()))","scheduledEndTime":"\(DateParsing.format(Date().addingTimeInterval(600)))",
         "realTime":false,"routeShortName":"52","legGeometry":{"points":"","precision":6,"length":0}}
        """
        let bus = try TransitousClient.makeDecoder().decode(Leg.self, from: Data(json.utf8))
        XCTAssertFalse(LiveOverlay.wanted(LiveOverlay.ride(bus), now: Date()))
        let it = itinerary([bus])
        let started = Date()
        let out = await LiveOverlay.apply([it])
        XCTAssertLessThan(Date().timeIntervalSince(started), 0.5)
        XCTAssertEqual(out.first?.startTime, it.startTime)
    }

    func testSwissStopNumbersAndCoverage() {
        XCTAssertEqual(SearchChSource.stopNumber("ch-opentransportdataswiss27_ch:1:sloid:87349::1"), "8587349")
        XCTAssertEqual(SearchChSource.stopNumber("de-DELFI_ch:1:sloid:3000"), "8503000")
        XCTAssertEqual(SearchChSource.stopNumber("ch-opentransportdataswiss27_ch:1:sloid:91067"), "8591067")
        XCTAssertNil(SearchChSource.stopNumber("de-DELFI_de:05113:9289:0:2"))
        func ride(_ lat: Double, _ lon: Double, stop: String? = nil, tz: String? = nil) -> LiveRide {
            LiveRide(point: LatLon(lat: lat, lon: lon), stopId: stop, stopName: "", lines: ["S2"], headsign: nil,
                     scheduled: Date(), mode: "SUBURBAN", tz: tz)
        }
        let source = SearchChSource()
        XCTAssertTrue(source.covers(ride(47.38, 8.54, stop: "ch-opentransportdataswiss27_ch:1:sloid:3000")))
        XCTAssertTrue(source.covers(ride(46.0, 8.95, tz: "Europe/Zurich")))           // Lugano, a non-Swiss stop ID
        XCTAssertFalse(source.covers(ride(47.50, 9.74, tz: "Europe/Vienna")))         // Bregenz, inside the rectangle
        XCTAssertFalse(source.covers(ride(47.66, 9.18, tz: "Europe/Berlin")))         // Konstanz
        XCTAssertFalse(source.covers(ride(47.38, 8.54)))                               // no ID, no time zone: not guessed
    }

    func testSearchChBoard() throws {
        let root = try XCTUnwrap(json("""
        {"connections":[
          {"time":"2026-10-19 22:17:00","*G":"S","*L":"2","*Z":"018285","type":"strain","line":"S2","terminal":{"name":"Ziegelbrücke"},
           "track":"32!","dep_delay":"+3"},
          {"time":"2026-10-19 23:04:00","*G":"IC","*L":"8","*Z":"000837","line":"IC 8","track":"34","dep_delay":"X"},
          {"time":"2026-10-20 01:10:00","line":"7","type":"tram"},
          {"time":"2026-10-19 22:20:00","*G":"R","*Z":"002650","line":"R 2650"}]}
        """) as? [String: Any])
        let deps = SearchChSource.parseBoard(root)
        XCTAssertEqual(deps.count, 4)
        XCTAssertEqual(deps[0].scheduled, ISO8601DateFormatter().date(from: "2026-10-19T20:17:00Z"))
        XCTAssertEqual(deps[0].expected, ISO8601DateFormatter().date(from: "2026-10-19T20:20:00Z"))
        XCTAssertEqual(deps[0].track, "32")
        XCTAssertEqual(deps[0].destination, "Ziegelbrücke")
        XCTAssertTrue(deps[1].cancelled)
        XCTAssertNil(deps[1].expected)
        XCTAssertTrue(deps[1].lines.contains("837"))
        XCTAssertNil(deps[2].expected)                                        // beyond the live window
        XCTAssertFalse(deps[2].cancelled)
        // Zero-padded train numbers still match Transitous' "2650".
        XCTAssertTrue(LiveMatch.sameLine(["2650"], deps[3].lines))
        let near = try json("""
        [{"label":"Hauptbahnhof Zuerich, Zürich","id":null,"dist":4},{"label":"Zürich, Bahnhofquai/HB","id":"8587349","dist":115},
         {"label":"Zürich HB","id":"8503000","dist":0}]
        """)
        XCTAssertEqual(SearchChSource.parseNearest(near), "8503000")
        XCTAssertNil(SearchChSource.parseNearest(try json("""
        [{"label":"Zürich, Bahnhofquai/HB","id":"8587349","dist":115}]
        """)))
    }

    func testEFAStopIDsAndCoverage() {
        XCTAssertEqual(EFASource.dhid("de-DELFI_de:05113:9289:0:2"), "de:05113:9289")
        XCTAssertEqual(EFASource.dhid("de-DELFI_de:05113:9289"), "de:05113:9289")
        XCTAssertEqual(EFASource.dhid("de-DELFI_de:05113:9289_G"), "de:05113:9289")
        XCTAssertEqual(EFASource.dhid("de-VBB_de:11000:900100003::1"), "de:11000:900100003")
        XCTAssertNil(EFASource.dhid("de-DELFI_at:42:3654"))
        XCTAssertNil(EFASource.dhid("ch-opentransportdataswiss27_ch:1:sloid:3000"))
        func ride(_ stop: String, tz: String? = "Europe/Berlin", live: Bool = false) -> LiveRide {
            LiveRide(point: LatLon(lat: 51.45, lon: 7.01), stopId: stop, stopName: "", lines: ["U11"], headsign: nil,
                     scheduled: Date().addingTimeInterval(300), mode: "SUBWAY", tz: tz, live: live)
        }
        XCTAssertTrue(EFASource.vrr.covers(ride("de-DELFI_de:05113:9289:0:2")))      // Essen
        XCTAssertFalse(EFASource.vrr.covers(ride("de-DELFI_de:09162:2:52:52")))      // Munich: not this server's region
        XCTAssertFalse(EFASource.vrr.covers(ride("de-DELFI_de:05113:9289", tz: "Europe/Amsterdam")))
        // A live time from Transitous: the source is not even asked.
        XCTAssertTrue(EFASource.vrr.asks(ride("de-DELFI_de:05113:9289"), now: Date()))
        XCTAssertFalse(EFASource.vrr.asks(ride("de-DELFI_de:05113:9289", live: true), now: Date()))
        XCTAssertFalse(LiveOverlay.wanted(ride("de-DELFI_de:05113:9289", live: true), now: Date()))
        XCTAssertTrue(LiveOverlay.wanted(ride("de-DELFI_de:05113:9289"), now: Date()))
    }

    func testEFAStopEvents() throws {
        let root = try XCTUnwrap(json("""
        {"stopEvents":[
          {"realtimeStatus":["MONITORED"],"isRealtimeControlled":true,
           "location":{"id":"de:05113:9289:14:2","properties":{"platformName":"2"}},
           "departureTimePlanned":"2026-10-19T20:16:00Z","departureTimeBaseTimetable":"2026-10-19T20:15:00Z",
           "departureTimeEstimated":"2026-10-19T20:16:18Z",
           "transportation":{"name":"U-Bahn U11","disassembledName":"U11","number":"U11","destination":{"name":"Essen Messe W.-Süd/Gruga"}},
           "infos":[{"priority":"high","title":"Umleitung","content":"Wegen Bauarbeiten <b>ab Hbf</b>"}]},
          {"realtimeStatus":["TRIP_CANCELLED"],"isRealtimeControlled":true,
           "departureTimePlanned":"2026-10-19T20:20:00Z","departureTimeEstimated":"2026-10-19T20:20:00Z",
           "transportation":{"number":"107","disassembledName":"107"}},
          {"departureTimePlanned":"2026-10-19T20:35:00Z","departureTimeEstimated":"2026-10-19T20:35:00Z",
           "transportation":{"number":"SEV S3"}}]}
        """) as? [String: Any])
        let deps = EFASource.parseStopEvents(root)
        XCTAssertEqual(deps.count, 3)
        XCTAssertEqual(deps[0].scheduled, ISO8601DateFormatter().date(from: "2026-10-19T20:15:00Z"))   // timetable, not dispatch time
        XCTAssertEqual(deps[0].expected, ISO8601DateFormatter().date(from: "2026-10-19T20:16:18Z"))
        XCTAssertEqual(deps[0].track, "2")
        XCTAssertEqual(deps[0].alerts.first?.headerText, "Umleitung")
        XCTAssertEqual(deps[0].alerts.first?.descriptionText, "Wegen Bauarbeiten ab Hbf")
        XCTAssertEqual(deps[0].alerts.first?.severityLevel, "SEVERE")
        XCTAssertTrue(deps[1].cancelled)
        XCTAssertNil(deps[2].expected)                                         // not monitored: no live time claimed
        let ride = LiveRide(point: LatLon(lat: 51.45, lon: 7.01), stopId: "de-DELFI_de:05113:9289:14:2", stopName: "", lines: ["U11"],
                            headsign: nil, scheduled: ISO8601DateFormatter().date(from: "2026-10-19T20:15:00Z")!, mode: "SUBWAY")
        XCTAssertEqual(LiveMatch.best(for: ride, in: deps)?.expected, deps[0].expected)
    }

    func testMD5AndHafasChecksum() {
        XCTAssertEqual(MD5.hex(Data()), "d41d8cd98f00b204e9800998ecf8427e")
        XCTAssertEqual(MD5.hex(Data("The quick brown fox jumps over the lazy dog".utf8)), "9e107d9d372bb6826bd81d3542a419d6")
        XCTAssertEqual(MD5.hex(Data("12345678901234567890123456789012345678901234567890123456789012345678901234567890".utf8)),
                       "57edf4a22be3c955ac49da2e2107b67a")
        let mic = MD5.hex(Data("{}".utf8))
        XCTAssertEqual(HafasSource.checksum(Data("{}".utf8), salt: "x"), "mic=\(mic)&mac=\(MD5.hex(Data((mic + "x").utf8)))")
    }

    func testGermanHafasCoverage() {
        func ride(_ stop: String, tz: String? = "Europe/Berlin", live: Bool = false) -> LiveRide {
            LiveRide(point: LatLon(lat: 52.5, lon: 13.4), stopId: stop, stopName: "", lines: ["M41"], headsign: nil,
                     scheduled: Date(), mode: "BUS", tz: tz, live: live)
        }
        XCTAssertTrue(HafasSource.vbb.covers(ride("de-VBB_de:11000:900029302::1")))        // Berlin-Spandau
        XCTAssertTrue(HafasSource.vbb.covers(ride("de-DELFI_de:12054:900230999")))          // Potsdam
        XCTAssertTrue(HafasSource.rmv.covers(ride("de-DELFI_de:06414:6907:0:1")))           // Wiesbaden
        XCTAssertTrue(HafasSource.vbn.covers(ride("de-DELFI_de:03403:16750")))              // Oldenburg
        XCTAssertTrue(HafasSource.vbn.covers(ride("de-DELFI_de:04011:1001")))               // Bremen
        XCTAssertTrue(HafasSource.vbn.covers(ride("de-VBN_000000100063")))                  // Oldenburg ZOB, VBN's own feed
        XCTAssertFalse(HafasSource.vbn.covers(ride("de-VBN_000000100063", tz: "Europe/Amsterdam")))
        XCTAssertFalse(HafasSource.rmv.covers(ride("de-VBN_000000100063")))
        XCTAssertTrue(HafasSource.nahsh.covers(ride("de-DELFI_de:01003:58085")))            // Lübeck
        XCTAssertTrue(EFASource.vrn.covers(ride("de-DELFI_de:07312:9035")))                 // Kaiserslautern
        XCTAssertFalse(HafasSource.rmv.covers(ride("de-DELFI_de:06414:6907", tz: "Europe/Vienna")))
        XCTAssertFalse(HafasSource.vbb.covers(ride("de-DELFI_de:09162:6")))                  // Munich
        XCTAssertFalse(HafasSource.vbb.covers(ride("at-PTA_at:49:1198")))
        XCTAssertFalse(HafasSource.vbb.asks(ride("de-VBB_de:11000:900029302", live: true), now: Date()))
        XCTAssertFalse(HafasSource.oebb.onlyWithoutLive)
        // Each German stop is asked of one server at most.
        let german = LiveOverlay.sources.filter { s in ["de:01", "de:03", "de:04", "de:05", "de:06", "de:07", "de:11", "de:12"]
            .contains { p in s.covers(ride("de-DELFI_\(p)999:1")) } }
        XCTAssertEqual(german.count, 6)
        for p in ["de:01", "de:03", "de:04", "de:05", "de:06", "de:07", "de:11", "de:12"] {
            XCTAssertEqual(LiveOverlay.sources.filter { $0.covers(ride("de-DELFI_\(p)999:1")) }.count, 1, p)
        }
        // Not covered: no server found with more live times than Transitous.
        for p in ["de:02", "de:08", "de:09", "de:10", "de:13", "de:14", "de:15", "de:16"] {
            XCTAssertTrue(LiveOverlay.sources.filter { $0.covers(ride("de-DELFI_\(p)999:1")) }.isEmpty, p)
        }
    }

    func testHafasRequestBody() throws {
        let vbn = try XCTUnwrap(JSONSerialization.jsonObject(with: HafasSource.vbn.body("StationBoard", [:])) as? [String: Any])
        XCTAssertNil(vbn["ext"])
        XCTAssertEqual(vbn["ver"] as? String, "1.42")
        XCTAssertEqual((vbn["client"] as? [String: String])?["id"], "VBN")
        let oebb = try XCTUnwrap(JSONSerialization.jsonObject(with: HafasSource.oebb.body("StationBoard", [:])) as? [String: Any])
        XCTAssertEqual(oebb["ext"] as? String, "OEBB.1")
        XCTAssertEqual((oebb["auth"] as? [String: String])?["aid"], "OWDL4fE4ixNiPBBm")
    }

    func testHafasBerlinTimes() throws {
        // Summer: no offset in the reply, Berlin = UTC+2.
        let t = try XCTUnwrap(HafasSource.clockTime("20260715", "081500", offset: nil, tz: "Europe/Berlin"))
        XCTAssertEqual(t, ISO8601DateFormatter().date(from: "2026-07-15T06:15:00Z"))
        XCTAssertEqual(HafasSource.clockTime("20260715", "01001500", offset: 120, tz: "Europe/Berlin"),
                       ISO8601DateFormatter().date(from: "2026-07-15T22:15:00Z"))
    }
}


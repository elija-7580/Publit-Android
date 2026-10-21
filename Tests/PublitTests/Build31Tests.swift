import Foundation
import XCTest
@testable import Publit

final class QueryEncodingTests: XCTestCase {
    func testBase64PlusIsEncodedWithoutDamagingOtherCharacters() throws {
        let identifier = "a+b/c== with space&%2B"
        var components = URLComponents(string: "https://api.transitous.org/api/v6/refresh-itinerary")!
        components.queryItems = [URLQueryItem(name: "itineraryId", value: identifier),
                                 URLQueryItem(name: "other", value: "Wien + Graz")]
        components.percentEncodedQuery = TransitousClient.encodePlus(components.percentEncodedQuery)
        let query = try XCTUnwrap(components.percentEncodedQuery)
        XCTAssertFalse(query.contains("+"))
        XCTAssertTrue(query.contains("%2B"))
        XCTAssertTrue(query.contains("%252B"), "literal percent escapes must not be decoded twice")
        XCTAssertEqual(components.queryItems?.first?.value, identifier)
        // A form-style server converts raw '+' to space before percent-decoding.
        let encodedValue = try XCTUnwrap(query.components(separatedBy: "&").first?.components(separatedBy: "=").dropFirst().joined(separator: "="))
        XCTAssertEqual(encodedValue.replacingOccurrences(of: "+", with: " ").removingPercentEncoding, identifier)
        XCTAssertEqual(TransitousClient.encodePlus(query), query, "encoding must be idempotent")
        XCTAssertNil(TransitousClient.encodePlus(nil))
    }

    func testBackupPreservesEncodedPlus() {
        let url = URL(string: "https://api.transitous.org/api/v6/refresh-itinerary?itineraryId=a%2Bb%2Fc%3D")!
        let backup = TransitousClient.moved(url, to: TransitousClient.backupServer)
        XCTAssertEqual(URLComponents(url: backup, resolvingAgainstBaseURL: false)?.percentEncodedQuery,
                       "itineraryId=a%2Bb%2Fc%3D")
    }
}

final class TicketFieldMappingTests: XCTestCase {
    private var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/Vienna")!
        return calendar
    }

    func testEveryTextFieldIsExplicitAndPreservesEverythingElse() {
        let fields: [(TicketField, WritableKeyPath<TicketPass, String?>)] = [
            (.operatorName, \.operatorName), (.origin, \.origin), (.destination, \.destination),
            (.via, \.via), (.passenger, \.passenger), (.price, \.price), (.reference, \.reference), (.notes, \.notes),
        ]
        let original = TicketPass(id: "unchanged", title: "Original", category: .dayPass,
                                  barcode: .init(format: .qr, message: "unaltered"),
                                  document: .init(file: "ticket.pdf", kind: .pdf, page: 2), tripId: "trip")
        for (field, path) in fields {
            var pass = original
            XCTAssertTrue(field.apply("  Exact text: ÄÖ €19,90\n", to: &pass))
            var expected = original
            expected[keyPath: path] = "Exact text: ÄÖ €19,90"
            XCTAssertEqual(pass, expected, "only the chosen field may change: \(field)")
        }
        var pass = original
        XCTAssertTrue(TicketField.title.apply(" New title ", to: &pass))
        XCTAssertEqual(pass.title, "New title")
    }

    func testEmptyAndUnreadableAssignmentsDoNotEraseExistingValues() {
        let original = TicketPass(title: "Keep", origin: "Wien", validFrom: .distantPast, validUntil: .distantFuture)
        var pass = original
        XCTAssertFalse(TicketField.origin.apply(" \n ", to: &pass))
        XCTAssertFalse(TicketField.validFrom.apply("No date here", to: &pass))
        XCTAssertFalse(TicketField.validUntil.apply("31.02.2026", to: &pass))
        XCTAssertEqual(pass, original)
    }

    func testAssignedDayCoversTheWholeDay() throws {
        var pass = TicketPass()
        XCTAssertTrue(TicketField.validFrom.apply("Gültig am 14.10.2026", to: &pass, calendar: calendar))
        XCTAssertTrue(TicketField.validUntil.apply("14.10.2026", to: &pass, calendar: calendar))
        let from = try XCTUnwrap(pass.validFrom), until = try XCTUnwrap(pass.validUntil)
        XCTAssertEqual(calendar.component(.hour, from: from), 0)
        XCTAssertEqual(calendar.component(.hour, from: until), 23)
        XCTAssertEqual(calendar.component(.minute, from: until), 59)
        XCTAssertEqual(until.timeIntervalSince(from), 86_399)
    }

    func testAssignedRangeAndExplicitTimes() throws {
        var pass = TicketPass()
        let text = "14.10.2026 10:15 – 15.10.2026 18:30"
        XCTAssertTrue(TicketField.validFrom.apply(text, to: &pass, calendar: calendar))
        XCTAssertTrue(TicketField.validUntil.apply(text, to: &pass, calendar: calendar))
        XCTAssertEqual(calendar.component(.day, from: try XCTUnwrap(pass.validFrom)), 14)
        XCTAssertEqual(calendar.component(.hour, from: pass.validFrom!), 10)
        XCTAssertEqual(calendar.component(.minute, from: pass.validFrom!), 15)
        XCTAssertEqual(calendar.component(.day, from: try XCTUnwrap(pass.validUntil)), 15)
        XCTAssertEqual(calendar.component(.hour, from: pass.validUntil!), 18)
        XCTAssertEqual(calendar.component(.minute, from: pass.validUntil!), 30)
        XCTAssertEqual(TicketField.allCases.count, 11)
    }

    func testWholeMonthAndDaylightSaving() throws {
        let monthEnd = try XCTUnwrap(TicketTextParser.date(in: "Oktober 2026", end: true, calendar: calendar))
        XCTAssertEqual(calendar.component(.day, from: monthEnd), 31)
        let dayStart = try XCTUnwrap(TicketTextParser.date(in: "25.10.2026", end: false, calendar: calendar))
        let dayEnd = try XCTUnwrap(TicketTextParser.date(in: "25.10.2026", end: true, calendar: calendar))
        XCTAssertEqual(dayEnd.timeIntervalSince(dayStart), 25 * 3600 - 1, "the end follows local calendar time, not +86400")
    }
}

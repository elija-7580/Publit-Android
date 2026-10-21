import XCTest
@testable import Publit

final class PlanRefreshTests: XCTestCase {
    /// Coming back later must search from then: a departure time of "now" keeps following the clock.
    func testDepartureAtNowFollowsTheClockUntilAnotherTimeIsPicked() async {
        await MainActor.run {
        let m = PlanModel()
        m.timeMode = .departAt
        m.date = .now
        XCTAssertTrue(m.isLive)
        XCTAssertTrue(m.queryKey.contains("|now|"))
        m.date = .now.addingTimeInterval(3600)
        XCTAssertFalse(m.isLive)
        XCTAssertFalse(m.queryKey.contains("|now|"))
        m.date = .now
        XCTAssertTrue(m.isLive)
        m.timeMode = .arriveBy
        XCTAssertFalse(m.isLive)
        }
    }
}

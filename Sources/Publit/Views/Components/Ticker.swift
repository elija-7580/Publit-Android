import SwiftUI
import Observation
// Android: @Observable needs SkipFuse in this file, or the ticks never reach the Compose UI.
import SkipFuse

/// A clock that changes on whole interval boundaries (e.g. every full minute), as a plain
/// observable value instead of a `TimelineView`. Only views that read `now` redraw on a tick.
///
/// Freeze logs from build 11 showed SwiftUI's update loop on the main thread asking itself for
/// the next update while drawing (`requestUpdate(after:)`), for about 15 s, the period of the
/// trip screen's timeline. The timelines are replaced by these tickers.
@MainActor @Observable
final class Ticker {
    /// Shared minute clock for departure countdowns in lists: one timer for all rows.
    static let minute = Ticker(every: 60)

    private(set) var now = Date()
    @ObservationIgnored let interval: TimeInterval
    @ObservationIgnored private var loop: Task<Void, Never>?

    init(every interval: TimeInterval) {
        self.interval = interval
        loop = Task { [weak self] in
            while !Task.isCancelled {
                guard let delay = self?.delayToNextTick() else { return }
                try? await Task.sleep(for: .seconds(delay))
                guard let self, !Task.isCancelled else { return }
                self.now = Date()
            }
        }
    }

    private func delayToNextTick() -> TimeInterval {
        Ticker.delay(toNextMultipleOf: interval, from: Date())
    }

    /// Seconds until the next whole multiple of `interval` (counted from the reference date), plus a
    /// short margin so the new minute has begun when views redraw.
    nonisolated static func delay(toNextMultipleOf interval: TimeInterval, from date: Date) -> TimeInterval {
        let t = date.timeIntervalSinceReferenceDate
        let next = (floor(t / interval) + 1) * interval
        return max(0.05, next - t + 0.05)
    }
}

struct Ticking: ViewModifier {
    let interval: TimeInterval
    @Binding var now: Date

    func body(content: Content) -> some View {
        content.task(id: interval) {
            now = Date()
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(Ticker.delay(toNextMultipleOf: interval, from: Date())))
                if Task.isCancelled { return }
                now = Date()
            }
        }
    }
}

extension View {
    /// Sets `now` to the current time on appear and then on every whole `seconds` boundary while
    /// the view is on screen. Use for one screen-level clock instead of a `TimelineView`.
    func ticking(every seconds: TimeInterval, now: Binding<Date>) -> some View {
        modifier(Ticking(interval: seconds, now: now))
    }
}

/// Redraws `content` with the current time on every whole `every` seconds while on screen.
/// Replaces `TimelineView(.periodic…)` for one small part of a screen.
struct Clocked<Content: View>: View {
    let every: TimeInterval
    @ViewBuilder let content: (Date) -> Content
    @State var now = Date()

    init(every: TimeInterval, @ViewBuilder content: @escaping (Date) -> Content) {
        self.every = every
        self.content = content
    }

    var body: some View {
        content(now).ticking(every: every, now: $now)
    }
}

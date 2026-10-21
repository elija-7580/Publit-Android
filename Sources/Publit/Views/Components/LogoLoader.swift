import SwiftUI

/// Loading, in the logo's language: the "p" is drawn like a line on a map and its stop lands,
/// over and over (the launch splash once). Replaces the system spinner wherever a screen or
/// a list waits for its content. With Reduce Motion the mark stands still.
struct LogoLoader: View {
    /// Shown under the mark ("Reading ticket…"); nil for a plain loader.
    var label: String? = nil
    var size: CGFloat = 56
    @Environment(\.accessibilityReduceMotion) var reduceMotion
    @State var drawn: CGFloat = 0
    @State var landed = false

    var body: some View {
        VStack(spacing: 10) {
            mark
            if let label {
                Text(verbatim: label).font(.footnote.weight(.medium)).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 14)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: label ?? L10n.t("Loading…")))
        .accessibilityAddTraits(.updatesFrequently)
        .task(id: reduceMotion) { await loop() }
    }

    private var mark: some View {
        let s = size / 1024
        return ZStack {
            PublitMarkLine()
                .trim(from: 0, to: drawn)
                .stroke(Color.ink, style: StrokeStyle(lineWidth: 96 * s, lineCap: .round, lineJoin: .round))
            Circle()
                .fill(Color.signature)
                .frame(width: 116 * s, height: 116 * s)
                .scaleEffect(landed ? 1 : 0.1)
                .opacity(landed ? 1 : 0)
                .offset(y: (402 - 512) * s - (landed ? 0 : 18 * s * 4))
        }
        .frame(width: size, height: size)
    }

    private func loop() async {
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) { drawn = reduceMotion ? 1 : 0; landed = reduceMotion }
        guard !reduceMotion else { return }
        do {
            while !Task.isCancelled {
                withAnimation(.easeInOut(duration: 0.55)) { drawn = 1 }
                try await Task.sleep(for: .milliseconds(450))
                withAnimation(.spring(response: 0.35, dampingFraction: 0.6)) { landed = true }
                try await Task.sleep(for: .milliseconds(1000))
                withAnimation(.easeIn(duration: 0.25)) { landed = false; drawn = 0 }
                try await Task.sleep(for: .milliseconds(350))
            }
        } catch { /* The view left or Reduce Motion changed. */ }
    }
}

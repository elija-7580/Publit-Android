import SwiftUI

/// The comparison bars are clock-aligned, not an unexplained progress indicator.
struct ConnectionGuideView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                Text("All rows use the same clock. Later departures start farther right.")
                    .font(.title3.weight(.semibold)).fixedSize(horizontal: false, vertical: true)
                VStack(alignment: .leading, spacing: 16) {
                    HStack { Text(verbatim: "08:00"); Spacer(); Text(verbatim: "08:20"); Spacer(); Text(verbatim: "08:40") }
                        .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                    example(start: 0, label: "08:00 – 08:35", duration: "35 min")
                    example(start: 0.25, label: "08:10 – 08:40", duration: "30 min")
                }
                .padding(16).background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
                ConnectionSymbols()
                Text("The coloured blocks are rides. Thin grey sections are walking; dotted gaps are waiting between legs.")
                    .font(.body).fixedSize(horizontal: false, vertical: true)
                Text("This is a comparison, not live progress. Check the departure times, line names and change warnings.")
                    .font(.subheadline).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }.padding()
        }
        .liveBarPadding()
        .background(Color.ground)
        .navigationTitle(L10n.t("How to read connections"))
        .screenTrail("Connection guide")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func example(start: CGFloat, label: String, duration: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack { Text(verbatim: label).font(.headline.monospacedDigit()); Spacer(); Text(verbatim: duration).font(.subheadline) }
            GeometryReader { geometry in
                HStack(spacing: 0) {
                    Color.clear.frame(width: geometry.size.width * start)
                    RoundedRectangle(cornerRadius: 2).fill(Color.secondary).frame(width: geometry.size.width * 0.1, height: 4)
                    RoundedRectangle(cornerRadius: 5).fill(Color.ink)
                        .overlay { Text(verbatim: "REG").font(.caption.bold()).foregroundStyle(.white) }
                        .frame(width: geometry.size.width * (start == 0 ? 0.775 : 0.65), height: 24)
                }.frame(height: 24)
            }.frame(height: 24)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label + ", " + duration)
    }
}

struct ConnectionSymbols: View {
    #if os(Android)
    var typeSize: DynamicTypeSize { AndroidEnvironment.dynamicTypeSize }
    #else
    @Environment(\.dynamicTypeSize) var typeSize
    #endif
    var body: some View {
        // By text size rather than ViewThatFits, so a list row keeps one height.
        Group {
            if typeSize >= .xxxLarge {
                VStack(alignment: .leading, spacing: 12) { symbols }
            } else {
                HStack(spacing: 16) { symbols }
            }
        }
        .font(.footnote).foregroundStyle(.secondary)
    }
    @ViewBuilder private var symbols: some View {
        Label(L10n.t("Ride"), systemImage: "rectangle.fill").foregroundStyle(Color.ink)
        Label(L10n.t("Walk"), systemImage: "figure.walk")
        Label(L10n.t("Wait"), systemImage: "ellipsis")
    }
}

struct ConnectionLegendRow: View {
    let start, end: Date
    var tz: String? = nil
    /// The walk/change/… symbols under the time axis (Plan leaves them out).
    var showSymbols = true
    @State var showing = false
    @AppStorage("routeTimelines") var enabled = true

    var body: some View {
        if enabled {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text(verbatim: Fmt.time(start, tz: tz)).monospacedDigit()
                    Spacer()
                    Button { showing = true } label: { Label(L10n.t("Time axis"), systemImage: "questionmark.circle") }
                        .buttonStyle(.plain).accessibilityIdentifier("connectionGuide")
                    Spacer()
                    Text(verbatim: Fmt.time(end, tz: tz)).monospacedDigit()
                }
                .font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                if showSymbols { ConnectionSymbols() }
            }
            .sheet(isPresented: $showing) {
                NavigationStack {
                    ConnectionGuideView().toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { showing = false } } }
                }
                .environment(\.liveBarInset, 0)
            }
        }
    }
}

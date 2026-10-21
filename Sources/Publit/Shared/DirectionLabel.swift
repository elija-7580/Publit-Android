import SwiftUI

/// Same wording in departure boards, itinerary legs and widgets. Missing headsigns are explicit.
/// A circle line says so: "Towards S. Alvise · Circle line" on a leg, "Circle line" on a board.
struct DirectionLabel: View {
    let direction: String?
    var circular = false
    var font: Font = .subheadline

    var body: some View {
        Group {
            if let direction = TransitLabels.clean(direction) {
                (Text(L10n.t("Towards") + " ").foregroundColor(.secondary)
                 // Semibold, not bold: the destination leads the row, but the station name above stays the heaviest text.
                 + Text(verbatim: direction).foregroundColor(.primary).fontWeight(.semibold)
                 + Text(verbatim: circular ? " · " + L10n.t("Circle line") : "").foregroundColor(.secondary))
            } else if circular {
                Text("Circle line").foregroundColor(.primary).fontWeight(.semibold)
            } else {
                Text("Direction unavailable").foregroundColor(.secondary)
            }
        }
        .font(font)
        .fixedSize(horizontal: false, vertical: true)
        .accessibilityIdentifier("vehicleDirection")
    }
}

import SwiftUI

struct FareSection: View {
    let itinerary: Itinerary
    var body: some View {
        if !itinerary.fareProducts.isEmpty {
            Section {
                if let estimate = itinerary.fareEstimate {
                    LabeledContent("Fare estimate", value: estimate.formattedAmount)
                }
                ForEach(Array(itinerary.fareProducts.prefix(6).enumerated()), id: \.offset) { _, product in
                    VStack(alignment: .leading, spacing: 4) {
                        LabeledContent { Text(product.formattedAmount).monospacedDigit() } label: { Text(verbatim: product.name) }
                        if let category = product.riderCategory { Text(verbatim: category.riderCategoryName).font(.caption).foregroundStyle(.secondary) }
                        if let media = product.media?.fareMediaName { Text(verbatim: media).font(.caption).foregroundStyle(.secondary) }
                    }
                }
            } header: { Text("Fare information") }
            footer: { Text("Experimental operator fare data, not a binding quote. Product options are not necessarily the total trip price. Check eligibility and the operator's price before buying.") }
        }
    }
}

/// "Venice · ACTV": the network a route rides in and how to get a ticket there.
struct RegionTicketRow: View {
    let region: TransitRegion

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Label {
                Text(verbatim: region.title).font(.subheadline.weight(.semibold))
            } icon: {
                Image(systemName: "ticket").foregroundStyle(.secondary)
            }
            Text(verbatim: region.ticketText).font(.caption).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            if let url = region.link {
                Link(destination: url) {
                    Text(L10n.f("Fares and tickets: %@", region.network)).font(.caption.weight(.semibold))
                }
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("regionTicket")
    }
}

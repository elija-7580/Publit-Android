import SwiftUI

struct ServiceAlertRow: View {
    let alert: Alert
    var body: some View {
        DisclosureGroup {
            VStack(alignment: .leading, spacing: 8) {
                if !alert.descriptionText.isEmpty { Text(verbatim: alert.descriptionText).font(.subheadline) }
                if let url = TicketLinks.webURL(alert.url) { Link("Operator details", destination: url) }
            }
            .padding(.vertical, 6)
        } label: {
            VStack(alignment: .leading, spacing: 4) {
                Label(alert.kind, systemImage: alert.priority == 0 ? "exclamationmark.octagon.fill" : "exclamationmark.triangle.fill")
                    .font(.caption.weight(.semibold)).foregroundStyle(alert.priority == 0 ? Color.danger : Color.delay)
                Text(verbatim: alert.headerText).font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary).fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

struct ServiceAlertsView: View {
    let alerts: [Alert]
    var body: some View { ForEach(alerts, id: \.self) { ServiceAlertRow(alert: $0) } }
}

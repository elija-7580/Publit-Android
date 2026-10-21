import SwiftUI

/// Shown while a screen displays saved data because there is no connection.
struct OfflineNote: View {
    @State var connectivity = Connectivity.shared

    var body: some View {
        if let from = connectivity.offlineDataFrom {
            Label {
                Text("Offline. Showing data from \(Fmt.time(from)).")
            } icon: {
                Image(systemName: "wifi.slash")
            }
            .font(.footnote)
            .foregroundStyle(.secondary)
            .accessibilityIdentifier("offlineNote")
        }
    }
}

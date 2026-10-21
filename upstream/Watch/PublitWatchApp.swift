import SwiftUI

/// Publit on the Apple Watch: the departures around you, like the Nearby tab on the iPhone.
/// It talks to Transitous on its own (no iPhone needed nearby) and keeps no data of its own.
@main
struct PublitWatchApp: App {
    @State private var model = WatchNearbyModel()

    var body: some Scene {
        WindowGroup {
            NavigationStack {
                WatchNearbyView(model: model)
            }
        }
    }
}

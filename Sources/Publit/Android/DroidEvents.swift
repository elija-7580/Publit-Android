import Foundation
import SkipFuse

/// Kotlin → Swift: the Android side reports events here (location fixes, permission answers,
/// notification taps, map taps). Kotlin calls `DroidEvents.shared.…`.
/* SKIP @bridge */public final class DroidEvents: @unchecked Sendable {
    /* SKIP @bridge */public static let shared = DroidEvents()
    private init() {}

    /* SKIP @bridge */public func location(_ lat: Double, _ lon: Double, _ accuracy: Double, _ speed: Double, _ course: Double, _ timeMillis: Double) {
        #if os(Android)
        let fix = CLLocation(coordinate: CLLocationCoordinate2D(latitude: lat, longitude: lon), altitude: 0,
                             horizontalAccuracy: accuracy, verticalAccuracy: -1, course: course, speed: speed,
                             timestamp: Date(timeIntervalSince1970: timeMillis / 1000))
        Task { @MainActor in LocationManager.shared.receive(fix) }
        #endif
    }

    /* SKIP @bridge */public func heading(_ degrees: Double) {
        #if os(Android)
        Task { @MainActor in LocationManager.shared.receiveHeading(degrees < 0 ? nil : degrees) }
        #endif
    }

    /// 0 not asked, 1 denied, 2 allowed (while in use).
    /* SKIP @bridge */public func locationPermission(_ status: Int) {
        #if os(Android)
        Task { @MainActor in LocationManager.shared.receivePermission(status) }
        #endif
    }

    /// A notification or shortcut was tapped: open its publit:// link.
    /* SKIP @bridge */public func openURL(_ url: String) {
        guard let u = URL(string: url) else { return }
        Task { @MainActor in AppState.shared.open(u) }
    }

    /// A shared or opened file (ticket PDF/image) was copied to this local path.
    /* SKIP @bridge */public func openFile(_ path: String) {
        #if os(Android)
        Task { @MainActor in AppState.shared.open(URL(fileURLWithPath: path)) }
        #endif
    }

    /// A map reports what it shows / what was tapped (see AndroidMapView.swift).
    /* SKIP @bridge */public func mapEvent(_ mapId: String, _ json: String) {
        #if os(Android)
        Task { @MainActor in AndroidMapEvents.shared.receive(mapId, json) }
        #endif
    }

    /// A step for the diagnostics (heat, memory pressure …).
    /* SKIP @bridge */public func diagnosticNote(_ text: String) {
        #if os(Android)
        HangLog.shared.note(text)
        #endif
    }

    /// A result from an Android picker or scanner (photo, document, calendar …).
    /* SKIP @bridge */public func pickerResult(_ requestId: String, _ json: String) {
        #if os(Android)
        Task { @MainActor in DroidRequests.shared.finish(requestId, json) }
        #endif
    }
}


import Foundation

/// Features that are built but not shipped yet.
enum Features {
    /// Stored tickets, ticket apps and "Buy ticket" links. Off until the ticket screen is redesigned.
    static let tickets = false
    /// Tickets as passes (Tickets tab, trip tickets, Wallet). Replaces `tickets`.
    static let passes = true
}

enum AppInfo {
    static var version: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0.6.0"
    }
    static var build: String { Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?" }

    /// Contact required by the Transitous usage policy. Set `PublitContact` in project.yml.
    static var contact: String {
        Bundle.main.infoDictionary?["PublitContact"] as? String ?? "https://github.com"
    }

    static var userAgent: String { "Publit/\(version) (iOS; +\(contact))" }

    static var repositoryURL: URL { URL(string: contact) ?? URL(string: "https://github.com")! }
    static let sourcesURL = URL(string: "https://transitous.org/sources/")!
    static let websiteURL = URL(string: "https://elija-7580.github.io/publit-site/")!
    static let privacyURL = URL(string: "https://elija-7580.github.io/publit-site/privacy/")!
    static let osmCopyrightURL = URL(string: "https://www.openstreetmap.org/copyright")!
}

import Foundation
import SkipFuse
import Observation

/// A ticket the user keeps in Publit: a screenshot (e.g. KlimaTicket, a one-way ticket PDF page)
/// and/or a link that opens the app where the ticket lives. Stored on the device only.
struct Ticket: Codable, Identifiable, Hashable, Sendable {
    enum Kind: String, Codable, CaseIterable, Sendable {
        case pass, single
        var title: String { self == .pass ? L10n.t("Pass (valid for many trips)") : L10n.t("Single trip") }
    }

    let id: String
    var name: String
    var kind: Kind
    /// File name of the image inside the tickets folder.
    var imageFile: String?
    /// App or web link, e.g. a ticket app's URL scheme or its web shop.
    var appLink: String?
    var validUntil: Date?
    /// Single tickets belong to one saved trip.
    var tripId: String?
    let addedAt: Date

    var appURL: URL? { TicketLinks.url(appLink) }
    func isValid(at date: Date = .now) -> Bool { validUntil.map { $0 >= Calendar.current.startOfDay(for: date) } ?? true }
}

@MainActor
@Observable
final class TicketStore {
    private(set) var tickets: [Ticket] = []
    /// Operator key (TicketLinks.operatorKey) → link to the user's own ticket app or shop.
    private(set) var linkedApps: [String: String] = [:]

    @ObservationIgnored private let file: JSONFile<[Ticket]>
    @ObservationIgnored private let appsFile: JSONFile<[String: String]>
    @ObservationIgnored let imageDirectory: URL

    init(directory: URL? = nil) {
        let dir = directory ?? JSONFile<[Ticket]>.defaultDirectory
        file = JSONFile(name: "tickets.json", directory: dir)
        appsFile = JSONFile(name: "ticket-apps.json", directory: dir)
        imageDirectory = dir.appendingPathComponent("tickets", isDirectory: true)
        try? FileManager.default.createDirectory(at: imageDirectory, withIntermediateDirectories: true)
        tickets = file.load() ?? []
        linkedApps = appsFile.load() ?? [:]
    }

    var passes: [Ticket] { tickets.filter { $0.kind == .pass } }

    func ticket(_ id: String) -> Ticket? { tickets.first { $0.id == id } }

    /// What "Show ticket" opens for a trip: its own single tickets, then valid passes.
    func tickets(forTrip tripId: String?, at date: Date = .now) -> [Ticket] {
        let own = tripId.map { id in tickets.filter { $0.kind == .single && $0.tripId == id } } ?? []
        let valid = passes.filter { $0.isValid(at: date) }
        return own + valid
    }

    func imageURL(_ t: Ticket) -> URL? {
        t.imageFile.map { imageDirectory.appendingPathComponent($0) }
    }

    @discardableResult
    func add(name: String, kind: Ticket.Kind, image: Data?, appLink: String?, validUntil: Date?,
             tripId: String? = nil, now: Date = .now) -> Ticket {
        let id = UUID().uuidString
        var fileName: String?
        if let image {
            fileName = "\(id).img"
            try? image.write(to: imageDirectory.appendingPathComponent(fileName!), options: [.atomic, .completeFileProtection])
        }
        let t = Ticket(id: id, name: name.isEmpty ? (kind == .pass ? L10n.t("Pass") : L10n.t("Ticket")) : name, kind: kind,
                       imageFile: fileName, appLink: appLink?.nilIfEmpty, validUntil: validUntil,
                       tripId: kind == .single ? tripId : nil, addedAt: now)
        tickets.append(t)
        persist()
        return t
    }

    /// Production import: image writes do not block the main actor, and failures are visible.
    @discardableResult
    func addAsync(name: String, kind: Ticket.Kind, image: Data?, appLink: String?, validUntil: Date?,
                  tripId: String? = nil, now: Date = .now) async throws -> Ticket {
        let id = UUID().uuidString
        let fileName = image == nil ? nil : "\(id).img"
        if let image, let fileName {
            let url = imageDirectory.appendingPathComponent(fileName)
            try await Task.detached(priority: .userInitiated) {
                let trace = PerformanceTrace.begin("Import ticket image")
                defer { PerformanceTrace.end(trace, "Import ticket image") }
                try image.write(to: url, options: [.atomic, .completeFileProtection])
            }.value
        }
        let ticket = Ticket(id: id, name: name.isEmpty ? (kind == .pass ? L10n.t("Pass") : L10n.t("Ticket")) : name,
                            kind: kind, imageFile: fileName, appLink: appLink?.nilIfEmpty, validUntil: validUntil,
                            tripId: kind == .single ? tripId : nil, addedAt: now)
        tickets.append(ticket)
        persist()
        // Finish critical metadata before dismissing the import UI.
        await Task.detached(priority: .userInitiated) { JSONPersistence.flush() }.value
        return ticket
    }

    func update(_ t: Ticket) {
        guard let i = tickets.firstIndex(where: { $0.id == t.id }) else { return }
        tickets[i] = t
        persist()
    }

    func remove(_ id: String) {
        if let t = ticket(id), let url = imageURL(t) { try? FileManager.default.removeItem(at: url) }
        tickets.removeAll { $0.id == id }
        persist()
    }

    func linkApp(_ link: String?, forOperator name: String) {
        let key = TicketLinks.operatorKey(name)
        if let link = link?.trimmingCharacters(in: .whitespacesAndNewlines), !link.isEmpty, TicketLinks.url(link) != nil {
            linkedApps[key] = link
        } else {
            linkedApps[key] = nil
        }
        appsFile.saveInBackground(linkedApps)
    }

    private func persist() { file.saveInBackground(tickets) }
}

import Foundation
import SkipFuse
import Observation

/// Passes and their original documents, on the device only (file protection: complete).
@MainActor
@Observable
final class PassStore {
    private(set) var passes: [TicketPass] = []

    @ObservationIgnored private let file: JSONFile<[TicketPass]>
    @ObservationIgnored let documentDirectory: URL
    @ObservationIgnored private var commitTail: Task<Void, Never>?
    @ObservationIgnored private var revision = 0

    /// Documents above this size are rejected; tickets are a few pages at most.
    static let maxDocumentBytes = 25 * 1024 * 1024

    enum Failure: LocalizedError, Equatable {
        case documentTooLarge
        var errorDescription: String? { L10n.t("This file is too large for a ticket.") }
    }

    init(directory: URL? = nil) {
        let dir = directory ?? JSONFile<[TicketPass]>.defaultDirectory
        file = JSONFile(name: "passes.json", directory: dir)
        documentDirectory = dir.appendingPathComponent("passes", isDirectory: true)
        try? FileManager.default.createDirectory(at: documentDirectory, withIntermediateDirectories: true)
        passes = file.load() ?? []
    }

    func pass(_ id: String) -> TicketPass? { passes.first { $0.id == id } }

    /// A pass by its own ID or by the ID of the old ticket it came from (links in calendars and Live Activities).
    func pass(linkedTo id: String) -> TicketPass? { pass(id) ?? passes.first { $0.legacyId == id } }

    func documentURL(_ pass: TicketPass) -> URL? {
        pass.document.map { documentDirectory.appendingPathComponent($0.file) }
    }

    func logoURL(_ pass: TicketPass) -> URL? {
        pass.logo.map { documentDirectory.appendingPathComponent($0.file) }
    }

    /// Sets or removes (nil) the operator logo of a saved pass. PNG data, already scaled down.
    func setLogo(_ png: Data?, source: String?, for id: String) {
        guard var pass = pass(id) else { return }
        if let old = logoURL(pass) { try? FileManager.default.removeItem(at: old) }
        if let png {
            // A new name each time, so views showing the old file reload.
            let name = "\(id)-logo-\(Int(Date().timeIntervalSince1970)).png"
            guard (try? png.write(to: documentDirectory.appendingPathComponent(name), options: [.atomic, .completeFileProtection])) != nil
            else { return }
            pass.logo = TicketPass.Logo(file: name, source: source)
        } else {
            pass.logo = nil
        }
        update(pass)
    }

    func grouped(at date: Date = .now) -> [(group: PassGroup, passes: [TicketPass])] { PassGroup.grouped(passes, at: date) }

    /// Expired a day ago or longer, or moved there by hand. Newest first.
    func archived(at date: Date = .now) -> [TicketPass] {
        passes.filter { $0.isArchived(at: date) }.sorted { ($0.archiveDate, $0.createdAt) > ($1.archiveDate, $1.createdAt) }
    }

    func archive(_ id: String, now: Date = .now) {
        guard var pass = pass(id) else { return }
        pass.archivedAt = now
        pass.keptOut = nil
        update(pass, now: now)
    }

    /// Back into the list; an expired ticket then stays there until archived again.
    func unarchive(_ id: String, now: Date = .now) {
        guard var pass = pass(id) else { return }
        pass.archivedAt = nil
        if case .expired = pass.validity(at: now) { pass.keptOut = true } else { pass.keptOut = nil }
        update(pass, now: now)
    }

    /// Deletes every archived ticket with its original and logo. Returns how many.
    @discardableResult
    func removeArchived(at date: Date = .now) -> Int {
        let gone = archived(at: date)
        for p in gone {
            if let url = documentURL(p) { try? FileManager.default.removeItem(at: url) }
            if let url = logoURL(p) { try? FileManager.default.removeItem(at: url) }
        }
        let ids = Set(gone.map(\.id))
        passes.removeAll { ids.contains($0.id) }
        if !gone.isEmpty { persist() }
        return gone.count
    }

    /// What a trip offers: every ticket added for it (oldest first), then tickets valid at the trip's time:
    /// passes, and dated tickets that belong to no trip (e.g. a single ticket added in the wallet).
    func passes(forTrip tripId: String?, at date: Date = .now) -> [TicketPass] {
        let own = tripId.map { id in passes.filter { $0.tripId == id }.sorted { $0.createdAt < $1.createdAt } } ?? []
        let valid = passes.filter { p in
            guard !own.contains(p), p.archivedAt == nil, p.category.isPass || p.tripId == nil else { return false }
            if case .valid = p.validity(at: date) { return true }
            return false
        }
        return own + valid
    }

    /// Saves a checked pass. The document is written away from the main actor before the pass appears.
    @discardableResult
    func add(_ pass: TicketPass, document: Data? = nil, kind: TicketPass.Document.Kind? = nil, page: Int = 0, deduplicate: Bool = false) async throws -> TicketPass {
        try Task.checkCancellation()
        var pass = pass
        var writtenURL: URL?
        if let document, let kind {
            guard document.count <= Self.maxDocumentBytes else { throw Failure.documentTooLarge }
            let fingerprint = await Task.detached(priority: .userInitiated) {
                TicketImportPolicy.fingerprint(document, page: page)
            }.value
            try Task.checkCancellation()
            if deduplicate, let existing = passes.first(where: { $0.document?.fingerprint == fingerprint }) {
                return existing
            }
            let name = "\(pass.id).\(kind == .pdf ? "pdf" : "img")"
            let url = documentDirectory.appendingPathComponent(name)
            try await Task.detached(priority: .userInitiated) {
                try document.write(to: url, options: [.atomic, .completeFileProtection])
            }.value
            if Task.isCancelled {
                try? FileManager.default.removeItem(at: url)
                throw CancellationError()
            }
            // The actor can reenter during file writing; a second import may have finished first.
            if deduplicate, let existing = passes.first(where: { $0.document?.fingerprint == fingerprint }) {
                try? FileManager.default.removeItem(at: url)
                return existing
            }
            pass.document = TicketPass.Document(file: name, kind: kind, page: page, fingerprint: fingerprint)
            writtenURL = url
        }
        let candidate = pass, documentURL = writtenURL, previous = commitTail
        let commit = Task { @MainActor in
            if let previous { await previous.value }
            try Task.checkCancellation()
            if deduplicate, let hash = candidate.document?.fingerprint,
               let existing = passes.first(where: { $0.document?.fingerprint == hash }) {
                if let documentURL { try? FileManager.default.removeItem(at: documentURL) }
                return existing
            }
            // Other saved tickets can be edited during disk I/O. Repeat with their latest state
            // rather than publishing an older snapshot over those edits.
            while true {
                let observed = revision
                var snapshot = passes.filter { $0.id != candidate.id }
                snapshot.append(candidate)
                try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                    file.saveInBackground(snapshot) { continuation.resume(with: $0) }
                }
                if observed == revision {
                    passes = snapshot
                    revision += 1
                    return candidate
                }
            }
        }
        commitTail = Task { _ = try? await commit.value }
        do {
            return try await withTaskCancellationHandler { try await commit.value } onCancel: { commit.cancel() }
        } catch {
            if let writtenURL { try? FileManager.default.removeItem(at: writtenURL) }
            throw error
        }
    }

    /// Adds or replaces a pass that needs no file work (samples, tests, imports).
    func insert(_ pass: TicketPass) {
        if let i = passes.firstIndex(where: { $0.id == pass.id }) { passes[i] = pass } else { passes.append(pass) }
        persist()
    }

    func update(_ pass: TicketPass, now: Date = .now) {
        guard let i = passes.firstIndex(where: { $0.id == pass.id }) else { return }
        var updated = pass
        updated.updatedAt = now
        passes[i] = updated
        persist()
    }

    func remove(_ id: String) {
        if let p = pass(id) {
            if let url = documentURL(p) { try? FileManager.default.removeItem(at: url) }
            if let url = logoURL(p) { try? FileManager.default.removeItem(at: url) }
        }
        passes.removeAll { $0.id == id }
        persist()
    }

    /// Takes over tickets from the first ticket version (screenshot + name + validity). Runs once per ticket.
    @discardableResult
    func importLegacy(_ tickets: [Ticket], imageDirectory: URL, now: Date = .now) -> Int {
        var count = 0
        for t in tickets where !passes.contains(where: { $0.legacyId == t.id }) {
            var pass = TicketPass(title: t.name, category: t.kind == .pass ? .period : .single, validUntil: t.validUntil,
                                  tripId: t.tripId, legacyId: t.id, createdAt: t.addedAt, updatedAt: now)
            if let file = t.imageFile {
                let source = imageDirectory.appendingPathComponent(file)
                let name = "\(pass.id).img"
                if (try? FileManager.default.copyItem(at: source, to: documentDirectory.appendingPathComponent(name))) != nil {
                    pass.document = TicketPass.Document(file: name, kind: .image, page: 0)
                }
            }
            if let link = t.appLink { pass.notes = link }
            passes.append(pass)
            count += 1
        }
        if count > 0 { persist() }
        return count
    }

    private func persist() { revision += 1; file.saveInBackground(passes) }
}

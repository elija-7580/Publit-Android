import Foundation

/// Conservative page-recognition rules. Recognition does not verify authenticity or transport rights.
enum TicketImportPolicy {
    /// Bound work for shared PDFs; longer or ambiguous documents go through the page picker.
    static let maxAutomaticPages = 12

    static func isRecognizable(_ draft: TicketDraft, barcodes: [TicketPass.Barcode]) -> Bool {
        if let from = draft.validFrom, let until = draft.validUntil, until < from { return false }
        // A barcode alone may be a website link; an operator alone may be an advertisement.
        guard draft.category != nil else { return false }
        return draft.operatorName != nil || !barcodes.isEmpty
            || (draft.origin != nil && draft.destination != nil)
    }

    /// Exact-document duplicate detection, not authentication. Page is part of the identity:
    /// outward/return tickets in the same PDF must stay separate.
    static func fingerprint(_ data: Data, page: Int) -> String { SHA1.hex(data) + ":\(page)" }
}

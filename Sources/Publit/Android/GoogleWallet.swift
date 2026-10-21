#if os(Android)
import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Optional Google Wallet export. The issuer's key stays on the signing server, never in this app.
/// Binary railway codes cannot safely be re-encoded as text, so those stay in Publit.
enum GoogleWallet {
    static var endpoint: URL? {
        guard let raw = Droid.string("walletSigner"), let url = URL(string: raw), url.scheme == "https", url.host != nil else { return nil }
        return url
    }
    static func available(_ pass: TicketPass) -> Bool {
        guard endpoint != nil, let code = pass.barcode, code.payload == nil,
              let text = code.message, !text.isEmpty, text.utf8.count <= 4096 else { return false }
        return ["qr", "aztec", "pdf417", "code128"].contains(code.format.rawValue)
    }
    static func text(_ key: String) -> String { Droid.string("walletText", key) ?? key }

    static func export(_ pass: TicketPass) async throws -> URL {
        guard available(pass), let endpoint, let barcode = pass.barcode else { throw ExportError.invalid }
        var fields: [String: Any] = ["id": pass.id, "title": pass.displayTitle,
            "operator": pass.operatorName ?? "Publit", "barcode": ["type": barcode.format.rawValue, "value": barcode.message ?? ""]]
        if let origin = pass.origin { fields["origin"] = origin }
        if let destination = pass.destination { fields["destination"] = destination }
        if let date = pass.validFrom { fields["validFrom"] = ISO8601DateFormatter().string(from: date) }
        if let date = pass.validUntil { fields["validUntil"] = ISO8601DateFormatter().string(from: date) }
        var request = URLRequest(url: endpoint, timeoutInterval: 25)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: fields)
        let (data, response) = try await URLSession.shared.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200,
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: String],
              let value = object["saveUrl"], let url = URL(string: value), url.scheme == "https",
              url.host == "pay.google.com", url.path.hasPrefix("/gp/v/save/") else { throw ExportError.invalid }
        return url
    }
    enum ExportError: LocalizedError {
        case invalid
        var errorDescription: String? { GoogleWallet.text("wallet_error") }
    }
}
#endif

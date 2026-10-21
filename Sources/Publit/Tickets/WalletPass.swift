import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Wallet export settings. Apple Wallet only accepts passes signed with a Pass Type ID certificate
/// from a paid Apple Developer account. The private key must never ship in the app, so a small
/// signing service holds it (see services/pass-signer). Without these Info.plist keys export stays off.
struct WalletPassConfig: Equatable, Sendable {
    let passTypeIdentifier: String
    let teamIdentifier: String
    let signerURL: URL

    init?(passTypeIdentifier: String?, teamIdentifier: String?, signerURL: String?) {
        guard let passTypeIdentifier, passTypeIdentifier.hasPrefix("pass."), let teamIdentifier, teamIdentifier.count == 10,
              let signerURL, let url = URL(string: signerURL), url.scheme == "https", url.host?.isEmpty == false else { return nil }
        self.passTypeIdentifier = passTypeIdentifier
        self.teamIdentifier = teamIdentifier
        self.signerURL = url
    }

    static var current: WalletPassConfig? {
        let info = Bundle.main.infoDictionary ?? [:]
        return WalletPassConfig(passTypeIdentifier: info["PublitPassTypeIdentifier"] as? String,
                                teamIdentifier: info["PublitTeamIdentifier"] as? String,
                                signerURL: info["PublitPassSignerURL"] as? String)
    }
}

enum WalletPassError: LocalizedError, Equatable {
    case missingIcon
    case unsupportedCode
    case signer(Int)
    case invalidSignature

    var errorDescription: String? {
        switch self {
        case .missingIcon, .invalidSignature: return L10n.t("The Wallet pass could not be created.")
        case .unsupportedCode: return L10n.t("This code cannot be added to Apple Wallet. Show it in Publit instead.")
        case .signer: return L10n.t("Apple Wallet is not available right now. Please try again later.")
        }
    }
}

/// Builds the files of a `.pkpass` bundle: pass.json, images, manifest.json, signature.
enum WalletPassBuilder {
    struct Field: Encodable, Equatable {
        let key: String
        var label: String?
        let value: String
        var dateStyle: String?
        var timeStyle: String?
        var textAlignment: String?
    }

    struct Structure: Encodable, Equatable {
        var transitType: String?
        var headerFields: [Field]?
        var primaryFields: [Field]
        var secondaryFields: [Field]?
        var auxiliaryFields: [Field]?
        var backFields: [Field]?
    }

    struct Barcode: Encodable, Equatable {
        let format: String
        let message: String
        let messageEncoding: String
        var altText: String?
    }

    struct Pass: Encodable, Equatable {
        let formatVersion = 1
        let passTypeIdentifier: String
        let teamIdentifier: String
        let serialNumber: String
        let organizationName: String
        let description: String
        var logoText: String?
        let foregroundColor: String
        let backgroundColor: String
        let labelColor: String
        var relevantDate: String?
        var expirationDate: String?
        let sharingProhibited = true
        var barcodes: [Barcode]?
        var boardingPass: Structure?
        var generic: Structure?
    }

    static func pass(for pass: TicketPass, config: WalletPassConfig, calendar: Calendar = .current) throws -> Pass {
        let iso = ISO8601DateFormatter()
        iso.timeZone = calendar.timeZone
        func date(_ d: Date?) -> String? { d.map { iso.string(from: $0) } }
        func dateField(_ key: String, _ label: String, _ d: Date?, end: Bool) -> Field? {
            guard let d else { return nil }
            let c = calendar.dateComponents([.hour, .minute], from: d)
            let dayOnly = end ? (c.hour == 23 && c.minute == 59) : (c.hour == 0 && c.minute == 0)
            return Field(key: key, label: label, value: iso.string(from: d), dateStyle: "PKDateStyleMedium",
                         timeStyle: dayOnly ? "PKDateStyleNone" : "PKDateStyleShort")
        }

        var barcodes: [Barcode]?
        if let code = pass.barcode {
            guard let message = code.walletMessage, let format = code.format.walletFormat else { throw WalletPassError.unsupportedCode }
            barcodes = [Barcode(format: format, message: message, messageEncoding: code.walletEncoding,
                                altText: pass.reference ?? code.altText)]
        }

        let header = [dateField("until", L10n.t("Valid until"), pass.validUntil, end: true)].compactMap { $0 }
        let secondary = [
            pass.passenger.map { Field(key: "passenger", label: L10n.t("Passenger"), value: $0) },
            pass.travelClass.map { Field(key: "class", label: L10n.t("Class"), value: $0) },
        ].compactMap { $0 }
        let auxiliary = [
            dateField("from", L10n.t("Valid from"), pass.validFrom, end: false),
            pass.price.map { Field(key: "price", label: L10n.t("Price"), value: $0) },
        ].compactMap { $0 }
        let back = [
            pass.reference.map { Field(key: "reference", label: L10n.t("Ticket number"), value: $0) },
            pass.operatorName.map { Field(key: "operator", label: L10n.t("Operator"), value: $0) },
            pass.notes.map { Field(key: "notes", label: L10n.t("Notes"), value: $0) },
            Field(key: "copy", label: L10n.t("About this pass"),
                  value: L10n.t("A copy of your ticket, made with Publit. Only the ticket the operator issued is valid; keep it with you.")),
        ].compactMap { $0 }

        var out = Pass(passTypeIdentifier: config.passTypeIdentifier, teamIdentifier: config.teamIdentifier,
                       serialNumber: pass.id, organizationName: pass.operatorName ?? "Publit",
                       description: pass.displayTitle, logoText: pass.displayTitle,
                       foregroundColor: PassStyle.walletColor(pass.style.foreground),
                       backgroundColor: PassStyle.walletColor(pass.style.background),
                       labelColor: PassStyle.walletColor(pass.style.label),
                       relevantDate: date(pass.validFrom), expirationDate: date(pass.validUntil), barcodes: barcodes)
        if pass.hasRoute, let origin = pass.origin, let destination = pass.destination {
            let transit: String = switch pass.symbol {
            case .bus: "PKTransitTypeBus"
            case .ferry: "PKTransitTypeBoat"
            case .train, .tram, .metro, .ticket: "PKTransitTypeTrain"
            }
            out.boardingPass = Structure(transitType: transit, headerFields: header.nilIfEmpty,
                                         primaryFields: [Field(key: "origin", label: L10n.t("From"), value: origin),
                                                         Field(key: "destination", label: L10n.t("To"), value: destination)],
                                         secondaryFields: secondary.nilIfEmpty, auxiliaryFields: auxiliary.nilIfEmpty, backFields: back)
        } else {
            out.generic = Structure(headerFields: header.nilIfEmpty,
                                    primaryFields: [Field(key: "title", label: pass.category.title, value: pass.displayTitle)],
                                    secondaryFields: secondary.nilIfEmpty, auxiliaryFields: auxiliary.nilIfEmpty, backFields: back)
        }
        return out
    }

    static func passJSON(for pass: TicketPass, config: WalletPassConfig, calendar: Calendar = .current) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return try encoder.encode(Self.pass(for: pass, config: config, calendar: calendar))
    }

    /// `{"file name": "sha1 hex"}` for every file in the bundle except the manifest and signature.
    static func manifest(_ files: [String: Data]) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return try encoder.encode(files.mapValues(SHA1.hex))
    }

    static func package(files: [String: Data], manifest: Data, signature: Data) -> Data {
        let entries = files.keys.sorted().map { ZipWriter.Entry(name: $0, data: files[$0]!) }
            + [ZipWriter.Entry(name: "manifest.json", data: manifest), ZipWriter.Entry(name: "signature", data: signature)]
        return ZipWriter.archive(entries)
    }
}

/// Produces the detached PKCS #7 signature of manifest.json.
protocol WalletPassSigning: Sendable {
    func signature(passJSON: Data, manifest: Data) async throws -> Data
}

/// Sends pass.json and manifest.json to the signing service, which checks them and returns the signature.
/// Nothing else leaves the device: no document, no image of the ticket.
struct RemoteWalletPassSigner: WalletPassSigning {
    let endpoint: URL
    var session: URLSession = .shared

    private struct Request: Encodable { let pass: String; let manifest: String }

    func signature(passJSON: Data, manifest: Data) async throws -> Data {
        var request = URLRequest(url: endpoint, timeoutInterval: 20)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(Request(pass: passJSON.base64EncodedString(), manifest: manifest.base64EncodedString()))
        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard status == 200 else { throw WalletPassError.signer(status) }
        // A DER-encoded PKCS #7 structure starts with a SEQUENCE tag.
        guard data.count > 64, data.count < 64 * 1024, data.first == 0x30 else { throw WalletPassError.invalidSignature }
        return data
    }
}

enum WalletPassExport {
    /// `images` needs at least icon.png; icon@2x.png and icon@3x.png are recommended.
    static func make(pass: TicketPass, images: [String: Data], config: WalletPassConfig,
                     signer: any WalletPassSigning, calendar: Calendar = .current) async throws -> Data {
        guard images["icon.png"] != nil else { throw WalletPassError.missingIcon }
        let json = try WalletPassBuilder.passJSON(for: pass, config: config, calendar: calendar)
        var files = images
        files["pass.json"] = json
        let manifest = try WalletPassBuilder.manifest(files)
        let signature = try await signer.signature(passJSON: json, manifest: manifest)
        return WalletPassBuilder.package(files: files, manifest: manifest, signature: signature)
    }
}

private extension Array {
    var nilIfEmpty: [Element]? { isEmpty ? nil : self }
}

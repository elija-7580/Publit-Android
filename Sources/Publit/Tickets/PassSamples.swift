#if DEBUG
import SwiftUI

/// Demo passes for UI tests and screenshots of the ticket screens, which are not in the app yet.
/// Every pass is marked as a demo; none is a real ticket.
enum PassSamples {
    @MainActor
    static func store() -> PassStore {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("publit-pass-samples-\(UUID().uuidString)", isDirectory: true)
        let store = PassStore(directory: directory)
        let calendar = Calendar.current
        let today = calendar.startOfDay(for: .now)
        func day(_ offset: Int) -> Date { calendar.date(byAdding: .day, value: offset, to: today)! }
        let month = calendar.dateInterval(of: .month, for: .now)!
        store.insert(TicketPass(id: "demo-rail", title: "Sparschiene (Demo)", operatorName: "ÖBB", category: .single, symbol: .train,
                                origin: "Wien Hbf", destination: "Salzburg Hbf", passenger: "Max Muster", travelClass: "2",
                                price: "€ 19,90", reference: "DEMO 1234 5678", validFrom: today, validUntil: day(1).addingTimeInterval(-1),
                                barcode: .init(format: .aztec, message: "PUBLIT DEMO - KEIN FAHRSCHEIN"),
                                style: PassStyle(background: "D70015"), notes: "Demo. Kein Fahrschein.", createdAt: day(-1)))
        store.insert(TicketPass(id: "demo-month", title: "Deutschlandticket (Demo)", operatorName: "Demo-Verbund", category: .subscription,
                                symbol: .tram, passenger: "Erika Muster", reference: "DEMO-DT-0001", validFrom: month.start,
                                validUntil: month.end.addingTimeInterval(-1), barcode: .init(format: .qr, message: "PUBLIT DEMO"),
                                style: .standard, createdAt: day(-2)))
        store.insert(TicketPass(id: "demo-day", title: "Tageskarte (Demo)", operatorName: "Wiener Linien", category: .dayPass, symbol: .metro,
                                validFrom: day(1), validUntil: day(2).addingTimeInterval(-1),
                                barcode: .init(format: .qr, message: "PUBLIT DEMO DAY"), style: PassStyle(background: "0066D6"), createdAt: day(-1)))
        store.insert(TicketPass(id: "demo-old", title: "Einzelfahrt (Demo)", operatorName: "MVV", category: .single, symbol: .metro,
                                origin: "Marienplatz", destination: "Nordfriedhof", validUntil: day(-3).addingTimeInterval(18 * 3600),
                                style: PassStyle(background: "0065AE"), createdAt: day(-4)))
        return store
    }

    /// Text as the recogniser would return it from a demo ticket.
    static let lines = [
        "ÖBB", "Sparschiene", "DEMO – KEIN FAHRSCHEIN", "Von: Wien Hbf", "Nach: Innsbruck Hbf",
        "Gültig am \(Date.now.formatted(.dateTime.day(.twoDigits).month(.twoDigits).year()))", "2. Klasse",
        "Reisender: Max Muster", "Preis: € 29,90", "Ticket-Nr.: DEMO 9876 5432",
    ]

    static func editorMode() -> PassEditorView.Mode {
        let code = TicketPass.Barcode(format: .aztec, message: "PUBLIT DEMO - KEIN FAHRSCHEIN")
        let draft = TicketTextParser.parse(lines: lines, barcodes: [code])
        return .new(TicketPass(draft: draft, barcode: code), document: nil, kind: nil, page: 0, codes: [code], colorFromTicket: "8C1D5A")
    }
}

struct PassSampleView: View {
    let kind: String
    @State var store = PassSamples.store()

    var body: some View {
        Group {
            if kind == "pass-import" {
                PassImportView()
            } else {
                NavigationStack {
                    switch kind {
                    case "pass": PassDetailView(passId: "demo-rail")
                    case "pass-editor": PassEditorView(mode: PassSamples.editorMode())
                    default: PassWalletView()
                    }
                }
            }
        }
        .environment(store)
    }
}
#endif

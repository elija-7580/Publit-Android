#if canImport(PhotosUI)
import PhotosUI
#endif
import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// Logos as Publit keeps them: decoded, at most 480 × 160 pixels, PNG.
enum LogoImages {
    static func prepare(_ data: Data) -> Data? {
        #if os(Android)
        // Android: scaled by Kotlin (Bitmap), transparency kept.
        let out = DroidScratch.file("png")
        defer { try? FileManager.default.removeItem(at: out) }
        let ok = DroidScratch.with(data, pdf: false) { Droid.bool("fitLogo", $0, 480.0, 160.0, out.path) } ?? false
        return ok ? try? Data(contentsOf: out) : nil
        #else
        guard let image = UIImage(data: data) else { return nil }
        let px = CGSize(width: image.size.width * image.scale, height: image.size.height * image.scale)
        guard px.width >= 16, px.height >= 16 else { return nil }
        let f = min(1, min(480 / px.width, 160 / px.height))
        let size = CGSize(width: max(1, (px.width * f).rounded()), height: max(1, (px.height * f).rounded()))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = false
        return UIGraphicsImageRenderer(size: size, format: format).pngData { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
        }
        #endif
    }

    static func fetch(_ candidate: LogoCandidate) async -> Data? {
        guard let raw = try? await OperatorLogos.download(candidate.url) else { return nil }
        return await Task.detached(priority: .userInitiated) { prepare(raw) }.value
    }

    static var language: String { Locale.current.language.languageCode?.identifier ?? "en" }

    /// The first logo of a known operator, for a new ticket. Unknown names are only looked up
    /// when the user opens the logo picker.
    static func suggestion(for operatorName: String?) async -> (png: Data, credit: String)? {
        guard let name = operatorName, let op = KnownOperators.find(name), op.wikidata != nil else { return nil }
        let list = await OperatorLogos.lookup(name: op.name, language: language) { try await OperatorLogos.download($0) }
        for candidate in list.prefix(2) {
            if let png = await fetch(candidate) { return (png, candidate.credit) }
        }
        return nil
    }
}

/// Choose the operator's logo: suggestions from Wikimedia Commons and the operator's website,
/// an image from Photos, or none.
struct LogoPickerView: View {
    let operatorName: String
    let onPick: (_ png: Data?, _ credit: String?) -> Void

    @Environment(\.dismiss) var dismiss
    @State var query = ""
    @State var candidates: [LogoCandidate] = []
    @State var images: [String: Data] = [:]
    @State var loading = false
    @State var searched = false
    @State var photo: PhotosPickerItem?

    var body: some View {
        List {
            Section {
                HStack {
                    TextField(L10n.t("Operator"), text: $query)
                        .submitLabel(.search)
                        .onSubmit { Task { await search() } }
                        .autocorrectionDisabled()
                    Button { Task { await search() } } label: { Image(systemName: "magnifyingglass") }
                        .accessibilityLabel(L10n.t("Search"))
                        .disabled(query.trimmingCharacters(in: .whitespaces).count < 2)
                }
            }
            Section {
                if loading {
                    LogoLoader()
                } else if searched && shown.isEmpty {
                    Text("No logo found. Try another name or choose an image.").foregroundStyle(.secondary)
                }
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 140), spacing: 12)], spacing: 12) {
                    ForEach(shown) { candidate in
                        Button { pick(candidate) } label: { tile(candidate) }
                            .buttonStyle(.plain)
                            .accessibilityLabel(candidate.title)
                            .accessibilityHint(candidate.credit)
                    }
                }
                .padding(.vertical, shown.isEmpty ? 0 : 6)
            } header: {
                Text("Suggestions")
            } footer: {
                Text("From Wikimedia Commons (via Wikidata) or the operator's website. Only the operator name is sent. Logos are trademarks of their owners.")
            }
            Section {
                PhotosPicker(selection: $photo, matching: .images) {
                    Label(L10n.t("Choose image"), systemImage: "photo")
                }
                Button(role: .destructive) {
                    onPick(nil, nil)
                    dismiss()
                } label: {
                    Label(L10n.t("No logo"), systemImage: "nosign")
                }
            }
        }
        .inkList()
        .navigationTitle(L10n.t("Logo"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
        }
        .task {
            if query.isEmpty { query = operatorName }
            if !searched, query.count >= 2 { await search() }
        }
        .onChange(of: photo) { _, item in
            guard let item else { return }
            Task {
                if let data = try? await item.loadTransferable(type: Data.self),
                   let png = await Task.detached(priority: .userInitiated, operation: { LogoImages.prepare(data) }).value {
                    onPick(png, nil)
                    dismiss()
                }
                photo = nil
            }
        }
    }

    /// Only candidates whose image loaded (broken links and non-images are left out).
    private var shown: [LogoCandidate] { candidates.filter { images[$0.id] != nil } }

    private func tile(_ candidate: LogoCandidate) -> some View {
        VStack(spacing: 6) {
            Group {
                if let data = images[candidate.id], let image = UIImage(data: data) {
                    Image(uiImage: image).resizable().interpolation(.high).scaledToFit()
                }
            }
            .frame(height: 56)
            .frame(maxWidth: .infinity)
            .padding(10)
            .background(.white, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).stroke(Color.primary.opacity(0.12)))
            Text(verbatim: candidate.title).font(.caption.weight(.semibold)).lineLimit(1)
            Text(verbatim: candidate.source == .website ? L10n.t("Website icon") : "Wikimedia Commons")
                .font(.caption2).foregroundStyle(.secondary).lineLimit(1)
        }
    }

    private func pick(_ candidate: LogoCandidate) {
        guard let png = images[candidate.id] else { return }
        onPick(png, candidate.credit)
        dismiss()
    }

    private func search() async {
        let name = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard name.count >= 2, !loading else { return }
        loading = true
        defer { loading = false; searched = true }
        let found = await OperatorLogos.lookup(name: name, language: LogoImages.language) { try await OperatorLogos.download($0) }
        candidates = found
        images = [:]
        await withTaskGroup(of: (String, Data?).self) { group in
            for c in found.prefix(8) { group.addTask { (c.id, await LogoImages.fetch(c)) } }
            for await (id, data) in group { if let data { images[id] = data } }
        }
    }
}

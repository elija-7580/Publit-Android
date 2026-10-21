#if canImport(PhotosUI)
import PhotosUI
#endif
import SwiftUI
#if canImport(UniformTypeIdentifiers)
import UniformTypeIdentifiers
#endif
#if canImport(VisionKit)
import VisionKit
#endif

/// Add a ticket: choose a PDF, photo or screenshot (or scan paper), pick the page,
/// let Publit read it on the device, then check the result.
struct PassImportView: View {
    /// A ticket added from a trip belongs to that trip.
    var tripId: String? = nil
    var sharedURL: URL? = nil
    @Environment(PassStore.self) var store
    @Environment(\.dismiss) var dismiss
    @State var stage: Stage = .choose
    #if !os(Android)
    @State var photo: PhotosPickerItem?
    #endif
    @State var choosingFile = false
    @State var choosingPhoto = false
    @State var scanning = false
    @State var readingTask: Task<Void, Never>?

    enum Stage {
        case choose
        case reading
        case pages(Data, count: Int)
        case review(PassEditorView.Mode)
        case failed(String)
    }

    var body: some View {
        NavigationStack {
            Group {
                switch stage {
                case .choose: chooser
                case .reading:
                    LogoLoader(label: L10n.t("Reading ticket…"))
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                case .pages(let data, let count): PagePicker(data: data, count: count) { page in
                    readingTask?.cancel()
                    readingTask = Task { await read(data, pdf: true, page: page) }
                }
                case .review(let mode): PassEditorView(mode: mode) { dismiss() }
                case .failed(let message):
                    ContentUnavailableView {
                        Label(L10n.t("This file could not be read."), systemImage: "exclamationmark.triangle")
                    } description: {
                        Text(verbatim: message)
                    } actions: {
                        Button(L10n.t("Try another file")) { stage = .choose }.buttonStyle(.ink)
                    }
                }
            }
            .toolbar {
                // The check screen has its own Cancel and Save.
                ToolbarItem(placement: .cancellationAction) {
                    if showsCancel { Button("Cancel") { readingTask?.cancel(); dismiss() } }
                }
            }
        }
        .environment(\.liveBarInset, 0)
        .onDisappear {
            if !scanning && !choosingFile && !choosingPhoto { readingTask?.cancel() }
        }
        .task { if let sharedURL { await open(sharedURL) } }
        #if os(Android)
        // Android: the system photo picker, file picker and ML Kit's document scanner.
        .onChange(of: choosingPhoto) { _, on in
            guard on else { return }
            readingTask?.cancel()
            readingTask = Task {
                let url = await DroidPicker.image()
                choosingPhoto = false
                if let url { await open(url) }
            }
        }
        .onChange(of: choosingFile) { _, on in
            guard on else { return }
            readingTask?.cancel()
            readingTask = Task {
                let url = await DroidPicker.file(["application/pdf", "image/*"])
                choosingFile = false
                if let url { await open(url) }
            }
        }
        .onChange(of: scanning) { _, on in
            guard on else { return }
            readingTask?.cancel()
            readingTask = Task {
                let url = await DroidPicker.scan()
                scanning = false
                if let url { await open(url) }
            }
        }
        #else
        .photosPicker(isPresented: $choosingPhoto, selection: $photo, matching: .images)
        .fileImporter(isPresented: $choosingFile, allowedContentTypes: [.pdf, .image]) { result in
            guard case .success(let url) = result else { return }
            readingTask?.cancel()
            readingTask = Task { await open(url) }
        }
        .onChange(of: photo) { _, item in
            guard let item else { return }
            readingTask?.cancel()
            readingTask = Task {
                stage = .reading
                if let data = try? await item.loadTransferable(type: Data.self) {
                    await read(data, pdf: false, page: 0)
                } else {
                    stage = .failed(L10n.t("The photo could not be loaded."))
                }
                photo = nil
            }
        }
        .fullScreenCover(isPresented: $scanning) {
            DocumentScanner { image in
                scanning = false
                guard let image, let data = image.jpegData(compressionQuality: 0.9) else { return }
                readingTask?.cancel()
                readingTask = Task { await read(data, pdf: false, page: 0) }
            }
            .ignoresSafeArea()
        }
        #endif
    }

    private var showsCancel: Bool {
        if case .review = stage { return false }
        return true
    }

    private var chooser: some View {
        List {
            Section {
                Button { choosingPhoto = true } label: {
                    Label(L10n.t("Photo or screenshot"), systemImage: "photo.on.rectangle")
                }
                .accessibilityIdentifier("importPhoto")
                Button { choosingFile = true } label: {
                    Label(L10n.t("PDF or image file"), systemImage: "doc")
                }
                .accessibilityIdentifier("importFile")
                if canScan {
                    Button { scanning = true } label: {
                        Label(L10n.t("Scan a paper ticket"), systemImage: "doc.viewfinder")
                    }
                }
            } footer: {
                Text("Publit reads the ticket on your iPhone. Nothing is uploaded. Codes that change every few seconds only work in the operator's app.")
            }
        }
        .inkList()
        .navigationTitle(L10n.t("Add ticket"))
        .screenTrail("Pass import")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var canScan: Bool {
        #if os(Android)
        DroidPicker.canScan
        #else
        VNDocumentCameraViewController.isSupported
        #endif
    }

    private func open(_ url: URL) async {
        stage = .reading
        #if os(Android)
        let ext = url.pathExtension.lowercased()
        let isImage = ["jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "bmp"].contains(ext)
        #else
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        #endif
        do {
            #if os(Android)
            guard ext == "pdf" || isImage else { throw TicketRecognizer.Failure.unreadable }
            let isPDF = ext == "pdf"
            #else
            let type = UTType(filenameExtension: url.pathExtension)
            guard type?.conforms(to: .pdf) == true || type?.conforms(to: .image) == true else {
                throw TicketRecognizer.Failure.unreadable
            }
            let isPDF = type?.conforms(to: .pdf) == true
            #endif
            let data = try await Task.detached(priority: .userInitiated) {
                let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                guard size <= PassStore.maxDocumentBytes else { throw PassStore.Failure.documentTooLarge }
                let handle = try FileHandle(forReadingFrom: url)
                defer { try? handle.close() }
                let bytes = try handle.read(upToCount: PassStore.maxDocumentBytes + 1) ?? Data()
                guard bytes.count <= PassStore.maxDocumentBytes else { throw PassStore.Failure.documentTooLarge }
                guard !bytes.isEmpty else { throw TicketRecognizer.Failure.unreadable }
                return bytes
            }.value
            try Task.checkCancellation()
            guard isPDF else { await read(data, pdf: false, page: 0); return }
            let count = await Task.detached(priority: .userInitiated) { TicketRecognizer.pageCount(pdf: data) }.value
            if count > 1 {
                if sharedURL != nil, let candidate = try await TicketRecognizer.uniqueTicket(pdf: data, count: count) {
                    await accept(candidate.scan, data: data, pdf: true, page: candidate.page)
                } else { stage = .pages(data, count: count) }
            } else { await read(data, pdf: true, page: 0) }
        } catch is CancellationError {
            // Dismissing the import must never turn into a late save.
        } catch { stage = .failed(error.localizedDescription) }
    }

    private func read(_ data: Data, pdf: Bool, page: Int) async {
        stage = .reading
        do {
            let scan = pdf ? try await TicketRecognizer.scan(pdf: data, page: page) : try await TicketRecognizer.scan(imageData: data)
            await accept(scan, data: data, pdf: pdf, page: page)
        } catch is CancellationError {
        } catch {
            stage = .failed(error.localizedDescription)
        }
    }

    private func accept(_ scan: TicketScan, data: Data, pdf: Bool, page: Int) async {
        do {
            try Task.checkCancellation()
            let fingerprint = await Task.detached(priority: .userInitiated) {
                TicketImportPolicy.fingerprint(data, page: page)
            }.value
            try Task.checkCancellation()
            // A reimport reviews the existing pass, not a second draft whose changes would be
            // silently discarded by deduplication on Save. Cancel never changes the stored pass.
            if let existing = store.passes.first(where: { $0.document?.fingerprint == fingerprint }) {
                stage = .review(.edit(existing))
                return
            }
            let draft = TicketTextParser.parse(lines: scan.lines, barcodes: scan.barcodes, strictValidity: sharedURL != nil)
            var pass = TicketPass(draft: draft, barcode: scan.barcodes.first)
            // Added from a trip: it belongs to that trip whatever type was recognised,
            // so changing the type on the check screen does not lose the link.
            if let tripId { pass.tripId = tripId }
            if PassStyle.suggested(forOperator: draft.operatorName) == nil, let color = scan.suggestedColor {
                pass.style = PassStyle(background: color)
            }
            stage = .review(.new(pass, document: data, kind: pdf ? .pdf : .image, page: page,
                                 codes: scan.barcodes, colorFromTicket: scan.suggestedColor))
        } catch is CancellationError {
        } catch { stage = .failed(error.localizedDescription) }
    }
}

/// Multi-page PDFs (e.g. outward and return ticket): choose the page with the ticket.
struct PagePicker: View {
    let data: Data
    let count: Int
    let choose: (Int) -> Void

    var body: some View {
        ScrollView {
            Text("Which page is the ticket?").font(.headline).padding(.top)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 140), spacing: 16)], spacing: 16) {
                ForEach(0..<count, id: \.self) { page in
                    PDFPageChoice(data: data, page: page, choose: choose)
                }
            }
            .padding()
        }
        .navigationTitle(L10n.t("Choose page"))
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct PDFPageChoice: View {
    let data: Data
    let page: Int
    let choose: (Int) -> Void
    @State var thumbnail: UIImage?

    var body: some View {
        Button { choose(page) } label: {
            VStack {
                Group {
                    if let thumbnail { Image(uiImage: thumbnail).resizable().scaledToFit() }
                    else { LogoLoader(size: 40).frame(height: 180) }
                }
                .frame(maxWidth: .infinity)
                .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 10))
                Text(verbatim: L10n.f("Page %lld", page + 1)).font(.subheadline)
            }
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("ticketPage-\(page)")
        .task {
            let image = await Task.detached(priority: .userInitiated) {
                TicketRecognizer.renderPage(pdf: data, page: page, maxPixels: 420)
            }.value
            guard !Task.isCancelled else { return }
            thumbnail = image
        }
        .onDisappear { thumbnail = nil }
    }
}

#if !os(Android)
/// VisionKit's document camera: edge detection and perspective correction for paper tickets.
private struct DocumentScanner: UIViewControllerRepresentable {
    let done: (UIImage?) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(done: done) }

    func makeUIViewController(context: Context) -> VNDocumentCameraViewController {
        let controller = VNDocumentCameraViewController()
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ controller: VNDocumentCameraViewController, context: Context) {}

    final class Coordinator: NSObject, VNDocumentCameraViewControllerDelegate {
        let done: (UIImage?) -> Void
        init(done: @escaping (UIImage?) -> Void) { self.done = done }

        func documentCameraViewController(_ controller: VNDocumentCameraViewController, didFinishWith scan: VNDocumentCameraScan) {
            done(scan.pageCount > 0 ? scan.imageOfPage(at: 0) : nil)
        }

        func documentCameraViewControllerDidCancel(_ controller: VNDocumentCameraViewController) { done(nil) }

        func documentCameraViewController(_ controller: VNDocumentCameraViewController, didFailWithError error: Error) { done(nil) }
    }
}
#endif

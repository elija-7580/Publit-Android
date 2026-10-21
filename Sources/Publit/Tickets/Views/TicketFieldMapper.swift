import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// Review the actual document before saving. Pinch to reach small print, tap a box, correct its
/// text if needed, then choose the property. The original document and code remain untouched.
struct TicketFieldMapper: View {
    let source: BarcodeSource
    @Binding var pass: TicketPass
    @Environment(\.dismiss) var dismiss
    @State var image: UIImage?
    @State var pieces: [TicketTextPiece] = []
    @State var assigned: [TicketField: Int] = [:]
    @State var selection: TicketTextPiece?
    @State var text = ""
    @State var dateError = false
    @State var loading = true
    @State var error: String?

    var body: some View {
        VStack(spacing: 0) {
            Text("Tap a text on the ticket and choose what it is.")
                .font(.subheadline).foregroundStyle(.secondary)
                .padding().frame(maxWidth: .infinity, alignment: .leading)
            if loading {
                LogoLoader(label: L10n.t("Reading ticket…"))
                    .frame(maxHeight: .infinity)
            } else if let image {
                if pieces.isEmpty {
                    Text(verbatim: error ?? L10n.t("No text found on this page.")).font(.footnote).foregroundStyle(.secondary).padding(.horizontal)
                }
                TicketMappingCanvas(image: image, pieces: pieces, assigned: assigned) { piece in
                    text = piece.text
                    dateError = false
                    selection = piece
                }
                .accessibilityIdentifier("ticketFieldCanvas")
            } else {
                ContentUnavailableView {
                    Label(L10n.t("This file could not be read."), systemImage: "doc")
                } description: { Text(verbatim: error ?? "") }
            }
        }
        .background(Color.ground)
        .navigationTitle(L10n.t("Assign fields on the ticket"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
        }
        .task { await load() }
        .sheet(item: $selection) { piece in
            NavigationStack {
                Form {
                    Section(L10n.t("Text")) {
                        TextField(L10n.t("Text"), text: $text, axis: .vertical)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                            .accessibilityIdentifier("ticketMappingText")
                    }
                    Section(L10n.t("Assign fields on the ticket")) {
                        ForEach(TicketField.allCases) { field in
                            Button(field.title) {
                                if field.apply(text, to: &pass) {
                                    assigned[field] = piece.id
                                    selection = nil
                                } else { dateError = true }
                            }
                            .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                            .accessibilityIdentifier("ticketField-\(field.rawValue)")
                        }
                    }
                }
                .inkList()
                .navigationTitle(L10n.t("Ticket"))
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") { selection = nil } }
                }
                .alert(L10n.t("No date found in this text."), isPresented: $dateError) {
                    Button("OK", role: .cancel) {}
                }
            }
            .environment(\.liveBarInset, 0)
        }
    }

    @MainActor private func load() async {
        guard loading else { return }
        do {
            let (cg, embedded) = try await Task.detached(priority: .userInitiated) { () throws -> (CGImage, [TicketTextPiece]) in
                let data: Data
                let kind: TicketPass.Document.Kind
                let page: Int
                switch source {
                case .data(let bytes, let type, let number): (data, kind, page) = (bytes, type, number)
                case .file(let url, let type, let number): (data, kind, page) = (try Data(contentsOf: url), type, number)
                }
                let result = kind == .pdf
                    ? TicketRecognizer.renderPage(pdf: data, page: page)?.cgImage
                    : TicketRecognizer.loadImage(data)
                guard let result else { throw TicketRecognizer.Failure.unreadable }
                return (result, kind == .pdf ? TicketRecognizer.pieces(pdf: data, page: page) : [])
            }.value
            try Task.checkCancellation()
            image = UIImage(cgImage: cg)
            // Preserve the document even when Vision is temporarily unavailable.
            do {
                pieces = embedded.isEmpty ? try await TicketRecognizer.pieces(image: cg) : embedded
            }
            catch is CancellationError { throw CancellationError() }
            catch { self.error = error.localizedDescription }
            try Task.checkCancellation()
            loading = false
        } catch is CancellationError {
        } catch {
            self.error = error.localizedDescription
            loading = false
        }
    }
}

private struct TicketMappingCanvas: UIViewRepresentable {
    let image: UIImage
    let pieces: [TicketTextPiece]
    let assigned: [TicketField: Int]
    let onTap: (TicketTextPiece) -> Void

    func makeUIView(context: Context) -> TicketMappingScrollView { TicketMappingScrollView() }

    func updateUIView(_ view: TicketMappingScrollView, context: Context) {
        view.configure(image: image, pieces: pieces, assigned: assigned, onTap: onTap)
    }
}

/// A single zoom container for the pixels and hit targets: the boxes never drift during zooming.
private final class TicketMappingScrollView: UIScrollView, UIScrollViewDelegate {
    private let pageView = UIView()
    private let imageView = UIImageView()
    private var pieces: [TicketTextPiece] = []
    private var buttons: [UIButton] = []
    private var viewport: CGSize = .zero
    private var onTap: ((TicketTextPiece) -> Void)?

    override init(frame: CGRect) {
        super.init(frame: frame)
        delegate = self
        minimumZoomScale = 1
        maximumZoomScale = 6
        backgroundColor = UIColor(Color.ground)
        alwaysBounceVertical = true
        addSubview(pageView)
        pageView.addSubview(imageView)
        imageView.contentMode = .scaleToFill
        imageView.isAccessibilityElement = false
        let doubleTap = UITapGestureRecognizer(target: self, action: #selector(toggleZoom(_:)))
        doubleTap.numberOfTapsRequired = 2
        addGestureRecognizer(doubleTap)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    func configure(image: UIImage, pieces: [TicketTextPiece], assigned: [TicketField: Int],
                   onTap: @escaping (TicketTextPiece) -> Void) {
        self.onTap = onTap
        if imageView.image !== image { imageView.image = image; viewport = .zero }
        self.pieces = pieces
        if buttons.count != pieces.count {
            buttons.forEach { $0.removeFromSuperview() }
            buttons = pieces.map { piece in
                let button = UIButton(type: .custom)
                button.tag = piece.id
                button.layer.cornerRadius = 3
                button.layer.borderWidth = 1
                button.addTarget(self, action: #selector(tapPiece(_:)), for: .touchUpInside)
                button.accessibilityIdentifier = "ticketText-\(piece.id)"
                pageView.addSubview(button)
                return button
            }
        }
        for (piece, button) in zip(pieces, buttons) {
            let fields = TicketField.allCases.filter { assigned[$0] == piece.id }
            let color = UIColor(Color.inkAction)
            button.backgroundColor = color.withAlphaComponent(fields.isEmpty ? 0.04 : 0.2)
            button.layer.borderColor = color.withAlphaComponent(fields.isEmpty ? 0.35 : 0.9).cgColor
            button.accessibilityLabel = ([piece.text] + fields.map(\.title)).joined(separator: ", ")
            button.accessibilityTraits = fields.isEmpty ? .button : [.button, .selected]
        }
        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        guard let image = imageView.image, bounds.width > 0 else { return }
        if viewport != bounds.size {
            viewport = bounds.size
            setZoomScale(1, animated: false)
            let width = bounds.width
            let height = width * image.size.height / max(image.size.width, 1)
            pageView.frame = CGRect(x: 0, y: 0, width: width, height: height)
            imageView.frame = pageView.bounds
            contentSize = pageView.bounds.size
        }
        for (piece, button) in zip(pieces, buttons) {
            let box = piece.rect
            let rect = CGRect(x: box.minX * pageView.bounds.width, y: box.minY * pageView.bounds.height,
                              width: box.width * pageView.bounds.width, height: box.height * pageView.bounds.height)
            button.frame = rect.insetBy(dx: -2, dy: -3)
        }
    }

    func viewForZooming(in scrollView: UIScrollView) -> UIView? { pageView }

    @objc private func tapPiece(_ sender: UIButton) {
        if let piece = pieces.first(where: { $0.id == sender.tag }) { onTap?(piece) }
    }

    @objc private func toggleZoom(_ gesture: UITapGestureRecognizer) {
        if zoomScale > 1 { setZoomScale(1, animated: true) }
        else {
            let point = gesture.location(in: pageView)
            let size = CGSize(width: bounds.width / 3, height: bounds.height / 3)
            zoom(to: CGRect(x: point.x - size.width / 2, y: point.y - size.height / 2,
                            width: size.width, height: size.height), animated: true)
        }
    }
}

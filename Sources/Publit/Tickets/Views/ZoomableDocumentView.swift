#if canImport(PDFKit)
import PDFKit
#endif
import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

#if os(Android)
/// Android: the original ticket as images (PDF pages drawn by PdfRenderer), zoom with the buttons.
struct ZoomableDocumentView: View {
    let url: URL
    let kind: TicketPass.Document.Kind
    var page = 0
    @State var pages: [UIImage] = []
    @State var loading = true
    @State var zoom: Double = 1

    var body: some View {
        GeometryReader { geo in
            ScrollView(.vertical) {
                ScrollView(.horizontal) {
                    VStack(spacing: 12) {
                        ForEach(Array(pages.enumerated()), id: \.offset) { _, image in
                            let width = geo.size.width * zoom
                            let aspect = image.size.width > 0 ? image.size.height / image.size.width : 1.4
                            Image(uiImage: image).resizable().frame(width: width, height: width * aspect)
                        }
                    }
                }
            }
            .overlay {
                if loading { LogoLoader(size: 40) }
            }
            .overlay(alignment: .bottomTrailing) {
                HStack(spacing: 8) {
                    Button { zoom = max(1, zoom / 1.5) } label: { Image(systemName: "minus.magnifyingglass") }
                        .disabled(zoom <= 1)
                    Button { zoom = min(6, zoom * 1.5) } label: { Image(systemName: "plus.magnifyingglass") }
                        .disabled(zoom >= 6)
                }
                .buttonStyle(.soft)
                .padding()
            }
        }
        .background(Color.ground)
        .accessibilityIdentifier("passOriginal")
        .accessibilityLabel(L10n.t("Original ticket"))
        .task {
            let (url, kind, first) = (url, kind, page)
            let images = await Task.detached(priority: .userInitiated) { () -> [UIImage] in
                guard let data = try? Data(contentsOf: url) else { return [] }
                guard kind == .pdf else {
                    return TicketRecognizer.image(TicketPage(data: data, pdf: false, page: 0)).map { [$0] } ?? []
                }
                // The ticket page first, then the others in order (outward and return tickets).
                let count = max(1, min(TicketRecognizer.pageCount(pdf: data), 12))
                let order = [first] + (0..<count).filter { $0 != first }
                return order.compactMap { TicketRecognizer.renderPage(pdf: data, page: $0, maxPixels: 2000) }
            }.value
            pages = images
            loading = false
        }
    }
}
#else
/// The original ticket, zoomable: PDFs in PDFKit, images in a scroll view (pinch or double-tap).
struct ZoomableDocumentView: View {
    let url: URL
    let kind: TicketPass.Document.Kind
    var page = 0

    var body: some View {
        switch kind {
        case .pdf: PDFDocumentView(url: url, page: page).ignoresSafeArea(edges: .bottom)
        case .image: ZoomableImageView(url: url)
        }
    }
}

private struct PDFDocumentView: UIViewRepresentable {
    let url: URL
    let page: Int

    func makeUIView(context: Context) -> PDFView {
        let view = PDFView()
        view.autoScales = true
        view.displayMode = .singlePageContinuous
        view.displayDirection = .vertical
        view.backgroundColor = UIColor(Color.ground)
        view.document = PDFDocument(url: url)
        if let p = view.document?.page(at: page) { view.go(to: p) }
        view.accessibilityIdentifier = "passOriginal"
        return view
    }

    func updateUIView(_ view: PDFView, context: Context) {}
}

private struct ZoomableImageView: UIViewRepresentable {
    let url: URL

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> ZoomingScrollView {
        let view = ZoomingScrollView()
        view.delegate = context.coordinator
        view.accessibilityIdentifier = "passOriginal"
        Task { @MainActor in
            view.imageView.image = await TicketImageLoader.load(url, original: true)
            view.imageView.accessibilityLabel = L10n.t("Original ticket")
            view.setNeedsLayout()
        }
        return view
    }

    func updateUIView(_ view: ZoomingScrollView, context: Context) {}

    final class Coordinator: NSObject, UIScrollViewDelegate {
        func viewForZooming(in scrollView: UIScrollView) -> UIView? { (scrollView as? ZoomingScrollView)?.imageView }
    }
}

final class ZoomingScrollView: UIScrollView {
    let imageView = UIImageView()

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = UIColor(Color.ground)
        minimumZoomScale = 1
        maximumZoomScale = 6
        showsVerticalScrollIndicator = false
        showsHorizontalScrollIndicator = false
        imageView.contentMode = .scaleAspectFit
        imageView.isAccessibilityElement = true
        addSubview(imageView)
        let doubleTap = UITapGestureRecognizer(target: self, action: #selector(toggleZoom(_:)))
        doubleTap.numberOfTapsRequired = 2
        addGestureRecognizer(doubleTap)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func layoutSubviews() {
        super.layoutSubviews()
        if zoomScale == 1 {
            imageView.frame = bounds
            contentSize = bounds.size
        }
    }

    @objc private func toggleZoom(_ gesture: UITapGestureRecognizer) {
        if zoomScale > 1 {
            setZoomScale(1, animated: true)
        } else {
            let point = gesture.location(in: imageView)
            let size = CGSize(width: bounds.width / 3, height: bounds.height / 3)
            zoom(to: CGRect(x: point.x - size.width / 2, y: point.y - size.height / 2, width: size.width, height: size.height), animated: true)
        }
    }
}

/// Full brightness while a code is on screen, as scanners at gates need; restored when it leaves
/// the screen or the app goes to the background.
struct BrightnessBoost: ViewModifier {
    var active = true
    @Environment(\.scenePhase) var phase
    @State var saved: CGFloat?

    func body(content: Content) -> some View {
        content
            .onAppear(perform: boost)
            .onDisappear(perform: restore)
            .onChange(of: phase) { _, phase in phase == .active ? boost() : restore() }
    }

    private var screen: UIScreen? {
        UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }?.screen
    }

    private func boost() {
        guard active, saved == nil, let screen else { return }
        saved = screen.brightness
        screen.brightness = 1
    }

    private func restore() {
        guard let value = saved, let screen else { saved = nil; return }
        screen.brightness = value
        saved = nil
    }
}

extension View {
    func brightnessBoost(_ active: Bool = true) -> some View { modifier(BrightnessBoost(active: active)) }
}
#endif

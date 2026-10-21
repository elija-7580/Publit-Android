import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// A ticket as a pass: operator and title, the route, the details, a perforation and the code.
/// `compact` is the list version without the code.
struct PassCardView: View {
    let pass: TicketPass
    var compact = false
    var now: Date = .now
    /// Operator logo, shown on a white plate instead of the symbol.
    var logo: UIImage? = nil
    /// The original, for codes shown cut out of it.
    var codeSource: BarcodeSource? = nil
    var onCodeTap: (() -> Void)? = nil

    #if os(Android)

    var typeSize: DynamicTypeSize { AndroidEnvironment.dynamicTypeSize }

    #else

    @Environment(\.dynamicTypeSize) var typeSize

    #endif
    private var background: Color { Color(hex: pass.style.background) ?? .black }
    private var foreground: Color { Color(hex: pass.style.foreground) ?? .white }
    private var label: Color { Color(hex: pass.style.label) ?? .gray }
    private var expired: Bool { if case .expired = pass.validity(at: now) { return true }; return false }

    var body: some View {
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 14) {
                header
                route
                if !compact { details }
            }
            .padding(18)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(background, in: TicketSectionShape(notchBottom: !compact))

            if !compact {
                codeArea
                    .padding(18)
                    .frame(maxWidth: .infinity)
                    .background(background, in: TicketSectionShape(notchTop: true))
                    .overlay(alignment: .top) {
                        Perforation()
                            .stroke(label.opacity(0.6), style: StrokeStyle(lineWidth: 1.5, dash: [5, 5]))
                            .frame(height: 1.5)
                            .padding(.horizontal, 20)
                            .accessibilityHidden(true)
                    }
            }
        }
        .foregroundStyle(foreground)
        .compositingGroup()
        .saturation(compact && expired ? 0 : 1)
        .opacity(compact && expired ? 0.7 : 1)
    }

    private var header: some View {
        let layout = typeSize.isAccessibilitySize ? AnyLayout(VStackLayout(alignment: .leading, spacing: 10)) : AnyLayout(HStackLayout(alignment: .center, spacing: 12))
        return layout {
            HStack(spacing: 12) {
                if let logo {
                    Image(uiImage: logo)
                        .resizable().interpolation(.high).scaledToFit()
                        .frame(maxWidth: 84, maxHeight: 30)
                        .padding(.horizontal, 6).padding(.vertical, 4)
                        .background(.white, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                        .accessibilityHidden(true)
                } else {
                    Image(systemName: pass.symbol.systemImage)
                        .font(.headline)
                        .frame(width: 38, height: 38)
                        .background(foreground.opacity(0.14), in: Circle())
                        .accessibilityHidden(true)
                }
                VStack(alignment: .leading, spacing: 2) {
                    if let op = pass.operatorName {
                        Text(verbatim: op).font(.caption.weight(.semibold)).textCase(.uppercase).foregroundStyle(label)
                    }
                    Text(verbatim: pass.displayTitle).font(.headline).fixedSize(horizontal: false, vertical: true)
                }
            }
            if !typeSize.isAccessibilitySize { Spacer(minLength: 4) }
            ValidityBadge(validity: pass.validity(at: now), foreground: foreground)
        }
    }

    @ViewBuilder
    private var route: some View {
        if pass.hasRoute, let origin = pass.origin, let destination = pass.destination {
            let layout = typeSize.isAccessibilitySize ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8)) : AnyLayout(HStackLayout(alignment: .top, spacing: 10))
            layout {
                field(L10n.t("From"), origin, big: true)
                Image(systemName: typeSize.isAccessibilitySize ? "arrow.down" : "arrow.right")
                    .font(.title3.weight(.semibold)).foregroundStyle(label)
                    .padding(.top, typeSize.isAccessibilitySize ? 0 : 20)
                    .accessibilityHidden(true)
                field(L10n.t("To"), destination, big: true)
            }
        } else if !compact {
            field(L10n.t("Type"), pass.category.title, big: true)
        }
    }

    private var detailFields: [(String, String)] {
        var out: [(String, String)] = []
        if pass.hasRoute, let via = pass.via, !via.isEmpty { out.append((L10n.t("Via"), via)) }
        if let from = pass.validFrom { out.append((L10n.t("Valid from"), PassFormat.date(from, end: false))) }
        if let until = pass.validUntil { out.append((L10n.t("Valid until"), PassFormat.date(until, end: true))) }
        if let passenger = pass.passenger { out.append((L10n.t("Passenger"), passenger)) }
        if let c = pass.travelClassText { out.append((L10n.t("Class"), c)) }
        if let price = pass.price { out.append((L10n.t("Price"), price)) }
        return out
    }

    @ViewBuilder
    private var details: some View {
        let fields = detailFields
        if !fields.isEmpty {
            let columns = typeSize.isAccessibilitySize ? 1 : 2
            Grid(alignment: .leading, horizontalSpacing: 16, verticalSpacing: 12) {
                ForEach(Array(stride(from: 0, to: fields.count, by: columns)), id: \.self) { start in
                    GridRow {
                        ForEach(start..<min(start + columns, fields.count), id: \.self) { i in
                            field(fields[i].0, fields[i].1, big: false)
                        }
                    }
                }
            }
        }
    }

    private func field(_ title: String, _ value: String, big: Bool) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(verbatim: title).font(.caption2.weight(.semibold)).textCase(.uppercase).foregroundStyle(label)
            Text(verbatim: value)
                .font(big ? .title3.weight(.bold) : .subheadline.weight(.semibold))
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    /// A disabled button dims its label, so the preview shows the code without a button.
    @ViewBuilder
    private func codeButton(_ code: TicketPass.Barcode) -> some View {
        let image = BarcodeView(code: code, source: codeSource)
            .frame(maxWidth: code.format.isSquare ? 230 : .infinity)
            .frame(height: code.format.isSquare ? 230 : 120)
            .padding(14)
            .background(.white, in: RoundedRectangle(cornerRadius: 14))
        if let onCodeTap {
            Button { onCodeTap() } label: { image }
                .buttonStyle(.plain)
                .accessibilityHint(L10n.t("Shows the code full screen."))
        } else {
            image
        }
    }

    @ViewBuilder
    private var codeArea: some View {
        VStack(spacing: 10) {
            if let code = pass.barcode {
                codeButton(code)
                    .accessibilityLabel(L10n.f("%@ code", code.format.title))
                    .accessibilityIdentifier("passCode")
                if let text = pass.reference ?? code.altText {
                    Text(verbatim: text).font(.footnote.monospaced()).foregroundStyle(label)
                        .textSelection(.enabled)
                }
            } else {
                Label(pass.document != nil ? L10n.t("No code found. Show the original instead.") : L10n.t("No code"),
                      systemImage: "doc.text.magnifyingglass")
                    .font(.subheadline).foregroundStyle(label)
                    .frame(maxWidth: .infinity, minHeight: 60)
            }
        }
    }
}

struct ValidityBadge: View {
    let validity: TicketPass.Validity
    let foreground: Color

    var body: some View {
        if let content {
            Label(content.text, systemImage: content.icon)
                .font(.caption.weight(.semibold))
                .padding(.horizontal, 10).padding(.vertical, 5)
                .background(foreground.opacity(0.14), in: Capsule())
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var content: (text: String, icon: String)? {
        switch validity {
        case .valid: return (L10n.t("Valid now"), "checkmark.circle.fill")
        case .upcoming(let from): return (L10n.f("From %@", PassFormat.date(from, end: false)), "clock")
        case .expired: return (L10n.t("Expired"), "xmark.circle")
        case .unknown: return nil
        }
    }
}

/// Rounded rectangle with semicircle notches where the perforation is, like a paper ticket.
struct TicketSectionShape: Shape {
    var cornerRadius: CGFloat = 22
    var notchRadius: CGFloat = 11
    var notchTop = false
    var notchBottom = false

    func path(in rect: CGRect) -> Path {
        var path = Path(roundedRect: rect, cornerRadii: RectangleCornerRadii(
            topLeading: notchTop ? 0 : cornerRadius, bottomLeading: notchBottom ? 0 : cornerRadius,
            bottomTrailing: notchBottom ? 0 : cornerRadius, topTrailing: notchTop ? 0 : cornerRadius))
        let d = notchRadius * 2
        if notchTop {
            path = path.subtracting(Path(ellipseIn: CGRect(x: rect.minX - notchRadius, y: rect.minY - notchRadius, width: d, height: d)))
                .subtracting(Path(ellipseIn: CGRect(x: rect.maxX - notchRadius, y: rect.minY - notchRadius, width: d, height: d)))
        }
        if notchBottom {
            path = path.subtracting(Path(ellipseIn: CGRect(x: rect.minX - notchRadius, y: rect.maxY - notchRadius, width: d, height: d)))
                .subtracting(Path(ellipseIn: CGRect(x: rect.maxX - notchRadius, y: rect.maxY - notchRadius, width: d, height: d)))
        }
        return path
    }
}

private struct Perforation: Shape {
    func path(in rect: CGRect) -> Path {
        Path { p in
            p.move(to: CGPoint(x: rect.minX, y: rect.midY))
            p.addLine(to: CGPoint(x: rect.maxX, y: rect.midY))
        }
    }
}

/// Renders the code away from the main thread; it never blocks scrolling. Redrawn codes are
/// shown at their own pixel size (whole pixels per module, no smoothing); codes cut out of the
/// original are scaled to fit.
struct BarcodeView: View {
    let code: TicketPass.Barcode
    var source: BarcodeSource? = nil
    @Environment(\.displayScale) var displayScale
    @State var rendered: BarcodeRenderer.Rendered?
    @State var failed = false

    private struct Key: Hashable {
        let code: TicketPass.Barcode
        let width: Int, height: Int
    }

    var body: some View {
        GeometryReader { geo in
            Group {
                if let rendered {
                    if rendered.exact {
                        Image(uiImage: rendered.image).interpolation(.none)
                    } else {
                        Image(uiImage: rendered.image).resizable().interpolation(.high).scaledToFit()
                    }
                } else if failed {
                    Image(systemName: "exclamationmark.triangle").foregroundStyle(.secondary)
                } else {
                    LogoLoader(size: 28)
                }
            }
            .frame(width: geo.size.width, height: geo.size.height)
            .task(id: Key(code: code, width: Int(geo.size.width), height: Int(geo.size.height))) {
                guard geo.size.width > 1, geo.size.height > 1 else { return }
                let (code, size, scale, source) = (code, geo.size, displayScale, source)
                let result = await Task.detached(priority: .userInitiated) {
                    BarcodeRenderer.render(code, fitting: size, scale: scale, source: source)
                }.value
                guard !Task.isCancelled else { return }
                rendered = result
                failed = result == nil
            }
        }
    }
}

/// Operator logos of saved passes, read once per file.
@MainActor
enum PassLogoCache {
    private static let cache: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.countLimit = 80
        cache.totalCostLimit = 8 * 1024 * 1024
        return cache
    }()

    static func image(_ pass: TicketPass, store: PassStore) -> UIImage? {
        guard let logo = pass.logo, let url = store.logoURL(pass) else { return nil }
        if let hit = cache.object(forKey: logo.file as NSString) { return hit }
        guard let image = UIImage(contentsOfFile: url.path) else { return nil }
        cache.setObject(image, forKey: logo.file as NSString, cost: Int(image.size.width * image.scale * image.size.height * image.scale * 4))
        return image
    }
}

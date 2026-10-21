#if canImport(PassKit)
import PassKit
#endif
import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

#if os(Android)
/// Export is opt-in: show the destination and ask before any ticket data leaves the device.
struct AddToWalletButton: View {
    let pass: TicketPass
    var logo: UIImage? = nil
    @State private var consent = false
    @State private var working = false
    @State private var error: String?
    var body: some View {
        Button { consent = true } label: {
            Label(GoogleWallet.text("wallet_add"), systemImage: "wallet.pass")
        }
        .buttonStyle(InkButtonStyle())
        .disabled(working)
        .alert(GoogleWallet.text("wallet_add"), isPresented: $consent) {
            Button(GoogleWallet.text("wallet_continue")) { Task { await export() } }
            Button(L10n.t("Cancel"), role: .cancel) {}
        } message: {
            Text(GoogleWallet.text("wallet_consent"))
        }
        .alert(GoogleWallet.text("wallet_error"), isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(verbatim: error ?? "") }
    }
    private func export() async {
        guard !working else { return }
        working = true
        defer { working = false }
        do {
            let url = try await GoogleWallet.export(pass)
            Droid.call("openURL", url.absoluteString)
        } catch { self.error = error.localizedDescription }
    }
}
#else
/// Apple's "Add to Apple Wallet" button. Builds and signs the pass only when tapped.
/// Shown only when Wallet export is configured (WalletPassConfig) and the code can be exported.
struct AddToWalletButton: View {
    let pass: TicketPass
    var logo: UIImage? = nil
    @State var walletPass: IdentifiedPass?
    @State var working = false
    @State var error: String?

    struct IdentifiedPass: Identifiable {
        let pass: PKPass
        var id: String { pass.serialNumber }
    }

    var body: some View {
        WalletButton { Task { await export() } }
            .frame(height: 50)
            .frame(maxWidth: .infinity)
            .opacity(working ? 0.5 : 1)
            .disabled(working || !PKAddPassesViewController.canAddPasses())
            .overlay { if working { LogoLoader(size: 24).frame(width: 48) } }
            .accessibilityIdentifier("addToWallet")
            .sheet(item: $walletPass) { item in AddPassesSheet(pass: item.pass).ignoresSafeArea() }
            .alert(L10n.t("The Wallet pass could not be created."), isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(verbatim: error ?? "")
            }
    }

    private func export() async {
        guard let config = WalletPassConfig.current, !working else { return }
        working = true
        defer { working = false }
        do {
            let images = WalletPassImages.render(pass, logo: logo)
            let data = try await WalletPassExport.make(pass: pass, images: images, config: config,
                                                       signer: RemoteWalletPassSigner(endpoint: config.signerURL))
            walletPass = IdentifiedPass(pass: try PKPass(data: data))
        } catch {
            self.error = error.localizedDescription
        }
    }
}

private struct WalletButton: UIViewRepresentable {
    let action: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(action: action) }

    func makeUIView(context: Context) -> PKAddPassButton {
        let button = PKAddPassButton(addPassButtonStyle: .black)
        button.addTarget(context.coordinator, action: #selector(Coordinator.tapped), for: .touchUpInside)
        return button
    }

    func updateUIView(_ button: PKAddPassButton, context: Context) { context.coordinator.action = action }

    final class Coordinator: NSObject {
        var action: () -> Void
        init(action: @escaping () -> Void) { self.action = action }
        @objc func tapped() { action() }
    }
}

private struct AddPassesSheet: UIViewControllerRepresentable {
    let pass: PKPass

    func makeUIViewController(context: Context) -> UIViewController {
        PKAddPassesViewController(pass: pass) ?? UIViewController()
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}

/// Pass images: the icon (lock screen and notifications) and, with an operator logo, the logo
/// on a small white plate (Wallet allows up to 160 × 50 points top left).
enum WalletPassImages {
    static func render(_ pass: TicketPass, logo: UIImage? = nil) -> [String: Data] {
        var out: [String: Data] = [:]
        for (name, side) in [("icon.png", 29.0), ("icon@2x.png", 58.0), ("icon@3x.png", 87.0)] {
            out[name] = icon(pass, logo: logo, side: side)
        }
        if let logo {
            for (name, scale) in [("logo.png", 1.0), ("logo@2x.png", 2.0), ("logo@3x.png", 3.0)] {
                out[name] = plate(logo, scale: scale)
            }
        }
        return out
    }

    private static func fit(_ image: UIImage, in box: CGSize) -> CGSize {
        let w = max(image.size.width, 1), h = max(image.size.height, 1)
        let f = min(box.width / w, box.height / h)
        return CGSize(width: (w * f).rounded(), height: (h * f).rounded())
    }

    private static func plate(_ logo: UIImage, scale: CGFloat) -> Data {
        let inner = fit(logo, in: CGSize(width: 140, height: 34))
        let size = CGSize(width: inner.width + 12, height: inner.height + 8)
        let format = UIGraphicsImageRendererFormat()
        format.scale = scale
        format.opaque = false
        return UIGraphicsImageRenderer(size: size, format: format).pngData { _ in
            UIColor.white.setFill()
            UIBezierPath(roundedRect: CGRect(origin: .zero, size: size), cornerRadius: 6).fill()
            logo.draw(in: CGRect(x: 6, y: 4, width: inner.width, height: inner.height))
        }
    }

    private static func icon(_ pass: TicketPass, logo: UIImage?, side: CGFloat) -> Data {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let size = CGSize(width: side, height: side)
        return UIGraphicsImageRenderer(size: size, format: format).pngData { context in
            if let logo {
                UIColor.white.setFill()
                context.fill(CGRect(origin: .zero, size: size))
                let s = fit(logo, in: CGSize(width: side * 0.86, height: side * 0.86))
                logo.draw(in: CGRect(x: (side - s.width) / 2, y: (side - s.height) / 2, width: s.width, height: s.height))
                return
            }
            let bg = LineColors.RGB(hex: pass.style.background) ?? LineColors.black
            let fg = LineColors.RGB(hex: pass.style.foreground) ?? LineColors.white
            UIColor(red: bg.r, green: bg.g, blue: bg.b, alpha: 1).setFill()
            context.fill(CGRect(origin: .zero, size: size))
            let config = UIImage.SymbolConfiguration(pointSize: side * 0.5, weight: .semibold)
            if let symbol = UIImage(systemName: pass.symbol.systemImage, withConfiguration: config)?
                .withTintColor(UIColor(red: fg.r, green: fg.g, blue: fg.b, alpha: 1), renderingMode: .alwaysOriginal) {
                let s = symbol.size
                symbol.draw(in: CGRect(x: (side - s.width) / 2, y: (side - s.height) / 2, width: s.width, height: s.height))
            }
        }
    }
}
#endif

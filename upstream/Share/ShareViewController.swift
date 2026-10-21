import UIKit
import UniformTypeIdentifiers

/// "Publit" in the share sheet of Google Maps, Apple Maps and other apps: reads the shared link
/// or text (`MapLink`), follows a short link once, and opens Publit with routes to that place.
final class ShareViewController: UIViewController {
    private let status = UILabel()
    private let spinner = UIActivityIndicatorView(style: .medium)
    private var started = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        status.text = L10n.t("Opening in Publit…")
        status.font = .preferredFont(forTextStyle: .body)
        status.adjustsFontForContentSizeCategory = true
        status.numberOfLines = 0
        status.textAlignment = .center
        let close = UIButton(type: .system)
        close.setTitle(L10n.t("Done"), for: .normal)
        close.addTarget(self, action: #selector(done), for: .touchUpInside)
        let stack = UIStackView(arrangedSubviews: [spinner, status, close])
        stack.axis = .vertical
        stack.spacing = 16
        stack.alignment = .center
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            stack.leadingAnchor.constraint(equalTo: view.layoutMarginsGuide.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: view.layoutMarginsGuide.trailingAnchor),
        ])
        spinner.startAnimating()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        guard !started else { return }
        started = true
        sharedText { [weak self] text in
            Task { @MainActor in await self?.handle(text) }
        }
    }

    private func handle(_ text: String?) async {
        guard let link = text.flatMap({ MapLink.parse(text: $0) }) else { return fail() }
        var place = await MapLink.resolve(link)
        if place == nil, let name = link.name { place = MapLink(name: name) }
        guard let url = place?.appURL, openApp(url) else { return fail() }
        done()
    }

    /// Shared URLs and text, joined: Google Maps sends the place name and its link.
    private func sharedText(_ completion: @escaping (String?) -> Void) {
        let items = extensionContext?.inputItems as? [NSExtensionItem] ?? []
        var parts: [String] = items.compactMap { $0.attributedContentText?.string }.filter { !$0.isEmpty }
        let lock = NSLock()
        let group = DispatchGroup()
        for provider in items.flatMap({ $0.attachments ?? [] }) {
            for type in [UTType.url.identifier, UTType.plainText.identifier] where provider.hasItemConformingToTypeIdentifier(type) {
                group.enter()
                provider.loadItem(forTypeIdentifier: type, options: nil) { item, _ in
                    let text = (item as? URL)?.absoluteString ?? (item as? String)
                    if let text { lock.lock(); parts.append(text); lock.unlock() }
                    group.leave()
                }
                break
            }
        }
        group.notify(queue: .main) { completion(parts.isEmpty ? nil : parts.joined(separator: "\n")) }
    }

    /// A share extension has no API to open its app; the app object in the responder chain can,
    /// through the Objective-C runtime (the Swift method is marked unavailable for extensions).
    /// Not allowed by Apple for extensions: fine for own installs, may be rejected in App Review.
    private func openApp(_ url: URL) -> Bool {
        typealias Open = @convention(c) (AnyObject, Selector, NSURL, NSDictionary, (@convention(block) (Bool) -> Void)?) -> Void
        let selector = NSSelectorFromString("openURL:options:completionHandler:")
        var responder: UIResponder? = self
        while let r = responder {
            if r is UIApplication, r.responds(to: selector) {
                let open = unsafeBitCast(r.method(for: selector), to: Open.self)
                open(r, selector, url as NSURL, NSDictionary(), nil)
                return true
            }
            responder = r.next
        }
        return false
    }

    private func fail() {
        spinner.stopAnimating()
        status.text = L10n.t("No place found in what was shared. Share a place or a pin from a map app.")
    }

    @objc private func done() {
        extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
    }
}

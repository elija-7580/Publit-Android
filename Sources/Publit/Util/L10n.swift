import Foundation

/// Localized text for code outside SwiftUI views (models, notifications, App Intents).
/// Keys are the English text; translations live in Sources/Shared/Localizable.xcstrings.
/// Placeholders follow the String Catalog convention: `%@` for text, `%lld` for whole numbers.
enum L10n {
    #if os(Android)
    /// On Android the catalog ships with the Publit module, not the app's main bundle.
    static var bundle: Bundle { .module }
    /// The language the texts are shown in, for the spoken guidance ("de", "en", …).
    static var languageCode: String {
        Bundle.module.preferredLocalizations.first ?? Locale.current.language.languageCode?.identifier ?? "en"
    }
    #else
    static var bundle: Bundle { .main }
    #endif

    static func t(_ key: String) -> String {
        NSLocalizedString(key, bundle: bundle, comment: "")
    }

    static func f(_ key: String, _ args: Any...) -> String {
        let format = NSLocalizedString(key, bundle: bundle, comment: "")
        #if canImport(Darwin)
        // Goes through NSString formatting so plural variations in the catalog apply.
        let cArgs: [CVarArg] = args.map { arg in
            switch arg {
            case let i as Int: return i
            case let d as Double: return d
            case let s as String: return s as NSString
            default: return String(describing: arg) as NSString
            }
        }
        return String(format: format, locale: Locale.current, arguments: cArgs)
        #else
        return substitute(format, args)
        #endif
    }

    /// Placeholder substitution without NSString (Linux): `%@`, `%lld`, `%d` and positional `%1$@`.
    static func substitute(_ format: String, _ args: [Any]) -> String {
        var out = ""
        var next = 0
        var i = format.startIndex
        while i < format.endIndex {
            let c = format[i]
            guard c == "%" else { out.append(c); i = format.index(after: i); continue }
            var j = format.index(after: i)
            guard j < format.endIndex else { out.append(c); break }
            if format[j] == "%" { out.append("%"); i = format.index(after: j); continue }
            var position: Int?
            var digits = ""
            var k = j
            while k < format.endIndex, format[k].isNumber { digits.append(format[k]); k = format.index(after: k) }
            if !digits.isEmpty, k < format.endIndex, format[k] == "$" {
                position = Int(digits).map { $0 - 1 }
                j = format.index(after: k)
            }
            var end = j
            while end < format.endIndex, "lhqz".contains(format[end]) { end = format.index(after: end) }
            guard end < format.endIndex, "@disuf".contains(format[end]) else { out.append(c); i = j; continue }
            let index = position ?? next
            if position == nil { next += 1 }
            if index >= 0, index < args.count { out += String(describing: args[index]) }
            i = format.index(after: end)
        }
        return out
    }
}

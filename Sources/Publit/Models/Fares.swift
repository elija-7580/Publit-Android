import Foundation

struct FareRiderCategory: Codable, Hashable, Sendable {
    let riderCategoryName: String
    let isDefaultFareCategory: Bool
}
struct FareMedia: Codable, Hashable, Sendable {
    let fareMediaName: String?
    let fareMediaType: String
}
struct FareProduct: Codable, Hashable, Sendable {
    let name: String
    let amount: Decimal
    let currency: String
    let riderCategory: FareRiderCategory?
    let media: FareMedia?
    var isUnrestrictedDefault: Bool {
        (riderCategory == nil || riderCategory?.isDefaultFareCategory == true)
        && (media == nil || media?.fareMediaType == "NONE" || media?.fareMediaType == "PAPER_TICKET")
    }
    var formattedAmount: String { FareEstimate.format(amount, currency: currency) }
}
struct FareTransfer: Codable, Sendable {
    let rule: String?
    let transferProducts: [FareProduct]?
    let effectiveFareLegProducts: [[[FareProduct]]]
    private enum CodingKeys: String, CodingKey { case rule, transferProducts, effectiveFareLegProducts }
    init(rule: String?, transferProducts: [FareProduct]?, effectiveFareLegProducts: [[[FareProduct]]]) {
        self.rule = rule; self.transferProducts = transferProducts; self.effectiveFareLegProducts = effectiveFareLegProducts
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        rule = try? c.decode(String.self, forKey: .rule)
        transferProducts = try? c.decode([FareProduct].self, forKey: .transferProducts)
        if let bundles = try? c.decode([[[FareProduct]]].self, forKey: .effectiveFareLegProducts) { effectiveFareLegProducts = bundles }
        else if let options = try? c.decode([[FareProduct]].self, forKey: .effectiveFareLegProducts) { effectiveFareLegProducts = options.map { $0.map { [$0] } } }
        else { effectiveFareLegProducts = [] }
    }

}
struct FareEstimate: Equatable, Sendable {
    let amount: Decimal
    let currency: String
    /// One formatter per currency and locale (fares are shown in every route row).
    private static let lock = NSLock()
    private static var formatters: [String: NumberFormatter] = [:]

    static func format(_ amount: Decimal, currency: String) -> String {
        lock.lock(); defer { lock.unlock() }
        let key = Locale.current.identifier + "|" + currency
        let formatter: NumberFormatter
        if let cached = formatters[key] { formatter = cached } else {
            formatter = NumberFormatter()
            formatter.numberStyle = .currency
            formatter.locale = .current
            formatter.currencyCode = currency
            if formatters.count >= 16 { formatters.removeAll() }
            formatters[key] = formatter
        }
        return formatter.string(from: amount as NSDecimalNumber) ?? "\(amount) \(currency)"
    }
    var formattedAmount: String { Self.format(amount, currency: currency) }
}
extension Itinerary {
    var fareProducts: [FareProduct] {
        let all = (fareTransfers ?? []).flatMap { ($0.transferProducts ?? []) + $0.effectiveFareLegProducts.flatMap { $0.flatMap { $0 } } }
        var seen = Set<FareProduct>()
        return all.filter { seen.insert($0).inserted }
    }

    /// Fail closed: no cheapest-child-ticket guesses, no mixed currencies and no double-counted transfers.
    /// Only a fully covered itinerary with one unambiguous unrestricted default bundle is estimated.
    var fareEstimate: FareEstimate? {
        guard let transfers = fareTransfers, !transfers.isEmpty, !transitLegs.isEmpty,
              transitLegs.allSatisfy({ leg in
                  guard let index = leg.fareTransferIndex else { return false }
                  guard transfers.indices.contains(index), let legIndex = leg.effectiveFareLegIndex else { return false }
                  return transfers[index].effectiveFareLegProducts.indices.contains(legIndex)
              }) else { return nil }
        guard Set(transitLegs.compactMap(\.fareTransferIndex)) == Set(transfers.indices) else { return nil }
        var products: [FareProduct] = []
        func bundle(_ choices: [[FareProduct]]) -> [FareProduct]? {
            let valid = choices.filter { !$0.isEmpty && $0.allSatisfy(\.isUnrestrictedDefault) }
            return valid.count == 1 ? valid[0] : nil
        }
        for transfer in transfers {
            let extra = transfer.transferProducts ?? []
            guard extra.count <= 1, extra.allSatisfy(\.isUnrestrictedDefault) else { return nil }
            switch transfer.rule {
            case "AB": guard !extra.isEmpty else { return nil }; products += extra
            case "A_AB":
                guard let first = transfer.effectiveFareLegProducts.first, let chosen = bundle(first) else { return nil }
                products += chosen + extra
            case "A_AB_B", nil:
                guard !transfer.effectiveFareLegProducts.isEmpty else { return nil }
                if transfer.rule == nil && transfer.effectiveFareLegProducts.count != 1 { return nil }
                for choices in transfer.effectiveFareLegProducts {
                    guard let chosen = bundle(choices) else { return nil }
                    products += chosen
                }
                products += extra
            default: return nil
            }
        }
        guard let currency = products.first?.currency, currency.count == 3,
              products.allSatisfy({ $0.currency == currency && !$0.amount.isNaN }) else { return nil }
        let amount = products.reduce(Decimal.zero) { $0 + $1.amount }
        return amount >= 0 ? FareEstimate(amount: amount, currency: currency) : nil
    }
}

import Foundation

/// "Find nearby" on the map, like Apple Maps: one tap shows every restaurant, pharmacy or
/// charger in view. Kept free of MapKit (the raw values are Apple's category names), so it is tested on Linux.
enum PlaceCategory: String, CaseIterable, Identifiable, Sendable {
    case restaurant, cafe, bakery, supermarket, store, pharmacy, hospital, hotel,
         atm, parking, evCharger, gasStation, restroom, museum, park, nightlife

    var id: String { rawValue }

    /// `MKPointOfInterestCategory.rawValue`.
    var appleCategory: String {
        switch self {
        case .restaurant: return "MKPOICategoryRestaurant"
        case .cafe: return "MKPOICategoryCafe"
        case .bakery: return "MKPOICategoryBakery"
        case .supermarket: return "MKPOICategoryFoodMarket"
        case .store: return "MKPOICategoryStore"
        case .pharmacy: return "MKPOICategoryPharmacy"
        case .hospital: return "MKPOICategoryHospital"
        case .hotel: return "MKPOICategoryHotel"
        case .atm: return "MKPOICategoryATM"
        case .parking: return "MKPOICategoryParking"
        case .evCharger: return "MKPOICategoryEVCharger"
        case .gasStation: return "MKPOICategoryGasStation"
        case .restroom: return "MKPOICategoryRestroom"
        case .museum: return "MKPOICategoryMuseum"
        case .park: return "MKPOICategoryPark"
        case .nightlife: return "MKPOICategoryNightlife"
        }
    }

    /// The short word the map stores for a picked place ("foodmarket"), see `MapPick.category`.
    var word: String { String(appleCategory.dropFirst("MKPOICategory".count)).lowercased() }

    var title: String {
        switch self {
        case .restaurant: return L10n.t("Restaurants")
        case .cafe: return L10n.t("Cafés")
        case .bakery: return L10n.t("Bakeries")
        case .supermarket: return L10n.t("Supermarkets")
        case .store: return L10n.t("Shops")
        case .pharmacy: return L10n.t("Pharmacies")
        case .hospital: return L10n.t("Hospitals")
        case .hotel: return L10n.t("Hotels")
        case .atm: return L10n.t("ATMs")
        case .parking: return L10n.t("Parking")
        case .evCharger: return L10n.t("EV chargers")
        case .gasStation: return L10n.t("Petrol stations")
        case .restroom: return L10n.t("Toilets")
        case .museum: return L10n.t("Museums")
        case .park: return L10n.t("Parks")
        case .nightlife: return L10n.t("Bars")
        }
    }

    /// SF Symbol, as Apple Maps draws these places.
    var symbol: String {
        switch self {
        case .restaurant: return "fork.knife"
        case .cafe: return "cup.and.saucer.fill"
        case .bakery: return "birthday.cake.fill"
        case .supermarket: return "cart.fill"
        case .store: return "bag.fill"
        case .pharmacy: return "cross.case.fill"
        case .hospital: return "cross.fill"
        case .hotel: return "bed.double.fill"
        case .atm: return "banknote.fill"
        case .parking: return "parkingsign"
        case .evCharger: return "bolt.car.fill"
        case .gasStation: return "fuelpump.fill"
        case .restroom: return "toilet.fill"
        case .museum: return "building.columns.fill"
        case .park: return "tree.fill"
        case .nightlife: return "wineglass.fill"
        }
    }

    /// Apple Maps' colour families: food orange, shops yellow, health red, stay purple, cars blue, nature green.
    var colorHex: String {
        switch self {
        case .restaurant, .bakery: return "FF9500"
        case .cafe: return "A2845E"
        case .supermarket, .store: return "E6A700"
        case .pharmacy, .hospital: return "FF3B30"
        case .hotel, .nightlife: return "AF52DE"
        case .atm: return "30B05A"
        case .parking, .gasStation: return "0A84FF"
        case .evCharger, .park: return "34C759"
        case .restroom: return "8E8E93"
        case .museum: return "FF2D55"
        }
    }

    /// The category of a place found or picked on the map (its stored word), if it is one of these.
    static func of(_ word: String?) -> PlaceCategory? {
        guard let w = word?.lowercased(), !w.isEmpty else { return nil }
        return allCases.first { $0.word == w }
    }

    /// Typed words that mean a category ("apotheke", "pharmacy"), so "Apotheke" finds all of them around.
    static func matching(_ text: String) -> PlaceCategory? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard t.count >= 3 else { return nil }
        return allCases.first { c in
            c.title.lowercased() == t || c.word == t || c.title.lowercased().hasPrefix(t) && t.count >= 5
        }
    }
}

/// What the map search looks for, chosen under the search field.
enum MapSearchScope: String, CaseIterable, Identifiable, Sendable {
    case all, stops, places, addresses

    var id: String { rawValue }

    var title: String {
        switch self {
        case .all: return L10n.t("All")
        case .stops: return L10n.t("Stops")
        case .places: return L10n.t("Places")
        case .addresses: return L10n.t("Addresses")
        }
    }

    /// Asks Apple Maps too (it knows shops and sights, not stops).
    var usesApple: Bool { self != .stops }
    /// Asks the transit geocoder (stops, towns, addresses).
    var usesTransit: Bool { self != .places }

    func keeps(_ m: GeocodeMatch) -> Bool {
        switch self {
        case .all: return true
        case .stops: return m.isStop
        case .addresses: return m.type == "ADDRESS" || (m.type == GeocodeMatch.pickedType && m.category == nil && m.street != nil)
        case .places: return !m.isStop && m.type != "ADDRESS" && !(m.type == GeocodeMatch.pickedType && m.category == nil && m.street != nil)
        }
    }

    func filter(_ results: [GeocodeMatch]) -> [GeocodeMatch] { results.filter(keeps) }
}

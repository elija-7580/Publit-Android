import Foundation

// Responses for the live map, rentals and line timetables. Kept small: only the fields the app
// shows are decoded, so the larger map responses stay cheap to parse.

/// A stop at either end of a map segment (`/api/v6/map/trips`).
struct SegmentStop: Decodable, Hashable, Sendable {
    let name: String
    let stopId: String?
    let lat: Double
    let lon: Double

    var coordinate: LatLon { LatLon(lat: lat, lon: lon) }
}

/// One vehicle trip between two consecutive stops, with the shape it travels on.
struct TripSegment: Decodable, Sendable {
    struct TripInfo: Decodable, Hashable, Sendable {
        let tripId: String
        let displayName: String?
        let routeShortName: String?
    }

    let trips: [TripInfo]
    let routeColor: String?
    let mode: String
    let distance: Double
    let from: SegmentStop
    let to: SegmentStop
    let departure: Date
    let arrival: Date
    let scheduledDeparture: Date
    let scheduledArrival: Date
    let realTime: Bool
    /// Google polyline, precision as requested (5 by default).
    let polyline: String
}

/// Vehicle form factors of bike and scooter sharing (GBFS).
enum RentalKind: String, CaseIterable, Sendable {
    case bicycle = "BICYCLE", cargoBicycle = "CARGO_BICYCLE", car = "CAR", moped = "MOPED"
    case scooterStanding = "SCOOTER_STANDING", scooterSeated = "SCOOTER_SEATED", other = "OTHER"

    init(_ raw: String?) { self = raw.flatMap(RentalKind.init(rawValue:)) ?? .other }

    var symbol: String {
        switch self {
        case .bicycle, .cargoBicycle: return "bicycle"
        case .car: return "car.fill"
        case .moped, .scooterSeated: return "scooter"
        case .scooterStanding: return "scooter"
        case .other: return "figure.walk.motion"
        }
    }

    var title: String {
        switch self {
        case .bicycle: return L10n.t("Bike")
        case .cargoBicycle: return L10n.t("Cargo bike")
        case .car: return L10n.t("Car")
        case .moped: return L10n.t("Moped")
        case .scooterStanding, .scooterSeated: return L10n.t("E-scooter")
        case .other: return L10n.t("Rental")
        }
    }
}

/// Bike and scooter sharing in a map rectangle (`/api/v1/rentals`).
struct RentalsResponse: Decodable, Sendable {
    struct Group: Decodable, Sendable {
        let id: String
        let name: String
        let color: String?
    }

    struct Station: Decodable, Identifiable, Sendable {
        let id: String
        let providerGroupId: String
        let name: String
        let lat: Double
        let lon: Double
        let isRenting: Bool?
        let numVehiclesAvailable: Int?
        let formFactors: [String]?
        let rentalUriIOS: String?
        let rentalUriWeb: String?

        var coordinate: LatLon { LatLon(lat: lat, lon: lon) }
    }

    struct Vehicle: Decodable, Identifiable, Sendable {
        let id: String
        let providerGroupId: String
        let lat: Double
        let lon: Double
        let formFactor: String?
        let isReserved: Bool?
        let isDisabled: Bool?
        let rentalUriIOS: String?
        let rentalUriWeb: String?

        var coordinate: LatLon { LatLon(lat: lat, lon: lon) }
        var isAvailable: Bool { isReserved != true && isDisabled != true }
    }

    let providerGroups: [Group]
    let stations: [Station]
    let vehicles: [Vehicle]

    private enum CodingKeys: String, CodingKey { case providerGroups, stations, vehicles }

    init(providerGroups: [Group] = [], stations: [Station] = [], vehicles: [Vehicle] = []) {
        self.providerGroups = providerGroups; self.stations = stations; self.vehicles = vehicles
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        providerGroups = try c.decodeIfPresent([Group].self, forKey: .providerGroups) ?? []
        stations = try c.decodeIfPresent([Station].self, forKey: .stations) ?? []
        vehicles = try c.decodeIfPresent([Vehicle].self, forKey: .vehicles) ?? []
    }

    func groupName(_ id: String) -> String { providerGroups.first { $0.id == id }?.name ?? id }
}

/// Sharing details of a rental leg in a route.
struct LegRental: Codable, Hashable, Sendable {
    let systemName: String?
    let providerGroupId: String?
    let formFactor: String?
    let fromStationName: String?
    let toStationName: String?
    let rentalUriIOS: String?
    let rentalUriWeb: String?

    var kind: RentalKind { RentalKind(formFactor) }
    /// Link that opens the provider's app (or web page) for this vehicle or station.
    var bookingURL: URL? {
        [rentalUriIOS, rentalUriWeb].compactMap { $0 }.first { !$0.isEmpty }.flatMap(URL.init(string:))
    }
}

extension RentalsResponse {
    /// Limit MapKit work and put the nearest, available rentals first. Provider-scoped IDs remain
    /// distinct even when two GBFS operators both call a vehicle "1".
    func mapSnapshot(min lo: LatLon, max hi: LatLon, limit: Int = 250) -> RentalsResponse {
        let center = LatLon(lat: (lo.lat + hi.lat) / 2, lon: (lo.lon + hi.lon) / 2)
        func visible(_ point: LatLon) -> Bool {
            point.isValidCoordinate && point.lat >= lo.lat && point.lat <= hi.lat && point.lon >= lo.lon && point.lon <= hi.lon
        }
        let stations = stations.filter { $0.isRenting != false && visible($0.coordinate) }
            .sorted { $0.coordinate.distance(to: center) < $1.coordinate.distance(to: center) }
        let vehicles = vehicles.filter { $0.isAvailable && visible($0.coordinate) }
            .sorted { $0.coordinate.distance(to: center) < $1.coordinate.distance(to: center) }
        return RentalsResponse(providerGroups: providerGroups, stations: Array(stations.prefix(max(0, limit))),
                               vehicles: Array(vehicles.prefix(max(0, limit))))
    }
}

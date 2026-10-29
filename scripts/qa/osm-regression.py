#!/usr/bin/env python3
"""Test the Android OSM source on Linux with minimal model stubs and a local HTTP server.
Only the os(Android) guard and the three endpoint URLs are changed in the test copy.
No public network calls or third-party dependencies.
"""
import http.server
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import threading
import urllib.parse

ROOT = Path(__file__).resolve().parents[2]
requests = []

class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        data = self.rfile.read(int(self.headers['Content-Length']))
        query = urllib.parse.parse_qs(data.decode()).get('data', [''])[0]
        assert '["amenity"="cafe"]' in query
        assert 'out center 60' in query
        requests.append(self.path)
        n = len(requests)
        if n == 2:
            status, body = 200, b'not JSON'
        elif n == 3:
            status, body = 200, json.dumps({'elements': [
                {'lat': 48.20, 'lon': 16.37, 'tags': {'name': 'Test Cafe'}},
                {'lat': 999, 'lon': 16.37, 'tags': {'name': 'Invalid'}},
            ]}).encode()
        elif n == 7:
            status, body = 200, b'{"elements":[]}'
        else:
            status, body = 503, b'unavailable'
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(body)
    def log_message(self, *_):
        pass

STUBS = r'''
import Foundation
struct Point { let latitude: Double; let longitude: Double }
struct Span { let latitudeDelta: Double; let longitudeDelta: Double }
struct MKCoordinateRegion { let center: Point; let span: Span }
struct LatLon {
    let lat: Double; let lon: Double
    var isValidCoordinate: Bool { lat.isFinite && lon.isFinite && abs(lat) <= 90 && abs(lon) <= 180 }
}
enum PlaceCategory {
    case restaurant, cafe, bakery, supermarket, store, pharmacy, hospital, hotel, atm, parking, evCharger, gasStation, restroom, museum, park, nightlife
    var title: String { "Cafe" }
    var word: String { "cafe" }
}
enum AppInfo { static let userAgent = "Publit-QA-local-test" }
struct GeocodeMatch {
    let name: String
    static func picked(name: String, at: LatLon, street: String?, houseNumber: String?, locality: String?, country: String?, category: String) -> Self { Self(name: name) }
}
'''
HARNESS = r'''
import Foundation
@main struct Regression {
    static func main() async throws {
        let area = MKCoordinateRegion(center: Point(latitude: 48.20, longitude: 16.37), span: Span(latitudeDelta: 0.01, longitudeDelta: 0.01))
        let places = try await OSMPlaces.places(in: area, category: .cafe)
        precondition(places.count == 1 && places[0].name == "Test Cafe")
        print("PASS: HTTP error + malformed JSON fall through; valid coordinates only")
        do {
            _ = try await OSMPlaces.places(in: area, category: .cafe)
            fatalError("Provider outage must throw, not return zero hits")
        } catch is URLError { print("PASS: all-provider outage throws") }
        let empty = try await OSMPlaces.places(in: area, category: .cafe)
        precondition(empty.isEmpty)
        print("PASS: successful empty response stays a valid empty result")
        withUnsafeCurrentTask { $0?.cancel() }
        do {
            _ = try await OSMPlaces.places(in: area, category: .cafe)
            fatalError("Cancelled task must not issue a request")
        } catch is CancellationError { print("PASS: cancellation prevents further network calls") }
    }
}
'''

def main():
    server = http.server.HTTPServer(('127.0.0.1', 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        source = (ROOT / 'Sources/Publit/Android/OSMPlaces.swift').read_text().replace('#if os(Android)', '#if os(Linux)', 1)
        port = server.server_port
        urls = iter([f'http://127.0.0.1:{port}/{i}' for i in range(1, 4)])
        source, count = re.subn(r'https://[^"\s]+', lambda _: next(urls), source)
        assert count == 3
        with tempfile.TemporaryDirectory() as directory:
            d = Path(directory)
            (d / 'OSMPlaces.swift').write_text(source)
            (d / 'Models.swift').write_text(STUBS)
            (d / 'Regression.swift').write_text(HARNESS)
            compiler = Path(os.environ.get('PUBLIT_TOOLS', '/data/tools')) / 'swift/usr/bin/swiftc'
            subprocess.run([str(compiler), str(d/'OSMPlaces.swift'), str(d/'Models.swift'), str(d/'Regression.swift'), '-o', str(d/'test')], check=True)
            subprocess.run([str(d/'test')], check=True)
        assert requests == ['/1', '/2', '/3', '/1', '/2', '/3', '/1'], requests
        print('PASS: exact endpoint order, empty response stops fallback, no request after cancellation')
    finally:
        server.shutdown()

if __name__ == '__main__':
    main()

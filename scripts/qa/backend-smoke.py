#!/usr/bin/env python3
"""Live contract smoke checks; not a replacement for device/end-to-end testing.
Uses fixed public Vienna coordinates, never a user's location. No credentials.
"""
import json
import time
import urllib.parse
import urllib.request
import sys

BASE = "https://api.transitous.org"
CHECKS = [
    ("geocode", "/api/v1/geocode", {"text": "Wien Hauptbahnhof", "numResults": "3"}, list, None),
    ("plan", "/api/v6/plan", {"fromPlace": "48.185,16.376", "toPlace": "48.208,16.372", "arriveBy": "false", "directModes": "WALK", "withFares": "true"}, dict, "itineraries"),
    ("map-stops", "/api/v6/map/stops", {"min": "48.18,16.36", "max": "48.21,16.39", "zoom": "15"}, list, None),
    ("departures", "/api/v6/stoptimes", {"center": "48.185,16.376", "radius": "1000", "n": "5", "withAlerts": "true"}, dict, "stopTimes"),
    ("rentals", "/api/v1/rentals", {"min": "48.18,16.36", "max": "48.21,16.39", "withProviders": "false", "withStations": "true", "withVehicles": "true", "withZones": "false"}, dict, None),
    ("map-style", "https://tiles.openfreemap.org/styles/liberty", {}, dict, "layers"),
    ("vienna-live", "https://www.wienerlinien.at/ogd_realtime/monitor", {"rbl": "4111"}, dict, "data"),
]

def run():
    failures = 0
    for name, path, params, expected, key in CHECKS:
        url = (path if path.startswith("https:") else BASE + path)
        if params:
            url += "?" + urllib.parse.urlencode(params)
        started = time.monotonic()
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "Publit-Android-QA (https://github.com/elija-7580/Publit-Android)"})
            with urllib.request.urlopen(request, timeout=30) as response:
                assert response.status == 200, response.status
                value = json.load(response)
            assert isinstance(value, expected), "unexpected JSON type"
            if key:
                assert key in value, f"missing {key}"
            if isinstance(value, list):
                assert value, "empty result"
            if name == "plan":
                assert value["itineraries"], "no transit routes"
            if name == "departures":
                assert value["stopTimes"], "no departures"
            print(f"PASS {name}: HTTP 200 + JSON contract ({time.monotonic() - started:.2f}s)", flush=True)
        except Exception as error:
            failures += 1
            print(f"FAIL {name}: {error}", flush=True)
    return 1 if failures else 0

if __name__ == "__main__":
    sys.exit(run())

#!/usr/bin/env python3
"""Record the demo data (fixtures/<name>/) once from the real Transitous API.

  python3 record_fixtures.py --out fixtures/munich

Change place, destination and time below (or by arguments). Needs internet. The answers are
trimmed (debug fields dropped, fewer polyline points) and only stops/vehicles near the place kept.
Check the result for anything personal before committing (use public places, never a home address).
"""
import argparse, json, math, os, urllib.parse, urllib.request
from datetime import datetime, timedelta, timezone

UA = {"User-Agent": "Publit-screenshot-fixtures/1.0 (+https://github.com/elija-7580/Publit-Android)", "Accept": "application/json"}
API = "https://api.transitous.org"


def get(path, q):
    u = API + path + "?" + urllib.parse.urlencode(q)
    return json.load(urllib.request.urlopen(urllib.request.Request(u, headers=UA), timeout=90))


def dec(s, prec):
    f = 10 ** prec; i = lat = lon = 0; pts = []
    while i < len(s):
        for k in (0, 1):
            r = sh = 0
            while True:
                b = ord(s[i]) - 63; i += 1; r |= (b & 31) << sh; sh += 5
                if b < 32: break
            d = ~(r >> 1) if r & 1 else r >> 1
            if k == 0: lat += d
            else: lon += d
        pts.append((lat / f, lon / f))
    return pts


def enc(pts, prec):
    f = 10 ** prec; out = []; pl = pn = 0
    for la, lo in pts:
        a, b = round(la * f), round(lo * f)
        for d in (a - pl, b - pn):
            v = ~(d << 1) if d < 0 else d << 1
            while v >= 32: out.append(chr((32 | (v & 31)) + 63)); v >>= 5
            out.append(chr(v + 63))
        pl, pn = a, b
    return "".join(out)


def simplify(pts, tol):
    """Douglas-Peucker, tolerance in metres."""
    if len(pts) < 3: return pts
    def dist(p, a, b):
        k = math.cos(math.radians(a[0]))
        ax, ay, bx, by, px, py = a[1] * k, a[0], b[1] * k, b[0], p[1] * k, p[0]
        dx, dy = bx - ax, by - ay
        if dx == dy == 0: return math.hypot(px - ax, py - ay) * 111000
        t = max(0, min(1, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
        return math.hypot(px - ax - t * dx, py - ay - t * dy) * 111000
    keep = [False] * len(pts); keep[0] = keep[-1] = True; st = [(0, len(pts) - 1)]
    while st:
        a, b = st.pop(); best, bi = -1, 0
        for i in range(a + 1, b):
            d = dist(pts[i], pts[a], pts[b])
            if d > best: best, bi = d, i
        if best > tol: keep[bi] = True; st += [(a, bi), (bi, b)]
    return [p for p, k in zip(pts, keep) if k]


def geom(g, tol=2.5):
    if isinstance(g, dict) and g.get("points"):
        p = simplify(dec(g["points"], g.get("precision", 6)), tol)
        g["points"] = enc(p, g.get("precision", 6)); g["length"] = len(p)


DROP = {"debugOutput", "requestParameters", "arrivalTimestampReason", "departureTimestampReason", "vertexType"}


def clean(x):
    if isinstance(x, dict):
        for k in list(x):
            if k in DROP: del x[k]
            else: clean(x[k])
        if "legGeometry" in x: geom(x["legGeometry"])
    elif isinstance(x, list):
        for v in x: clean(v)
    return x


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="fixtures/munich")
    ap.add_argument("--time", default="2026-11-03T08:41:00Z", help="reference time (UTC), shown as the clock time")
    ap.add_argument("--here", default="48.14990,11.58030", help="where the phone is (public place)")
    ap.add_argument("--here-name", default="Universität")
    ap.add_argument("--to", default="48.35380,11.78610")
    ap.add_argument("--to-name", default="Flughafen München")
    a = ap.parse_args()
    T = datetime.fromisoformat(a.time.replace("Z", "+00:00"))
    iso = lambda d: d.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    lat, lon = map(float, a.here.split(","))
    fx = {}
    fx["nearby"] = clean(get("/api/v6/stoptimes", {"center": a.here, "withAlerts": "true", "radius": "500", "n": "40", "time": a.time}))
    fx["plan"] = clean(get("/api/v6/plan", dict(fromPlace=a.here, toPlace=a.to, arriveBy="false", useRoutedTransfers="true",
        maxPreTransitTime="1800", withFares="true", maxPostTransitTime="1800", maxDirectTime="3600", directModes="WALK", time=a.time,
        numLegAlternatives="2", slowDirect="true", fastestSlowDirectFactor="1.5")))
    seen, its = set(), []
    for it in fx["plan"]["itineraries"]:   # MOTIS sometimes lists the same connection twice
        k = json.dumps([(l["mode"], l.get("tripId"), l["startTime"], l["endTime"]) for l in it["legs"]])
        if k not in seen: seen.add(k); its.append(it)
        for l in it["legs"]:
            for alt in l.get("alternatives") or []:
                for x in alt:
                    x.pop("steps", None)
                    if x.get("legGeometry"): geom(x["legGeometry"], 15)
    fx["plan"]["itineraries"] = its
    for m in ("BIKE", "CAR"):
        r = clean(get("/api/v6/plan", dict(fromPlace=a.here, toPlace=a.to, arriveBy="false", transitModes="", directModes=m, maxDirectTime="21600", time=a.time)))
        for d in r.get("direct", []):
            for l in d["legs"]:
                l.pop("steps", None)
                if l.get("legGeometry"): geom(l["legGeometry"], 8)
        fx["street-" + m.lower()] = r
    mn, mx = f"{lat - 0.03:.5f},{lon - 0.045:.5f}", f"{lat + 0.03:.5f},{lon + 0.045:.5f}"
    stops = get("/api/v6/map/stops", {"min": mn, "max": mx, "zoom": "15"})
    fx["map-stops"] = [s for s in stops if abs(s["lat"] - lat) < 0.02 and abs(s["lon"] - lon) < 0.03 and s["stopId"].startswith("de-DELFI")]
    mn, mx = f"{lat - 0.016:.5f},{lon - 0.024:.5f}", f"{lat + 0.016:.5f},{lon + 0.024:.5f}"
    segs = []
    for s in get("/api/v6/map/trips", {"min": mn, "max": mx, "zoom": "15", "startTime": iso(T - timedelta(minutes=2)),
                                       "endTime": iso(T + timedelta(minutes=8)), "precision": "5"}):
        if s.get("distance", 0) > 12000: continue
        for k in ("from", "to"):
            s[k] = {kk: vv for kk, vv in s[k].items() if kk in ("name", "stopId", "lat", "lon", "tz", "track", "scheduledTrack")}
        if s.get("polyline"): s["polyline"] = enc(simplify(dec(s["polyline"], 5), 3), 5)
        segs.append(s)
    fx["map-trips"] = segs
    ids = []
    for it in fx["plan"]["itineraries"][:3]:
        for l in it["legs"]:
            if l.get("tripId") and l["tripId"] not in ids: ids.append(l["tripId"])
    fx["trips"] = {t: clean(get("/api/v6/trip", {"tripId": t})) for t in ids}
    os.makedirs(a.out, exist_ok=True)
    for k, v in fx.items():
        json.dump(v, open(os.path.join(a.out, k + ".json"), "w"), ensure_ascii=False, separators=(",", ":"))
        print(k, os.path.getsize(os.path.join(a.out, k + ".json")))
    hl, ho = map(float, a.to.split(","))
    meta = {"name": os.path.basename(a.out.rstrip("/")),
            "note": "Real Transitous (MOTIS) responses, recorded once and trimmed. The demo server moves all times to the day of the run, so the clock always shows the same time.",
            "reference": iso(T), "timeZone": "Europe/Berlin",
            "clock": T.astimezone(__import__("zoneinfo").ZoneInfo("Europe/Berlin")).strftime("%H:%M"),
            "here": {"name": a.here_name, "lat": lat, "lon": lon}, "destination": {"name": a.to_name, "lat": hl, "lon": ho}}
    json.dump(meta, open(os.path.join(a.out, "scenario.json"), "w"), ensure_ascii=False, indent=2)


if __name__ == "__main__":
    main()

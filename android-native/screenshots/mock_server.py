#!/usr/bin/env python3
"""Demo data server for the website screenshots (screenshot build type only).

Answers the Transitous/MOTIS endpoints the app uses from recorded fixtures
(fixtures/<scenario>/*.json) and moves every timestamp to the day of the run, so the
device clock (09:41 local) always matches the data. Nothing is fetched from the internet.

  python3 mock_server.py --fixtures fixtures/munich --port 8765 --log out/requests.jsonl

Control (used by capture.py):
  GET /_demo/scene?offset=-18   shift all times by an extra -18 minutes (live trip scene)
  GET /_demo/stats              request counts, unmatched requests
"""
import argparse, datetime as dt, json, re, sys, threading, urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from zoneinfo import ZoneInfo

ISO = re.compile(r"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?Z$")


def parse_iso(s):
    return dt.datetime.fromisoformat(s.replace("Z", "+00:00"))


def fmt_iso(t):
    return t.astimezone(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


class Data:
    def __init__(self, folder, date=None):
        self.meta = json.load(open(f"{folder}/scenario.json", encoding="utf-8"))
        self.fx = {}
        for k in ("nearby", "plan", "street-bike", "street-car", "map-stops", "map-trips", "trips"):
            self.fx[k] = json.load(open(f"{folder}/{k}.json", encoding="utf-8"))
        tz = ZoneInfo(self.meta["timeZone"])
        ref = parse_iso(self.meta["reference"])
        hh, mm = map(int, self.meta["clock"].split(":"))
        day = dt.date.fromisoformat(date) if date else dt.datetime.now(tz).date()
        target = dt.datetime(day.year, day.month, day.day, hh, mm, tzinfo=tz)
        self.base_shift = target - ref          # the recorded 09:41 becomes 09:41 local on the run day
        self.extra = dt.timedelta(0)            # per scene (/_demo/scene)
        self.lock = threading.Lock()
        self.counts, self.unmatched = {}, []

    @property
    def shift(self):
        return self.base_shift + self.extra

    def moved(self, x, shift=None):
        shift = self.shift if shift is None else shift
        if isinstance(x, dict):
            return {k: self.moved(v, shift) for k, v in x.items()}
        if isinstance(x, list):
            return [self.moved(v, shift) for v in x]
        if isinstance(x, str) and ISO.match(x):
            return fmt_iso(parse_iso(x) + shift)
        return x


def box(q):
    try:
        a = [float(v) for v in q["min"].split(",")]
        b = [float(v) for v in q["max"].split(",")]
        return min(a[0], b[0]), min(a[1], b[1]), max(a[0], b[0]), max(a[1], b[1])
    except (KeyError, ValueError, IndexError):
        return None


def inside(bx, lat, lon, pad=0.0):
    return bx is None or (bx[0] - pad <= lat <= bx[2] + pad and bx[1] - pad <= lon <= bx[3] + pad)


def handle(data, path, q):
    """(status, body) for one API request; None when the demo data has no answer."""
    fx = data.fx
    if path == "/api/v6/stoptimes":
        res = data.moved(fx["nearby"])
        if q.get("pageCursor"):
            res["stopTimes"] = []
        elif q.get("stopId"):
            sid = q["stopId"]
            res["stopTimes"] = [s for s in res["stopTimes"] if s["place"].get("stopId") == sid or s["place"].get("parentId") == sid]
        modes = [m for m in q.get("mode", "").split(",") if m]
        if modes:
            res["stopTimes"] = [s for s in res["stopTimes"] if s.get("mode") in modes]
        n = int(q.get("n", "0") or 0)
        if n > 0:
            res["stopTimes"] = res["stopTimes"][:n]
        return 200, res
    if path == "/api/v6/plan":
        if "transitModes" in q and q["transitModes"] == "":
            direct = q.get("directModes", "")
            key = "street-bike" if "BIKE" in direct else "street-car" if "CAR" in direct else None
            if key is None:
                return 200, {"from": fx["plan"]["from"], "to": fx["plan"]["to"], "direct": [], "itineraries": [],
                             "previousPageCursor": "", "nextPageCursor": ""}
            return 200, data.moved(fx[key])
        res = data.moved(fx["plan"])
        if q.get("pageCursor"):
            res["itineraries"] = []
        return 200, res
    if path == "/api/v6/refresh-itinerary":
        for it in fx["plan"]["itineraries"]:
            if it.get("id") == q.get("itineraryId"):
                return 200, data.moved(it)
        return None
    if path == "/api/v6/trip":
        t = fx["trips"].get(q.get("tripId", ""))
        return (200, data.moved(t)) if t else None
    if path == "/api/v6/map/stops":
        bx = box(q)
        return 200, [s for s in fx["map-stops"] if inside(bx, s["lat"], s["lon"])]
    if path == "/api/v6/map/trips":
        bx = box(q)
        segs = data.moved(fx["map-trips"])
        try:
            start, end = parse_iso(q["startTime"]), parse_iso(q["endTime"])
        except (KeyError, ValueError):
            start = end = None
        out = []
        for s in segs:
            if not (inside(bx, s["from"]["lat"], s["from"]["lon"], 0.02) or inside(bx, s["to"]["lat"], s["to"]["lon"], 0.02)):
                continue
            if start and (parse_iso(s["arrival"]) < start or parse_iso(s["departure"]) > end):
                continue
            out.append(s)
        return 200, out
    if path == "/api/v1/rentals":
        return 200, {"providerGroups": [], "providers": [], "stations": [], "vehicles": [], "zones": []}
    if path == "/api/v1/geocode":
        return 200, []
    return None


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    data: Data = None
    log = None

    def log_message(self, *a):
        pass

    def send(self, status, obj):
        body = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        u = urllib.parse.urlsplit(self.path)
        q = {k: v[0] for k, v in urllib.parse.parse_qs(u.query, keep_blank_values=True).items()}
        d = self.data
        if u.path == "/_demo/scene":
            with d.lock:
                d.extra = dt.timedelta(minutes=float(q.get("offset", "0") or 0))
            return self.send(200, {"shiftSeconds": d.shift.total_seconds()})
        if u.path == "/_demo/stats":
            with d.lock:
                return self.send(200, {"counts": d.counts, "unmatched": d.unmatched[-50:], "shiftSeconds": d.shift.total_seconds()})
        if u.path == "/_demo/reset":
            with d.lock:
                d.counts, d.unmatched = {}, []
            return self.send(200, {"ok": True})
        try:
            r = handle(d, u.path, q)
        except Exception as e:  # a broken fixture must show up in the log, not as a hang
            r = (500, {"error": f"demo server: {e!r}"})
        with d.lock:
            d.counts[u.path] = d.counts.get(u.path, 0) + 1
            if r is None:
                d.unmatched.append(self.path)
        if self.log:
            self.log.write(json.dumps({"at": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
                                       "path": self.path, "status": 404 if r is None else r[0]}) + "\n")
            self.log.flush()
        if r is None:
            return self.send(404, {"error": "Not in the demo data."})
        self.send(*r)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--fixtures", default="fixtures/munich")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--date", help="run day (YYYY-MM-DD, default: today in the scenario's time zone)")
    ap.add_argument("--log")
    a = ap.parse_args()
    Handler.data = Data(a.fixtures, a.date)
    Handler.log = open(a.log, "a") if a.log else None
    srv = ThreadingHTTPServer(("0.0.0.0", a.port), Handler)
    print(f"demo server on :{a.port}, times moved by {Handler.data.base_shift}", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Real screenshots of the screenshot build in a running Android emulator (adb).

  python3 capture.py --apk app-x86_64-screenshots.apk --out out --server http://127.0.0.1:8765

Needs: adb on PATH (emulator booted, adb root allowed), mock_server.py running, tesseract
(deu+eng), Pillow, numpy. Writes out/raw/<lang>/<nn>-<scene>.png and out/diagnostics/.
Every view is checked before it is kept: app in front, content stable (no spinner), the
expected words read by OCR, no error/loading text, the demo server asked, the map not empty,
and all captures different from each other. Any failed check fails the run.
"""
import argparse, datetime as dt, hashlib, io, json, math, os, re, subprocess, sys, threading, time, unicodedata, urllib.parse, urllib.request
from zoneinfo import ZoneInfo
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
STATUS_BAR = 150   # px at the top excluded from the stability check (fixed by demo mode anyway)


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def sh(cmd, check=True, timeout=60, binary=False):
    r = subprocess.run(cmd, shell=isinstance(cmd, str), capture_output=True, timeout=timeout)
    if check and r.returncode != 0:
        raise RuntimeError(f"command failed ({r.returncode}): {cmd}\n{r.stderr.decode(errors='replace')}")
    return r.stdout if binary else r.stdout.decode(errors="replace")


def adb(*args, check=True, timeout=60, binary=False):
    return sh(["adb", *args], check=check, timeout=timeout, binary=binary)


def shell(cmd, check=True, timeout=60):
    return adb("shell", cmd, check=check, timeout=timeout)


def fold(s):
    s = unicodedata.normalize("NFKD", s.lower())
    s = "".join(c for c in s if not unicodedata.combining(c))
    s = s.replace("’", "'").replace("‘", "'")
    return re.sub(r"[^a-z0-9' ]+", " ", s.replace("\n", " "))


def http(url):
    with urllib.request.urlopen(url, timeout=10) as r:
        return json.loads(r.read().decode("utf-8"))


def screencap():
    for _ in range(3):
        data = adb("exec-out", "screencap", "-p", binary=True, timeout=30)
        try:
            return Image.open(io.BytesIO(data)).convert("RGB"), data
        except Exception:
            time.sleep(1)
    raise RuntimeError("screencap failed")


def changed(a, b):
    """Share of pixels that differ visibly between two frames (status bar excluded)."""
    x = np.asarray(a, dtype=np.int16)[STATUS_BAR:]
    y = np.asarray(b, dtype=np.int16)[STATUS_BAR:]
    if x.shape != y.shape:
        return 1.0
    return float((np.abs(x - y).sum(axis=2) > 24).mean())


def ocr(img, path):
    img.save(path)
    return sh(["tesseract", path, "stdout", "-l", "deu+eng", "--psm", "11"], timeout=120)


def map_filled(img):
    """Map tiles loaded: the middle of the screen is detailed, not one flat background colour."""
    a = np.asarray(img)[int(img.height * 0.2):int(img.height * 0.8):2, ::2]
    q = (a // 16).reshape(-1, 3)
    keys = q[:, 0].astype(np.int32) * 256 + q[:, 1].astype(np.int32) * 16 + q[:, 2]
    _, counts = np.unique(keys, return_counts=True)
    dominant = counts.max() / counts.sum()
    edges = float((np.abs(np.diff(a.astype(np.int16), axis=1)).sum(axis=2) > 40).mean())
    return dominant < 0.75 and len(counts) > 40 and edges > 0.01, {"dominant": round(float(dominant), 3), "colours": int(len(counts)), "edges": round(edges, 4)}


def check_text(text, groups, forbid):
    t = " " + " ".join(fold(text).split()) + " "
    missing = [g for g in groups if not any(fold(w).strip() in t for w in g)]
    found = [w for w in forbid if fold(w).strip() and fold(w).strip() in t]
    return missing, found


def decode_polyline(s, precision):
    f = 10 ** precision; i = lat = lon = 0; pts = []
    while i < len(s):
        vals = []
        for _ in (0, 1):
            r = shift = 0
            while True:
                b = ord(s[i]) - 63; i += 1; r |= (b & 31) << shift; shift += 5
                if b < 32:
                    break
            vals.append(~(r >> 1) if r & 1 else r >> 1)
        lat += vals[0]; lon += vals[1]
        pts.append((lat / f, lon / f))
    return pts


def along(pts, frac):
    """The point at a share of a polyline's length."""
    def d(a, b):
        k = math.cos(math.radians((a[0] + b[0]) / 2))
        return math.hypot(a[0] - b[0], (a[1] - b[1]) * k)
    segs = [d(a, b) for a, b in zip(pts, pts[1:])]
    goal = sum(segs) * frac
    for (a, b), s in zip(zip(pts, pts[1:]), segs):
        if goal <= s and s > 0:
            t = goal / s
            return a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t
        goal -= s
    return pts[-1]


class Device:
    def __init__(self, cfg, diag):
        self.cfg, self.diag, self.pkg = cfg, diag, cfg["package"]
        self.fix = (cfg["here"]["lat"], cfg["here"]["lon"])
        self._stop = threading.Event()
        threading.Thread(target=self._gps, daemon=True).start()

    def _gps(self):
        while not self._stop.is_set():
            lat, lon = self.fix
            adb("emu", "geo", "fix", f"{lon:.6f}", f"{lat:.6f}", check=False, timeout=15)
            time.sleep(2)

    def setup(self):
        adb("root", check=False); time.sleep(3); adb("wait-for-device", timeout=120)
        cmds = [
            "settings put global auto_time 0", "settings put global auto_time_zone 0",
            "setprop persist.sys.timezone Europe/Berlin", "cmd alarm set-timezone Europe/Berlin",
            "settings put system font_scale 1.0", "settings put system time_12_24 24",
            "settings put global window_animation_scale 0", "settings put global transition_animation_scale 0",
            "settings put global animator_duration_scale 0", "cmd uimode night no",
            "settings put secure location_mode 3", "settings put global heads_up_notifications_enabled 0",
            "settings put secure show_ime_with_hard_keyboard 0", "settings put secure immersive_mode_confirmations confirmed",
            "settings put secure navigation_mode 2",
            "cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural",
            "wm size reset", "wm density reset",
        ]
        for c in cmds:
            shell(c, check=False)
        overlays = shell("cmd overlay list", check=False)
        open(f"{self.diag}/overlays.txt", "w").write(overlays)
        # The emulator's Pixel 8 Pro profile: the same centred camera hole the device frame shows.
        for cand in ("com.android.internal.emulation.pixel_8_pro", "com.android.internal.display.cutout.emulation.pixel_8_pro"):
            if cand in overlays:
                shell(f"cmd overlay enable {cand}", check=False)
        time.sleep(2)
        info = {
            "model": shell("getprop ro.product.model", check=False).strip(),
            "sdk": shell("getprop ro.build.version.sdk", check=False).strip(),
            "size": shell("wm size", check=False).strip(), "density": shell("wm density", check=False).strip(),
            "timezone": shell("getprop persist.sys.timezone", check=False).strip(),
            "font_scale": shell("settings get system font_scale", check=False).strip(),
            "cutout": [l.strip() for l in shell("dumpsys display", check=False).splitlines() if "utout" in l][:4],
        }
        json.dump(info, open(f"{self.diag}/device.json", "w"), indent=2)
        log("device", info)
        if "1344x2992" not in info["size"]:
            raise RuntimeError(f"unexpected screen size: {info['size']}")
        self.demo_mode()

    def demo_mode(self):
        shell("settings put global sysui_demo_allowed 1", check=False)
        b = "am broadcast -a com.android.systemui.demo -e command"
        for c in ("enter", "clock -e hhmm 0941", "battery -e level 100 -e plugged false -e powersave false",
                  "network -e wifi show -e level 4 -e fully true", "network -e mobile show -e datatype 5g -e level 4 -e fully true",
                  "network -e airplane hide", "notifications -e visible false", "status -e volume hide -e bluetooth hide -e location hide -e alarm hide -e sync hide -e tty hide -e eri hide -e mute hide -e speakerphone hide -e managed_profile hide"):
            shell(f"{b} {c}", check=False)

    def install(self, apk):
        adb("install", "-r", "-g", apk, timeout=300)

    def set_clock(self, day, clock):
        hh, mm, ss = clock.split(":")
        shell(f"date {day.strftime('%m%d')}{hh}{mm}{day.strftime('%Y')}.{ss}")

    def reset_app(self, locale):
        shell(f"am force-stop {self.pkg}", check=False)
        shell(f"pm clear {self.pkg}")
        for p in ("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "POST_NOTIFICATIONS"):
            shell(f"pm grant {self.pkg} android.permission.{p}", check=False)
        shell(f"cmd locale set-app-locales {self.pkg} --locales {locale}")
        got = shell(f"cmd locale get-app-locales {self.pkg}", check=False)
        if locale not in got:
            raise RuntimeError(f"app language not set: {got.strip()}")
        adb("logcat", "-c", check=False)

    def seed_live(self, server, live):
        """The started trip of the live view, from the demo plan (files/trips.json as TripStore writes it)."""
        plan = http(f"{server}/api/v6/plan?fromPlace=x&toPlace=y")
        it = plan["itineraries"][live["itinerary"]]
        it = http(f"{server}/api/v6/refresh-itinerary?itineraryId={urllib.parse.quote(it['id'])}")
        leg = it["legs"][live["leg"]]
        g = leg["legGeometry"]
        self.fix = along(decode_polyline(g["points"], g.get("precision", 6)), live["progress"])
        trip = [{"id": live["id"], "itinerary": it, "fromName": live["from"], "toName": live["to"],
                 "savedAt": it["startTime"], "followLive": True}]
        local = f"{self.diag}/trips.json"
        json.dump(trip, open(local, "w"), ensure_ascii=False)
        open(f"{self.diag}/active-trip.json", "w").write(live["id"])
        files = f"/data/data/{self.pkg}/files"
        shell(f"mkdir -p {files}")
        adb("push", local, "/data/local/tmp/trips.json")
        adb("push", f"{self.diag}/active-trip.json", "/data/local/tmp/active-trip.json")
        uid = shell(f"stat -c %u /data/data/{self.pkg}").strip()
        shell(f"cp /data/local/tmp/trips.json /data/local/tmp/active-trip.json {files}/ && chown -R {uid}:{uid} {files} && chmod 700 {files} && chmod 600 {files}/* && restorecon -R {files}")
        log("live trip seeded, position", self.fix)

    def front(self):
        out = shell("dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'", check=False)
        return self.pkg in out

    def start(self, step):
        if "start" in step:
            shell(f"am start -W -n {self.pkg}/{self.cfg['activity']}", timeout=60)
        elif "link" in step:
            shell(f"am start -W -a android.intent.action.VIEW -d '{step['link']}' {self.pkg}", timeout=60)


def wait_ready(dev, name, groups, forbid, tol, timeout, diag, server=None, requires=(), is_map=False):
    """Screens until the view is fully loaded, stable and passes every check; returns (image, png bytes, report)."""
    end = time.time() + timeout
    prev, stable, reasons, n = None, 0, ["no frame yet"], 0
    while time.time() < end:
        time.sleep(1.5)
        img, png = screencap()
        n += 1
        if not dev.front():
            reasons = ["app not in front (crash, dialog or system screen)"]
            prev, stable = img, 0
            img.save(f"{diag}/{name}-last.png")
            continue
        diff = changed(prev, img) if prev is not None else 1.0
        stable = stable + 1 if diff <= tol else 0
        prev = img
        if stable < 2:
            reasons = [f"still changing ({diff:.4f} > {tol})"]
            continue
        text = ocr(img, f"{diag}/{name}-last.png")
        missing, found = check_text(text, groups, forbid)
        reasons = []
        if missing: reasons.append(f"missing text: {missing}")
        if found: reasons.append(f"error/loading text: {found}")
        stats = {}
        if is_map:
            ok, stats = map_filled(img)
            if not ok: reasons.append(f"map looks empty: {stats}")
        if server and requires:
            counts = http(f"{server}/_demo/stats")["counts"]
            miss = [r for r in requires if counts.get(r, 0) == 0]
            if miss: reasons.append(f"demo server not asked: {miss}")
        if not reasons:
            open(f"{diag}/{name}.txt", "w").write(text)
            return img, png, {"frames": n, "stable_diff": diff, "map": stats, "ocr_words": len(text.split())}
        stable = 1   # check again on the next stable frame
    open(f"{diag}/{name}-reasons.txt", "w").write("\n".join(reasons))
    raise RuntimeError(f"{name}: not ready after {timeout}s: {'; '.join(reasons)}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True)
    ap.add_argument("--out", default="out")
    ap.add_argument("--server", default="http://127.0.0.1:8765")
    ap.add_argument("--scenes", default=os.path.join(HERE, "scenes.json"))
    ap.add_argument("--only", help="comma separated scene ids")
    ap.add_argument("--langs", default="de,en")
    a = ap.parse_args()
    cfg = json.load(open(a.scenes, encoding="utf-8"))
    diag = os.path.join(a.out, "diagnostics"); os.makedirs(diag, exist_ok=True)
    day = dt.datetime.now(ZoneInfo("Europe/Berlin")).date()
    dev = Device(cfg, diag)
    dev.setup()
    dev.install(a.apk)
    scenes = [s for s in cfg["scenes"] if not a.only or s["id"] in a.only.split(",")]
    report, shots, failures = [], {}, []
    for lang in a.langs.split(","):
        L = cfg["languages"][lang]
        os.makedirs(os.path.join(a.out, "raw", lang), exist_ok=True)
        for i, sc in enumerate(scenes, 1):
            name = f"{lang}-{sc['id']}"
            log("scene", name)
            try:
                http(f"{a.server}/_demo/scene?offset={sc.get('offset', 0)}")
                http(f"{a.server}/_demo/reset")
                dev.fix = (cfg["here"]["lat"], cfg["here"]["lon"])
                dev.reset_app(L["locale"])
                if sc.get("seed") == "live":
                    dev.seed_live(a.server, cfg["live"])
                time.sleep(3)   # a fresh GPS fix before the app asks
                dev.set_clock(day, cfg["clock"])
                dev.demo_mode()
                for k, step in enumerate(sc["steps"]):
                    if "wait" in step:
                        wait_ready(dev, f"{name}-step{k}", step["wait"], [], 0.01, 120, diag)
                    else:
                        dev.start(step)
                img, png, rep = wait_ready(dev, name, sc["expect"] + L["expect"], cfg["forbid"] + L["forbid"],
                                           sc.get("tolerance", 0.004), sc.get("timeout", 150), diag,
                                           a.server, sc.get("requires", []), sc.get("map", False))
                path = os.path.join(a.out, "raw", lang, f"{cfg['scenes'].index(sc) + 1:02d}-{sc['id']}.png")
                open(path, "wb").write(png)
                shots[name] = img
                rep.update({"scene": sc["id"], "lang": lang, "file": os.path.relpath(path, a.out), "sha256": hashlib.sha256(png).hexdigest()})
                report.append(rep)
                log("ok", name, rep)
            except Exception as e:
                failures.append(f"{name}: {e}")
                log("FAILED", name, e)
                try: screencap()[0].save(f"{diag}/{name}-failed.png")
                except Exception: pass
            finally:
                open(f"{diag}/logcat-{name}.txt", "w").write(adb("logcat", "-d", "-v", "time", check=False, timeout=60))
                try: json.dump(http(f"{a.server}/_demo/stats"), open(f"{diag}/server-{name}.json", "w"), indent=1)
                except Exception: pass
    # No two captures alike: every view differs from the others, and each language from the other.
    names = sorted(shots)
    hashes = {}
    for r in report:
        if r["sha256"] in hashes:
            failures.append(f"identical captures: {hashes[r['sha256']]} and {r['lang']}-{r['scene']}")
        hashes[r["sha256"]] = f"{r['lang']}-{r['scene']}"
    for x in names:
        for y in names:
            if x >= y: continue
            same_scene = x.split("-", 1)[1] == y.split("-", 1)[1]
            d = changed(shots[x], shots[y])
            if (same_scene and d < 0.002) or (not same_scene and x[:2] == y[:2] and d < 0.08):
                failures.append(f"captures too similar: {x} / {y} ({d:.4f})")
    json.dump({"day": day.isoformat(), "shots": report, "failures": failures}, open(os.path.join(a.out, "report.json"), "w"), indent=2)
    if failures or len(report) != len(scenes) * len(a.langs.split(",")):
        log("FAILED:\n  " + "\n  ".join(failures))
        return 1
    log(f"all {len(report)} screenshots ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())

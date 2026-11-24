#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
# SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
"""Wear OS screenshots for Google Play, from the real watch app in a Wear OS emulator.

  python3 wear_shots.py --apk wear-debug.apk --out out

Needs adb on PATH with a booted Wear OS emulator (round, at least 384 px). Departures nearby come live
from Transitous for a fixed position in Vienna; the trip under way is seeded the way the phone sends
it (LiveTripStore, shared prefs publit.live), so the live screen and its map show a real-looking ride.
Writes out/wear/<lang>/<nn>-<view>.png (square, as Play wants for Wear OS).
"""
import argparse, json, os, re, subprocess, sys, time
from xml.sax.saxutils import escape

PKG = "io.github.elija7580.publit"
HERE = (48.2085, 16.3731)   # Stephansplatz, Vienna

# U1 Stephansplatz -> Karlsplatz -> Taubstummengasse (about), then a short walk.
U1 = [(48.2085, 16.3731), (48.2062, 16.3714), (48.2040, 16.3700), (48.2010, 16.3695), (48.1990, 16.3690), (48.1960, 16.3713)]
WALK = [(48.1960, 16.3713), (48.1952, 16.3735), (48.1946, 16.3752)]

TEXT = {
    "en": {"headline": "Get off at Taubstummengasse", "detail": "Next stop: Karlsplatz · 2 stops to go", "then": "Then walk 3 min",
           "destination": "Belvedere", "stages": ["Walk to Stephansplatz", "U1 to Taubstummengasse", "Walk to Belvedere"],
           "map": ["Map"]},
    "de": {"headline": "Aussteigen: Taubstummengasse", "detail": "Nächster Halt: Karlsplatz · noch 2 Halte", "then": "Dann 3 Min. zu Fuß",
           "destination": "Belvedere", "stages": ["Zu Fuß zum Stephansplatz", "U1 bis Taubstummengasse", "Zu Fuß zum Belvedere"],
           "map": ["Karte", "Map"]},
}
LOCALES = {"en": "en-GB", "de": "de-AT"}


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def adb(*args, check=True, timeout=60, binary=False, stdin=None):
    r = subprocess.run(["adb", *args], capture_output=True, timeout=timeout, input=stdin)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)}: {r.stderr.decode(errors='replace')}")
    return r.stdout if binary else r.stdout.decode(errors="replace")


def shell(cmd, check=False, timeout=60):
    return adb("shell", cmd, check=check, timeout=timeout)


def shot(path):
    png = adb("exec-out", "screencap", "-p", binary=True, timeout=60)
    if len(png) < 5000:
        raise RuntimeError("empty screenshot")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, "wb").write(png)
    log("saved", path, len(png))


def tap_text(words, tries=10):
    """Taps the first view whose text or description contains one of the words."""
    for _ in range(tries):
        shell("uiautomator dump /sdcard/ui.xml")
        xml = adb("exec-out", "cat", "/sdcard/ui.xml", check=False)
        for w in words:
            m = re.search(r'(?:text|content-desc)="[^"]*' + re.escape(w) + r'[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
            if m:
                x1, y1, x2, y2 = map(int, m.groups())
                shell(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
                return True
        shell("input swipe 200 330 200 150 300")
        time.sleep(1)
    return False


def seed_trip(lang):
    t = TEXT[lang]
    now = int(time.time() * 1000)
    trip = {
        "id": f"demo-{lang}", "phase": "RIDING", "cue": "getOff", "headline": t["headline"], "detail": t["detail"],
        "next": now + 4 * 60_000 + 20_000, "start": now - 6 * 60_000, "end": now + 9 * 60_000, "destination": t["destination"],
        "delay": 0, "stepMode": "TRANSIT", "line": "U1", "lineColor": "#E3000F", "lineText": "#FFFFFF", "direction": "Oberlaa",
        "legTime": now + 4 * 60_000, "boardAt": "Stephansplatz", "platform": None, "platformChanged": False, "then": t["then"],
        "stages": [{"text": t["stages"][0], "done": True, "now": False}, {"text": t["stages"][1], "done": False, "now": True},
                   {"text": t["stages"][2], "done": False, "now": False}],
        "legs": [{"walk": False, "color": "#E3000F", "pts": [list(p) for p in U1]}, {"walk": True, "color": "#888888", "pts": [list(p) for p in WALK]}],
        "current": 0,
    }
    trip = {k: v for k, v in trip.items() if v is not None}
    value = escape(json.dumps(trip, ensure_ascii=False), {'"': "&quot;"})
    xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n    <string name=\"trip\">" + value + "</string>\n</map>\n"
    shell(f"am force-stop {PKG}")
    adb("shell", f"run-as {PKG} sh -c 'mkdir -p shared_prefs && cat > shared_prefs/publit.live.xml'", stdin=xml.encode())
    got = adb("shell", f"run-as {PKG} cat shared_prefs/publit.live.xml", check=False)
    if "Taubstummengasse" not in got:
        raise RuntimeError("live trip not seeded: " + got[:200])


def gps():
    adb("emu", "geo", "fix", f"{HERE[1]:.6f}", f"{HERE[0]:.6f}", check=False, timeout=15)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True)
    ap.add_argument("--out", default="out")
    ap.add_argument("--langs", default="en,de")
    a = ap.parse_args()
    diag = os.path.join(a.out, "diagnostics"); os.makedirs(diag, exist_ok=True)
    adb("install", "-r", "-g", a.apk, timeout=300)
    shell("settings put global window_animation_scale 0"); shell("settings put global transition_animation_scale 0")
    shell("settings put global animator_duration_scale 0"); shell("settings put secure location_mode 3")
    size = shell("wm size").strip()
    log("screen", size)
    failures = []
    for lang in a.langs.split(","):
        out = os.path.join(a.out, "wear", lang)
        try:
            shell(f"am force-stop {PKG}")
            shell(f"pm clear {PKG}")
            for p in ("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "POST_NOTIFICATIONS"):
                shell(f"pm grant {PKG} android.permission.{p}")
            shell(f"cmd locale set-app-locales {PKG} --locales {LOCALES[lang]}")
            for _ in range(5):
                gps(); time.sleep(1)
            # 1. Departures nearby (live data).
            shell(f"am start -W -n {PKG}/.wear.WearActivity", timeout=60)
            for _ in range(30):
                gps(); time.sleep(2)
                ui = shell("uiautomator dump /sdcard/ui.xml >/dev/null; cat /sdcard/ui.xml")
                if re.search(r'text="[^"]*(Stephansplatz|Stephansdom|U1|U3)[^"]*"', ui):
                    break
            time.sleep(3)
            shot(f"{out}/01-nearby.png")
            shell("input swipe 200 330 200 120 300"); time.sleep(2)
            shot(f"{out}/02-nearby-more.png")
            # 2. The trip under way, as the phone sends it: opens by itself.
            seed_trip(lang)
            shell(f"am start -W -n {PKG}/.wear.WearActivity", timeout=60)
            time.sleep(6)
            shot(f"{out}/03-live.png")
            shell("input swipe 200 330 200 140 300"); time.sleep(2)
            shot(f"{out}/04-live-more.png")
            # 3. Its map.
            if tap_text(TEXT[lang]["map"]):
                time.sleep(12)
                shot(f"{out}/05-map.png")
            else:
                failures.append(f"{lang}: map chip not found")
        except Exception as e:
            failures.append(f"{lang}: {e}")
            log("FAILED", lang, e)
        finally:
            open(f"{diag}/logcat-{lang}.txt", "w").write(adb("logcat", "-d", "-v", "time", check=False, timeout=60))
            try: shot(f"{diag}/{lang}-end.png")
            except Exception: pass
    if failures:
        log("FAILED:\n  " + "\n  ".join(failures))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

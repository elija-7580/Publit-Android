#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
# SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
"""Screen video of the started-trip location service, for Google Play's foreground service declaration.

  python3 fgs_video.py --apk app-play-x86_64-screenshots.apk --out out --server http://127.0.0.1:8765

Same emulator and demo data as capture.py. A started trip is opened (live view), so Publit starts its
location foreground service (GetOffService, foregroundServiceType=location) for the get-off reminder;
then the app goes to the background and the notification shade shows the ongoing trip notification
that keeps running while the user is not in the app. Writes out/fgs-location-demo.mp4.
"""
import argparse, datetime as dt, json, os, subprocess, sys, time
from zoneinfo import ZoneInfo

import capture
from capture import Device, adb, http, log, shell


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True)
    ap.add_argument("--out", default="out")
    ap.add_argument("--server", default="http://127.0.0.1:8765")
    ap.add_argument("--lang", default="en")
    a = ap.parse_args()
    cfg = json.load(open(os.path.join(capture.HERE, "scenes.json"), encoding="utf-8"))
    diag = os.path.join(a.out, "diagnostics"); os.makedirs(diag, exist_ok=True)
    day = dt.datetime.now(ZoneInfo("Europe/Berlin")).date()
    live_scene = next(s for s in cfg["scenes"] if s.get("seed") == "live")

    dev = Device(cfg, diag)
    dev.setup()
    dev.install(a.apk)
    http(f"{a.server}/_demo/scene?offset={live_scene.get('offset', 0)}")
    http(f"{a.server}/_demo/reset")
    dev.reset_app(cfg["languages"][a.lang]["locale"])
    dev.seed_live(a.server, cfg["live"])
    time.sleep(3)
    dev.set_clock(day, cfg["clock"])
    # Real status bar and heads-up notifications this time: the location and trip notifications are the point.
    shell("am broadcast -a com.android.systemui.demo -e command exit", check=False)
    shell("settings put global heads_up_notifications_enabled 1", check=False)
    shell(f"am start -W -n {dev.pkg}/{cfg['activity']}", timeout=60)
    time.sleep(4)

    remote = "/sdcard/fgs-demo.mp4"
    shell(f"rm -f {remote}", check=False)
    rec = subprocess.Popen(["adb", "shell", "screenrecord", "--size", "720x1600", "--bit-rate", "6000000", "--time-limit", "60", remote])
    time.sleep(2)
    try:
        # 1. The user opens their started trip: the live view (get-off reminder by location is on by default).
        shell(f"am start -W -a android.intent.action.VIEW -d 'publit://trip/{cfg['live']['id']}' {dev.pkg}", timeout=60)
        time.sleep(12)
        services = shell(f"dumpsys activity services {dev.pkg}", check=False)
        open(f"{diag}/services.txt", "w").write(services)
        if "GetOffService" not in services:
            raise RuntimeError("location foreground service did not start")
        # 2. The user leaves the app (home): the trip keeps being followed.
        shell("input keyevent KEYCODE_HOME")
        time.sleep(5)
        # 3. The notification shade: the ongoing trip notification of the running service.
        shell("cmd statusbar expand-notifications")
        time.sleep(9)
        shell("cmd statusbar collapse")
        time.sleep(2)
        # 4. Back into the trip from the launcher.
        shell(f"am start -W -n {dev.pkg}/{cfg['activity']}", timeout=60)
        time.sleep(6)
    finally:
        shell("pkill -INT screenrecord", check=False)
        try: rec.wait(timeout=30)
        except subprocess.TimeoutExpired: rec.kill()
        time.sleep(3)
        open(f"{diag}/logcat-fgs.txt", "w").write(adb("logcat", "-d", "-v", "time", check=False, timeout=60))
    out = os.path.join(a.out, "fgs-location-demo.mp4")
    adb("pull", remote, out, timeout=120)
    size = os.path.getsize(out)
    log("video", out, size, "bytes")
    if size < 100_000:
        raise RuntimeError("video too small")
    return 0


if __name__ == "__main__":
    sys.exit(main())

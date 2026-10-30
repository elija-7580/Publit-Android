#!/usr/bin/env python3
"""Device frames, posters, WebP and a preview for the raw emulator screenshots.

  python3 frame.py --raw out/raw --art art/pixel_8_pro --font NotoSans-VF.ttf --out out

The frame is Android Studio's Pixel 8 Pro device art (back.webp + mask.webp, Apache 2.0,
see README.md). Its screen opening is exactly 1344 x 2992 at (58, 58), the emulator's
screen size, so screenshots go in 1:1 and are never scaled or distorted inside the frame.
The poster layout, shadow and device height follow the iPhone App Store mockups
(Publit iOS repo, tools/appstore/compose.py).
"""
import argparse, glob, html, json, math, os, sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

SCREEN = (1344, 2992)
OFFSET = (58, 58)
CAP = {  # scene: (de title, de sub, en title, en sub), as the iPhone posters where the feature is the same
    "nearby": ("Abfahrten um dich herum.", "Linie, Steig und Live-Zeiten für jede Haltestelle in der Nähe.",
               "Departures around you.", "Line, platform and live times for every stop nearby."),
    "plan": ("Alle Wege auf einer Zeitachse.", "Sieh, wer zuerst ankommt, wo du umsteigst und wo es knapp wird.",
             "Every route on one timeline.", "See who arrives first, where you change and where it gets tight."),
    "live": ("Deine Fahrt, live.", "Deine Linie, die Minuten bis zum Ziel und wo du aussteigst. Auch in der Benachrichtigung.",
             "Your trip, live.", "Your line, the minutes left and where to get off. Also in the notification."),
    "map": ("Haltestellen und Fahrzeuge live.", "Die Karte mit Haltestellen, Linien und Fahrzeugen in Echtzeit, auf MapLibre.",
            "Every stop and vehicle, live.", "The map with stops, lines and vehicles in real time, on MapLibre."),
}


def frame_parts(art):
    back = Image.open(os.path.join(art, "back.webp")).convert("RGBA")
    mask = Image.open(os.path.join(art, "mask.webp")).convert("RGBA")
    if mask.size != SCREEN or back.size != (1469, 3104):
        raise SystemExit(f"unexpected device art size: back {back.size}, mask {mask.size}")
    # Smooth the binary outline of the case a little (the art has hard 0/255 alpha).
    a = back.getchannel("A")
    soft = a.filter(ImageFilter.GaussianBlur(0.7))
    inner = Image.new("L", back.size, 0)
    inner.paste(255, (OFFSET[0] + 40, OFFSET[1] + 40, OFFSET[0] + SCREEN[0] - 40, OFFSET[1] + SCREEN[1] - 40))
    back.putalpha(Image.composite(a, soft, inner))   # the screen opening keeps its exact edge
    return back, mask


def device(raw, back, mask):
    if raw.size != SCREEN:
        raise SystemExit(f"screenshot is {raw.size}, the Pixel 8 Pro frame needs {SCREEN} (no scaling, no distortion)")
    d = Image.new("RGBA", back.size, (0, 0, 0, 0))
    d.paste(raw.convert("RGB"), OFFSET)
    d.alpha_composite(mask, OFFSET)     # camera hole and screen corners
    d.alpha_composite(back)
    return d


def shadowed(dev, k):
    """A transparent PNG with the poster's soft drop shadow (alpha 110, offset 22k, blur 34k)."""
    off, blur = int(22 * k), max(6, int(34 * k))
    m = 3 * blur
    W, H = dev.width + 2 * m, dev.height + 2 * m + off
    sh = Image.new("L", (W, H), 0)
    sh.paste(dev.getchannel("A").point(lambda v: 110 if v > 128 else 0), (m, m + off))
    sh = sh.filter(ImageFilter.GaussianBlur(blur))
    out = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    out.putalpha(sh)
    out.alpha_composite(dev, (m, m))
    return out


def font(path, size, w):
    f = ImageFont.truetype(path, size)
    try: f.set_variation_by_axes([w, 100])
    except Exception: pass
    return f


def glyph(height, ink):
    """The publit P with the yellow dot (as in compose.py)."""
    S = 8; N = 1024 * S
    im = Image.new("RGBA", (N, N), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    def P(x, y): return ((x - 38) * S, (y - 18) * S)
    pts = [(400, 790), (400, 420)] + [(550 + 150 * math.cos(math.radians(180 + i)), 420 + 150 * math.sin(math.radians(180 + i))) for i in range(271)] + [(400, 570)]
    w = 96 * S
    for a, b in zip(pts, pts[1:]): d.line([P(*a), P(*b)], fill=ink + (255,), width=w)
    for p in pts:
        x, y = P(*p); d.ellipse([x - w / 2, y - w / 2, x + w / 2, y + w / 2], fill=ink + (255,))
    x, y = P(550, 420); r = 58 * S; d.ellipse([x - r, y - r, x + r, y + r], fill=(255, 197, 49, 255))
    im = im.crop(im.getbbox())
    return im.resize((max(1, round(im.width * height / im.height)), height), Image.LANCZOS)


def wrap(d, text, f, maxw):
    lines, cur = [], ""
    for word in text.split():
        t = (cur + " " + word).strip()
        if d.textlength(t, font=f) <= maxw: cur = t
        else: lines.append(cur); cur = word
    return lines + ([cur] if cur else [])


def poster(dev, scene, lang, fontpath):
    """1320 x 2868 like the iPhone posters: same caption, margins, device height rule and shadow."""
    bg, ink, sub = (244, 244, 242), (17, 17, 17), (102, 102, 107)
    W, H = 1320, 2868
    k = W / 1398
    c = Image.new("RGB", (W, H), bg); d = ImageDraw.Draw(c)
    t, s = CAP[scene][0:2] if lang == "de" else CAP[scene][2:4]
    x0, hs, ss, ws, gs, railx, gy, dot_y = int(104 * k), int(98 * k), int(40 * k), int(40 * k), int(52 * k), int(58 * k), int(80 * k), int(262 * k)
    d.line([(railx, 0), (railx, dot_y)], fill=ink, width=max(3, int(5 * k)))
    rd = max(8, int(17 * k)); d.ellipse([railx - rd, dot_y - rd, railx + rd, dot_y + rd], fill=(255, 197, 49))
    g = glyph(gs, ink); c.paste(g, (x0, gy), g)
    d.text((x0 + g.width + int(18 * k), gy + gs // 2), "publit", font=font(fontpath, ws, 700), fill=ink, anchor="lm")
    hf, sf = font(fontpath, hs, 800), font(fontpath, ss, 450)
    maxw = W - x0 - int(60 * k)
    y = gy + int(110 * k)
    for ln in wrap(d, t, hf, maxw): d.text((x0, y), ln, font=hf, fill=ink); y += int(hs * 1.1)
    if s:
        y += int(38 * k)
        for ln in wrap(d, s, sf, maxw): d.text((x0 + 3, y), ln, font=sf, fill=sub); y += int(ss * 1.42)
    top = y + int(44 * k)
    mx = int(70 * k)
    scale = (H - top - int(50 * k)) / dev.height
    if dev.width * scale > W - 2 * mx: scale = (W - 2 * mx) / dev.width
    dv = dev.resize((int(dev.width * scale), int(dev.height * scale)), Image.LANCZOS)
    fx, fy = (W - dv.width) // 2, top
    sh = Image.new("L", (W, H), 0)
    sh.paste(dv.getchannel("A").point(lambda v: 110 if v > 128 else 0), (fx, fy + int(22 * k)))
    sh = sh.filter(ImageFilter.GaussianBlur(max(6, int(34 * k))))
    c.paste(Image.new("RGB", (W, H), (0, 0, 0)), (0, 0), sh)
    c.paste(dv, (fx, fy), dv)
    return c, {"device_height": dv.height, "device_top": fy, "device_width": dv.width}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--raw", default="out/raw")
    ap.add_argument("--art", required=True)
    ap.add_argument("--font", required=True)
    ap.add_argument("--out", default="out")
    a = ap.parse_args()
    back, mask = frame_parts(a.art)
    files = sorted(glob.glob(os.path.join(a.raw, "*", "*.png")))
    if not files:
        raise SystemExit("no screenshots")
    manifest = []
    for f in files:
        lang = os.path.basename(os.path.dirname(f))
        stem = os.path.splitext(os.path.basename(f))[0]          # 01-nearby
        scene = stem.split("-", 1)[1]
        raw = Image.open(f)
        dev = device(raw, back, mask)
        outs = {}
        def save(img, kind, name, **kw):
            p = os.path.join(a.out, kind, lang, name); os.makedirs(os.path.dirname(p), exist_ok=True)
            img.save(p, **kw); outs[kind + ("-webp" if name.endswith(".webp") else "")] = os.path.relpath(p, a.out)
        framed = shadowed(dev, dev.height / 3104 * 0.944)
        save(framed, "framed", f"{stem}-pixel-8-pro.png", optimize=True)
        save(framed.resize((framed.width // 2, framed.height // 2), Image.LANCZOS), "webp", f"{stem}-pixel-8-pro.webp", quality=88, method=6)
        p, geo = poster(dev, scene, lang, a.font)
        save(p, "posters", f"{stem}-android.png", optimize=True)
        save(p, "posters", f"{stem}-android.webp", quality=88, method=6)
        w = raw.convert("RGB").resize((600, round(600 * raw.height / raw.width)), Image.LANCZOS)
        save(w, "web", f"web-{scene}-android.webp", quality=86, method=6)
        manifest.append({"lang": lang, "scene": scene, "raw": os.path.relpath(f, a.out), **outs, "poster_geometry": geo})
        print("framed", lang, stem, geo, flush=True)
    # Overlay for publit.eu's .phone component (same idea as assets/iphone-17-pro.webp): the frame
    # with a transparent screen, 2/3 size like the iPhone overlay (1350 px bezel -> 900 px).
    ov = Image.new("RGBA", back.size, (0, 0, 0, 0)); ov.alpha_composite(mask, OFFSET); ov.alpha_composite(back)
    ov = ov.resize((round(back.width * 2 / 3), round(back.height * 2 / 3)), Image.LANCZOS)
    os.makedirs(os.path.join(a.out, "web"), exist_ok=True)
    ov.save(os.path.join(a.out, "web", "pixel-8-pro.webp"), quality=92, method=6)
    json.dump(manifest, open(os.path.join(a.out, "manifest.json"), "w"), indent=2)
    preview(a.out, manifest)


def preview(out, manifest):
    """preview/framed.png, preview/posters.png and preview/index.html with every image."""
    os.makedirs(os.path.join(out, "preview"), exist_ok=True)
    langs = sorted({m["lang"] for m in manifest})
    def sheet(key, h, name):
        rows = [[m for m in manifest if m["lang"] == l] for l in langs]
        cols = max(len(r) for r in rows)
        ims = [[Image.open(os.path.join(out, m[key])).convert("RGBA") for m in r] for r in rows]
        ims = [[i.resize((round(i.width * h / i.height), h), Image.LANCZOS) for i in r] for r in ims]
        cw = max(i.width for r in ims for i in r)
        pad = 30
        S = Image.new("RGB", (pad + cols * (cw + pad), pad + len(rows) * (h + pad)), (244, 244, 242))
        for y, r in enumerate(ims):
            for x, i in enumerate(r):
                S.paste(i, (pad + x * (cw + pad) + (cw - i.width) // 2, pad + y * (h + pad)), i)
        S.save(os.path.join(out, "preview", name), optimize=True)
    sheet("framed", 900, "framed.png")
    sheet("posters", 900, "posters.png")
    cells = "\n".join(
        f'<figure><img src="../{html.escape(m["framed"])}" alt=""><figcaption>{m["lang"]} · {m["scene"]}<br>'
        f'<a href="../{m["raw"]}">raw</a> · <a href="../{m["framed"]}">framed</a> · <a href="../{m["webp-webp"]}">webp</a> · '
        f'<a href="../{m["posters"]}">poster</a></figcaption></figure>' for m in manifest)
    open(os.path.join(out, "preview", "index.html"), "w").write(
        "<!doctype html><meta charset=utf-8><title>Publit Android screenshots</title>"
        "<style>body{font:15px system-ui;background:#f4f4f2;margin:24px}main{display:grid;grid-template-columns:repeat(4,1fr);gap:24px}"
        "img{width:100%}figure{margin:0}figcaption{text-align:center}</style>"
        "<h1>Publit Android, website screenshots</h1><p><img src=framed.png style='max-width:100%'></p><main>" + cells + "</main>")


if __name__ == "__main__":
    sys.exit(main())

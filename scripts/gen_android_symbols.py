#!/usr/bin/env python3
"""Generate Android symbolsets for the SF Symbols Publit uses.

SkipUI looks up `<name>.symbolset` in the main bundle's asset catalog before its small
built-in Material mapping (which renders a warning triangle for anything unknown).
Each SF name gets the matching Material Symbols Rounded glyph in four weights, so
icons follow the font weight like on iOS. Run from the repo root; needs network.
"""
import json, os, re, sys, urllib.request

OUT = "Sources/Publit/Resources/Module.xcassets/Symbols"
BASE = "https://fonts.gstatic.com/s/i/short-term/release/materialsymbolsrounded/{name}/{style}/24px.svg"

# SF Symbol -> Material Symbol. `.fill` names (and filled forms of the rest) use the filled glyph.
MAP = {
    "airplane": "flight", "antenna.radiowaves.left.and.right": "sensors", "archivebox": "archive",
    "arrow.clockwise": "refresh", "arrow.down": "arrow_downward", "arrow.down.forward": "south_east",
    "arrow.right": "arrow_forward", "arrow.right.circle": "arrow_circle_right", "arrow.left": "arrow_back",
    "arrow.triangle.branch": "alt_route", "arrow.triangle.swap": "swap_horiz",
    "arrow.triangle.turn.up.right.diamond": "directions", "arrow.turn.up.left": "turn_left",
    "arrow.turn.up.right": "turn_right", "arrow.up": "arrow_upward", "arrow.up.arrow.down": "swap_vert",
    "arrow.up.forward": "north_east", "arrow.up.forward.app": "open_in_new", "arrow.up.forward.circle": "north_east",
    "arrow.up.left": "north_west", "arrow.up.left.and.arrow.down.right": "open_in_full", "arrow.up.right": "north_east",
    "arrow.uturn.down": "u_turn_left", "arrow.uturn.forward": "redo", "arrow.uturn.right": "u_turn_right",
    "bag": "shopping_bag", "banknote": "payments", "bed.double": "bed", "bell": "notifications",
    "bicycle": "directions_bike", "birthday.cake": "cake", "bolt.car": "electric_car", "bookmark": "bookmark",
    "bookmark.slash": "bookmark_remove", "briefcase": "work", "building.2.crop.circle": "apartment",
    "building.2": "apartment", "building.columns": "account_balance", "bus.doubledecker": "directions_bus",
    "bus": "directions_bus", "cablecar": "gondola_lift", "calendar": "calendar_month",
    "calendar.badge.plus": "calendar_add_on", "camera": "photo_camera", "car": "directions_car",
    "car.side": "directions_car", "cart": "shopping_cart", "checkmark": "check", "checkmark.circle": "check_circle",
    "chevron.compact.right": "chevron_right", "chevron.down": "keyboard_arrow_down", "chevron.left": "chevron_left",
    "chevron.right": "chevron_right", "chevron.up": "keyboard_arrow_up", "circle": "circle", "clock": "schedule",
    "clock.arrow.circlepath": "history", "cross.case": "medical_services", "cross": "local_hospital",
    "cup.and.saucer": "local_cafe", "doc": "description", "doc.text.magnifyingglass": "find_in_page",
    "doc.viewfinder": "document_scanner", "dot.radiowaves.left.and.right": "sensors", "ellipsis": "more_horiz",
    "ellipsis.circle": "more_horiz", "exclamationmark.bubble": "feedback", "exclamationmark.circle": "error",
    "exclamationmark.octagon": "report", "exclamationmark.triangle": "warning", "ferry": "directions_boat",
    "figure.roll": "accessible", "figure.stairs": "stairs", "figure.walk": "directions_walk",
    "figure.walk.departure": "directions_walk", "figure.walk.motion": "directions_walk",
    "flag.checkered": "sports_score", "flag.checkered.circle": "sports_score", "flag": "flag",
    "fork.knife": "restaurant", "fuelpump": "local_gas_station", "gearshape": "settings",
    "globe.europe.africa": "public", "hare": "speed", "heart": "favorite", "house": "home",
    "house.circle": "home", "info.circle": "info", "lightrail": "tram",
    "line.3.horizontal.decrease.circle": "filter_list", "link": "link", "list.bullet": "format_list_bulleted",
    "location": "near_me", "location.circle": "assistant_navigation", "location.north.circle": "assistant_navigation",
    "location.north": "navigation", "location.north.line": "navigation", "location.slash": "location_off",
    "magnifyingglass": "search", "map": "map", "mappin": "location_on", "mappin.and.ellipse": "location_on",
    "mappin.circle": "location_on", "mic": "mic", "minus.magnifyingglass": "zoom_out", "mountain.2": "landscape",
    "nosign": "block", "parkingsign": "local_parking", "pencil": "edit", "photo": "image",
    "photo.on.rectangle": "photo_library", "play": "play_arrow", "plus": "add", "plus.circle": "add_circle",
    "plus.magnifyingglass": "zoom_in", "plus.rectangle.on.rectangle": "library_add",
    "point.topleft.down.to.point.bottomright.curvepath": "route", "questionmark.circle": "help",
    "rectangle": "rectangle", "rectangle.stack": "stacks", "scooter": "electric_scooter",
    "slider.horizontal.3": "tune", "sparkles": "auto_awesome", "speaker.slash": "volume_off",
    "speaker.wave.2": "volume_up", "square.3.layers.3d": "layers", "square.and.arrow.up": "ios_share",
    "square.and.arrow.up.on.square": "ios_share", "square.grid.2x2": "grid_view", "star": "star",
    "star.slash": "star_border", "stop.circle": "stop_circle", "ticket": "confirmation_number",
    "toilet": "wc", "train.side.front.car": "train", "tram": "tram", "tram.circle": "tram",
    "tram.fill.tunnel": "subway", "trash": "delete", "tray.and.arrow.up": "upload", "tree": "park",
    "view.3d": "3d_rotation", "wallet.pass": "wallet", "wifi.slash": "wifi_off", "wineglass": "wine_bar",
    "xmark": "close", "xmark.circle": "cancel",
}
# Glyphs that are only drawn one way in Material Symbols.
NO_FILL = {"star_border"}
WEIGHTS = [("Light-M", "wght300"), ("Regular-M", ""), ("Semibold-M", "wght600"), ("Bold-M", "wght700")]

def fetch(name, weight, fill):
    style = weight + ("fill1" if fill else "")
    url = BASE.format(name=name, style=style or "default")
    with urllib.request.urlopen(url, timeout=30) as r:
        svg = r.read().decode()
    return re.findall(r'<path[^>]* d="([^"]+)"', svg)

def symbol_svg(material, fill):
    groups = []
    for gid, w in WEIGHTS:
        paths = "".join(f'<path d="{d}"/>' for d in fetch(material, w, fill and material not in NO_FILL))
        groups.append(f'<g id="{gid}">{paths}</g>')
    # Guides: SkipUI sizes a symbol by the template cap height (SF Pro: 0.7046 em), so 591.9
    # makes one em 840 units, close to the size SF Symbols draw at the same font size.
    return ('<?xml version="1.0" encoding="UTF-8"?>\n'
            '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 -960 960 960">'
            '<g id="Guides"><line id="Capline-M" x1="0" x2="960" y1="0" y2="0"/>'
            '<line id="Baseline-M" x1="0" x2="960" y1="591.9" y2="591.9"/></g>'
            '<g id="Symbols">' + "".join(groups) + '</g></svg>\n')

def main():
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "Contents.json"), "w") as f:
        json.dump({"info": {"author": "xcode", "version": 1}, "properties": {"provides-namespace": False}}, f, indent=2)
    names = []
    for sf, material in MAP.items():
        names.append((sf, material, False))
        if not sf.endswith(".fill") and ".fill." not in sf:
            names.append((sf + ".fill", material, True))
    names.append(("tram.fill.tunnel", "subway", True))
    cache = {}
    for sf, material, fill in names:
        key = (material, fill)
        if key not in cache:
            cache[key] = symbol_svg(material, fill)
        d = os.path.join(OUT, sf + ".symbolset")
        os.makedirs(d, exist_ok=True)
        fn = sf + ".svg"
        with open(os.path.join(d, fn), "w") as f:
            f.write(cache[key])
        with open(os.path.join(d, "Contents.json"), "w") as f:
            json.dump({"info": {"author": "xcode", "version": 1},
                       "symbols": [{"filename": fn, "idiom": "universal"}]}, f, indent=2)
    print(f"{len(names)} symbolsets, {len(cache)} glyphs")

if __name__ == "__main__":
    main()

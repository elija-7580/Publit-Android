# Publit design language: Ink & Dot

Flat, matte and platform-neutral. One language for iOS, Android and the web. Values live in
`tokens.json`, a clickable preview (sample data) in `prototype.html`. This document is the
reference for every screen; the code names below are the iOS implementation
(`Sources/Views/Components/Design.swift`, `Sources/Util/Palette.swift`).

---

## 1. Principles
1. **Flat content.** Opaque cards on a light warm grey ground (dark: near black), large continuous radius (22), hairlines instead of shadows, no blur.
2. **Matte floating layer.** What floats (live bar, map chips, map controls, capsules) is an opaque white surface with a hairline. Over a map it gets the one soft shadow of the design (`mapShadow`); nowhere else.
3. **Ink, not colour.** The interface is black / white / grey. The primary action is ink (black; white in dark mode). Selected chips and segments are ink or a raised white key.
4. **Colour means something.** Line colours (badges, route tracks) are the main colour on screen. Green / orange / red only for on time / late / cancelled. Blue only for the way still ahead on a map (`routeAhead`).
5. **The dot is the signature.** The yellow dot of the logo (#FFC531) appears only where it means "you" or "live": your position, the trip in progress, the current tab, the edge mark of the recommended route. Never as a fill or a background. One dot detail per screen.
6. **The stroke comes from the logo.** Route lines and progress bars use the thick round-capped stroke; stops are rings; walks are rows of dots.
7. **Numbers first.** Minutes and times are large, bold and tabular; units small and grey (`DurationFigure`).
8. **Soft, not bevelled.** Inputs and secondary controls are flat soft wells (`inkFill`) with generous continuous corners (14–16) or capsules; no inner shadows, gradients or 3D keys.
9. **One family, few sizes.** SF Pro only (tabular digits via `.monospacedDigit()`, never SF Mono). At most four sizes and two weights per row.
10. **Say it once.** A fact appears once per screen: the overview says what, the steps say where. No list that repeats the list below it.

## 2. Foundations

### Colour (tokens → Swift)
| Role | Light | Dark | Swift |
|---|---|---|---|
| Ground (screen) | #F4F4F2 | #0B0B0C | `Color.ground` |
| Surface (cards, floating) | #FFFFFF | #19191B | `Color.surface` |
| Fill (wells, chips) | #EBEBE7 | #2A2A2D | `Color.inkFill` |
| Ink (text, primary action) | #111111 | #F4F4F2 | `Color.ink` |
| On ink | #FFFFFF | #111111 | `Color.onInk` |
| Hairline | ink 9 % | ink 9 % | `matteSurface` stroke |
| Live / on time | #1B7A33 | #30D158 | `Color.live` |
| Delay | #B25000 | #FF9F0A | `Color.delay` |
| Cancelled / danger | #D70015 | #FF453A | `Color.danger` |
| Way ahead (map) | #0A7CFF | #409CFF | `Color.routeAhead` |
| Way behind (map) | #A9CDFA | #5B7FA8 | `Color.routeTraveled` |
| Dot | #FFC531 | #FFC531 | `Color.signature` |

All text colours reach 4.5 : 1 on surface and ground in both modes (tested in `Palette` tests).
Secondary text is the system `.secondary`; tertiary only for chevrons and hints.

### Type
| Use | Style |
|---|---|
| Times in a route row, leave card | `headline` / `title2` bold, tabular |
| Durations | `DurationFigure` (number bold, unit caption) |
| Stop and place names | `subheadline` semibold |
| Secondary lines (platform, direction, "from …") | `caption` / `footnote`, secondary |
| Section headers | system list header |
| Minute labels on timelines | `caption2` semibold, tabular |

### Spacing and shape
- 8-pt rhythm: 4 · 8 · 12 · 16 · 20 · 24 · 32. Rows: 12 between lines of one block, 16 card padding.
- Radii: cards 22, controls and wells 14–16, badges 8, chips and capsules full.
- Touch targets ≥ 44 pt (map controls 44 × 44, chips 34 high inside a 44 row).

### Elevation
- Content: flat. A `List` section is one card (`.inkList()`, `.inkCard()`).
- Raised key (`raisedKey`): the selected segment, one 6-pt shadow at 8 %.
- Floating over a map (`mapShadow`): 5-pt shadow at 16 %. Only chips, map controls, capsules and hints on maps or above a scrolling list.

## 3. Components

### Buttons
| Kind | Style | Use |
|---|---|---|
| Primary | `.buttonStyle(.ink)` | one per screen part: Start trip, Switch to this connection |
| Secondary | `.buttonStyle(.soft)` | next to a primary or alone: Other connections, Now, Open in Maps |
| Chip | `.buttonStyle(.chip)` | small controls above a list: sort, map |
| Map control | `.buttonStyle(.mapControl)` | round white 44-pt controls on maps: layers, my location |
| Round key | ink circle 34–40 pt with an `onInk` symbol | search next to the destination, swap |
| Text link | `Link` / plain button, semibold | "Fares and tickets: ACTV" |

No system `.bordered`, `.borderedProminent`, `.blue` or materials. Destructive actions use `role: .destructive` (system red).

### Chips and segments
- Mode chips (`ModeFilterBar`): in lists a fill capsule, selected ink. **On a map: white chips** (surface + hairline + map shadow), selected ink; one fixed row of symbols, no scrolling.
- Segments (`BevelSegmented`): a fill well with a raised white key for the selection. `large: true` for main controls (Plan: Depart / Arrive, 40 pt).

### Cards, rows and notices
- Route row (`ItineraryRow`): times + duration on one line; timeline bar (rides in line colour with the line name, walks as dots, waiting dashed; minutes under rides and under walks of 3 min or more); boarding line; changes; notices. The recommended route gets the yellow edge mark, not a different position.
- Notices: `Notice` (coloured edge + symbol, ink text; only critical is tinted) and `InlineNotice` for one line in a row.
- Leave card (`LeaveHero`): the one number that decides, each time once.
- Region line (`RegionTicketRow`): "Venice · ACTV", a ticket symbol, one sentence on where to buy, a link to fares.

### Floating capsules
`floatingCapsule()`: white capsule, hairline, map shadow, subheadline semibold ink label with a symbol.
Used for "Back to now" / "Trip overview" on the live map and "Recommended: 08:12 – 09:30" above the
route list. A floating capsule never covers the thing it points to: it hides once that is in view.

### Sheets
- Medium + large detents for map selections; background interaction up to medium.
- Every sheet closes three ways: swipe down, a ✕ (`xmark`, top trailing) or Done, and — on maps — a tap on empty map. The live trip sheet is the exception: no swipe-to-close (too easy to lose the trip), only its close button.

## 4. Screens

### Map (station map)
- Top: white mode chips floating below the search bar; hints ("Zoom in to see stations") as white capsules under them.
- Bottom trailing, one column: **layers** then **my location**, both `.mapControl`. Nothing in the nav bar except Done (trailing, system).
- Loading: the logo mark in a white circle in the middle, not blocking taps.
- Selections open a sheet; the picked place's marker goes when the sheet closes.

### Plan
- From / To card with the connector stroke; swap key on the right; search key (ink circle with a magnifier) right next to the destination.
- Depart / Arrive as a large segment, the date and time below it, a soft "Now" button at the end of that row.
- Results: network header ("Venice · ACTV"), route rows, "Later connections", footer with "Updated …". Floating "Recommended" capsule when that row is out of sight.
- "Other ways" (walk, bike, car) only when realistic; each opens in the chosen maps app.

### Route detail
1. Overview: map preview, live status, leave card, line chain, network and ticket line, time-zone and driving notes.
2. Warnings, smart changes.
3. Actions (Start, Save, Calendar, Reminder).
4. Steps: a timeline; each ride shows where to get on and off and opens for its stops, live source, notes and the get-off alarm.
5. Fares, then the "All information without guarantee" footnote.

### Live trip
- Map with the current leg in full detail, the rest thinned; floating "Back to now / Trip overview" capsule top leading.
- Alternatives compare with the trip ("8 min later than your trip"); primary "Switch to this connection", soft "Other connections".
- The live bar reserves its height at the bottom of every tab (`safeAreaPadding`), so nothing hides behind it.

## 5. Motion
- `.snappy(duration: 0.2–0.25)` for toggles, expands and segment changes; capsules move in from the bottom with opacity.
- Respect Reduce Motion: the logo loader is static, countdowns change without animation.
- No self-redrawing timers in rows: shared minute clock (`Ticker`), `Clocked(every:)` only for small parts.

## 6. Writing
- Short, plain, second person: "Leave in 6 min", "Board at Rialto", "Get off at San Marco".
- Say what to do, not what failed: "This connection is already over. Plan again for a new one."
- Times as the clock shows them, durations in minutes, platforms as "Platform 3".
- Data honesty: live only when it is live ("Live · ÖBB"), "Timetable" otherwise; "All information without guarantee" under routes and in Settings → Data.

## 7. Accessibility
- Every control has a label; symbols-only controls (map controls, chips) get `accessibilityLabel`.
- Combined rows read as one sentence; decorative strokes are hidden.
- Dynamic Type up to accessibility sizes: rows switch to stacked layouts by text size, not `ViewThatFits`.
- Contrast ≥ 4.5 : 1 for text, ≥ 3 : 1 for controls, in light and dark.

## 8. Live bar and loading (implementation notes)
- Install `.liveTripBar()` on each tab's NavigationStack. The host measures the bar and pads the tab content with `safeAreaPadding(.bottom, height)`; sheet roots without the bar reset `liveBarInset` to zero.
- Pull the live bar up or tap it to open the trip.
- Loading uses `LogoLoader`, the logo stroke and landing dot.
- `DurationFigure` keeps localized units small and numbers large, bold and tabular.

## 9. Review checklist (before shipping a screen)
- [ ] 8-pt spacing; max two weights and four sizes per row; aligned columns in repeated rows.
- [ ] One yellow-dot detail at most; no colour without meaning; no system blue or materials.
- [ ] Primary = ink, secondary = soft, map = white controls with map shadow.
- [ ] Nothing said twice; nothing hidden behind the tab bar or live bar.
- [ ] Every sheet closes by swipe, ✕/Done and (maps) tap on empty map.
- [ ] Light and dark checked; Dynamic Type XL checked; VoiceOver labels on symbol-only controls.

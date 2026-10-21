# Changelog

## 0.6.0 (build 59)

- iPad: Publit now runs natively on iPad, in every orientation, in Split View and Stage Manager. Tabs sit in the floating tab bar at the top; lists, routes and settings stay in a readable column instead of stretching across the screen.
- iPhone Duo: built with the iOS 27 SDK (Xcode 27), so the app fills the inner display and resizes when you unfold, instead of running letterboxed. On the inner display Publit uses the same wide layout as on iPad.
- Builds, tests and TestFlight uploads now run on Xcode 27.

## 0.6.0 (build 58)

- Switzerland: live times everywhere. Transitous has the Swiss timetable but almost none of its live data, so Publit now asks search.ch (Swiss public transport data) for delays, cancellations and track changes of trains, trams, buses, boats and cable cars. Stops are matched exactly by their Swiss stop number, not by distance.
- North Rhine-Westphalia: where Transitous has no live time, Publit asks VRR's journey planner; stops are matched exactly by their national stop ID (DHID). Essen, Münster, Bonn, Aachen and the Ruhr get live times for many buses and trams that had none.
- More of Germany: where Transitous has no live time, Publit also asks VBB (Berlin and Brandenburg), RMV (Hesse), VBN (Lower Saxony and Bremen), NAH.SH (Schleswig-Holstein) and VRN (Rhineland-Palatinate). Measured: Wiesbaden 7 → 43 of 44 live, Oldenburg 19 → 34 of 44, Kaiserslautern 11 → 22 of 39, Bremen 31 → 40 of 42, Berlin-Spandau 35 → 41 of 41. Each German stop is asked of one server only, picked by its national stop ID.
- The extra sources are only asked for rides without a live time from Transitous (fewer requests, Transitous still wins).
- Carinthia (Villach, Klagenfurt, Spittal, Wolfsberg): local buses are still missing. Transitous currently skips the Carinthian timetable when importing, so there are no bus rides for ÖBB's live times to correct; this has to be fixed in Transitous.

## 0.6.0 (build 57)

- Pasted map links are found again even when a few characters went missing while typing ("httwww.google.com/maps/…"); the leftover characters no longer become the place name.

## 0.6.0 (build 56)

- Long trips open at once: the route screen shows time and lines first and the rest (changes, actions, steps) a moment later with a loader, instead of the tap seeming to do nothing.
- "Where to now?": tapping a route no longer redraws the comparison map with every route first; the map only redraws when the routes change.
- Route shapes: a cheaper cache key (it walked the whole encoded line on every draw).

## 0.6.0 (build 55)

- Map, Apple Maps style with the lines first: the new default look "Transit" (paler streets), bolder line colours on a light edge, stations as rounded squares with their mode symbol, Apple's places with their symbols.
- Map search: a scope bar (All · Stops · Places · Addresses), up to 25 hits with distance and the place's coloured symbol.
- "Find nearby": restaurants, cafés, bakeries, supermarkets, shops, pharmacies, hospitals, hotels, ATMs, parking, EV chargers, petrol stations, toilets, museums, parks and bars, all shown on the map in their colour; "Search this area" after moving the map. Typing a kind ("Pharmacies") offers all of them around.

## 0.6.0 (build 54)

- Route: the preview map frames the route itself (not your location), uses Publit's own marks (ring at the start, ink dot at the destination, small rings where you change) and is drawn just after the screen opens, so routes open faster.
- Route: time and lines sit in one calm card; the line chain has no arrows any more and walks are small chips, centred next to the line badges.
- Live trip: "Trip overview / Back to now" is a round key in the map's button column at the top left instead of a capsule over the stage strip; the duplicate full-screen map key is gone. Start and destination use the same marks as on the route.
- Live trip: opened full screen, so pulling the panel down can no longer swipe the whole trip away.
- Saved: "Find a line" removed.
- Plan: stray separator lines under "To" and under "Now" removed.

## 0.6.0 (build 53)

### Release
- TestFlight without a Mac: every build tag is archived, signed in the cloud with the App Store Connect API key and uploaded by GitHub Actions (.github/workflows/testflight.yml).
- Bundle ID is now io.github.elija7580.publit (widgets, watch app and share extension follow), matching the App Store Connect record.

## 0.6.0 (build 52)

### Design
- Nearby "Where to now?" now looks like Plan's From/To card: same label column, semibold text and the round ink search key (no grey well any more).
- Home, Work and the next trip use one row type everywhere (Nearby and Saved): a round soft key with the symbol, semibold title, place below, an arrow when the row starts a route.
- Live trip: "Trip overview / Back to now" moved off the map's buttons to the bottom right, on the top edge of the panel; it moves with the panel and hides when the panel is fully up. The duplicate round overview key on the map is gone.
## 0.6.0 (build 51)

### Onboarding
- First launch shows three short steps: what Publit is, why it asks for your location (Allow location / Not now), Home and Work (set them right there), and where the live trip and widget are. Skip at any time; shown once.

### Settings
- Start screen: open Publit on Nearby or Plan.
- Live data sources: which operators Publit asks besides Transitous (Wiener Linien, ÖBB, ACTV, SL, TfL) and for what.
- Export my data: saved places and lines, trips, search history and tickets as one JSON file via the share sheet.

## 0.6.0 (build 50)

### Design
- The design language is written down in full (docs/design/README.md): colours, type, spacing, buttons, chips, sheets, floating controls, screens, motion, writing, accessibility and a review checklist.
- Map: white mode chips with a hairline instead of grey ones; layers and "My location" are round white controls in one column at the bottom edge (the blue system location button and the layers button in the bar are gone); hints and errors float as white capsules.
- Secondary buttons ("Other connections", "Now", "Open in Maps", ticket actions) share one soft style instead of the system's blue bordered buttons.
- Floating capsules ("Back to now", "Recommended: …") and the map preview button share one white look with a soft shadow.

## 0.6.0 (build 49)

### Plan
- Depart/Arrive and the date and time are bigger and spaced out; a "Now" button sets the time back to now.
- The ✕ (Clear destination) is gone; a search button sits right next to the destination.
- Route rows: walks are dotted lines, minutes only where they fit (rides, and walks of 3 min or more).
- When the recommended route is further down the list, a floating "Recommended: 08:12 – 09:30" capsule jumps to it; the list stays where it is otherwise, and the capsule hides once the route is in view.
- Above the routes and in the route detail: the network and how to buy a ticket, e.g. "Venice · ACTV – Tickets in the AVM Venezia app or at ticket machines. Validate paper tickets before boarding." with a link to fares (14 cities so far).
- Walking, cycling and driving comparisons only when they are realistic (no 3-hour walks next to a 40-minute train); each opens in Apple Maps (or the app chosen in Settings) from the row's menu or the route detail.
- History shows where a destination came from: search 🔍, map pin 📍, favourite ⭐, line or link.

### Route detail
- One overview at the top, no repeated boarding list; each ride in the steps shows where to get on and off and opens to its stops, live source, notes and the get-off alarm.
- "All information without guarantee" note under the route and in Settings → Data.

### Map
- A tapped place or stop closes with a tap on empty map, the ✕ or a swipe down; its marker goes too.
- Buses, trams and ferries are drawn at the widest zoom that shows lines (the server only sent trains there).

## 0.6.0 (build 48)

### Live trip
- The live trip sheet no longer closes when you swipe down by accident; close it with the button.
- Walks are drawn on the map again, also when the router sends no shape (a straight line instead of nothing).
- New route during a trip: alternatives are compared with your trip ("8 min later than your trip"), "Switch to this connection" takes it over, "Other connections" lists more to switch to, and "New route from here" searches right there (the old button did nothing).
- A floating button on the map switches between "Back to now" and "Trip overview".
- The live bar no longer covers buttons and the last rows of a screen.

### Fixed
- The start button explains why a trip cannot start (connection already over, or a ride does not run) instead of doing nothing.
- Long trips (e.g. a 600 km train ride) open much faster: overview maps draw thinned lines instead of thousands of points. This load on the main thread was the likely cause of the app quitting on long trips.

## 0.6.0 (build 47)

### Live data where Transitous has none
- Delays, cancellations, platforms and disruptions now also come straight from the operators: Wiener Linien (Vienna), ÖBB (all of Austria: trains, and buses/trams where the region reports them, plus construction notes), ACTV (Venice vaporetti and buses), SL (Stockholm) and TfL line status (London).
- Routes appear as before and get live times about a second later; departure boards and the live trip refresh use them too. Walks after a late ride move with it.
- Route detail shows where live times come from ("Live · ÖBB"). Settings → Data: "Live data from more operators" (on by default) with credits.

### Fixed
- "Walk from  does not run": a walk to a cancelled stop no longer marks the whole trip as cancelled; only rides count.

## 0.6.0 (build 46)

### Code review: fixes and speed
- Map links typed or pasted without the slashes ("https:www.google.com/maps/…") or without "https://" ("www.google.com/maps/…", "maps.apple.com/…") are recognised as places.
- Diagnostics log: entries are written off the main thread (an error used to read and rewrite up to 400 KB on the main thread, a small hitch of its own); date formatters are reused instead of created per screen step.
- Fare prices reuse one formatter per currency instead of building one for every route row.
- 999.6 m shows as "1.0 km", not "1000 m".

## 0.6.0 (build 45)

### Design: softer and calmer
- Plan: From and To in one soft card joined by the logo's line (ring → dot), a round swap key; Depart/Arrive as a capsule switch next to the date and time; Route options as a soft chip. No more bevelled, edgy fields.
- Inputs and switches everywhere are flat soft wells with round corners, like the Nearby cards.
- One typeface: durations no longer use monospaced digits from a different font.
- Routes list: the "Recommended" chip is solid ink and readable in dark mode; "Walk to … · 1 min" is a quiet secondary line; warnings sit closer to their icon; "Updated" is no longer hidden behind the tab bar.
- Route preview: the leave/arrive card is a calm surface card instead of a bright white slab in dark mode, each time shown once; line chain with simple chevrons; boarding steps in aligned columns (time, line, stop).
- Nearby: no empty band between a station and its first departure.
- Fixed: Build 44 did not compile (live trip walk stage).

## 0.6.0 (build 44)

### Live trip
- Closing a started trip is "Minimize" (chevron down): the trip keeps running in the live bar. Ending it is in the ••• menu, without a stray confirmation over the map.
- The ••• menu also has the map style (Standard, Muted, Satellite, …) and 3D, the same settings as the Map tab, and spoken directions.
- Ticket button: tap shows the default ticket right away (code full screen); hold to pick another ticket, make it the default, see all or add one.
- A later walk picked in the trip shows its own way from where it starts (with a start dot), not from where you stand; "Open in Maps" for it starts there too.

### Tickets
- Looking at a route or a line shows no tickets; saved and started trips do.

### Smart routing
- Your walking speed comes from Apple Health (walking speed of the last 30 days) when you allow it, else from the step counter; Settings shows the source.
- Smart routing settings are also in the route options of Plan.

## 0.6.0 (build 43)

- Lines without an operator colour (e.g. many Italian feeds) now get a clear colour per mode instead of grey, so routes are colourful everywhere.
- Settings: link to the new privacy policy (website, German and English); the outdated note about a backup server is gone.
- Shared links point to the website (`/publit-site/open/`), which works again; links from older builds are still understood.

## 0.6.0 (build 42)

### Plan and map
- Routes always in time order; the best one is marked (Recommended, Fastest, Fewest changes, Least walking) instead of re-sorted. No sort menu.
- Route bars all start at the left; the longest route fills the width.
- No map toggle and no time axis row in the routes list.
- Trip of a line: tap any stop → “From <station> to here”, “Route here”, “Route from here”.
- Station map: mode filters float over the map; loading mark in the middle.
- Fix: crash when tapping a notification.
- Transitous only (no fallback to motis-project.de servers, per Transitous policy).

## 0.6.0 (build 41)

### Plan
- Routes stay current: a departure time of now follows the clock, routes refresh live, pull down to refresh, “Updated” time below the list.
- Where to go at a glance: each route shows the stop, platform and walk to get on; the route detail lists every boarding and the exit at the top.
- Station map: the loading mark sits in the middle of the map.

## 0.6.0 (build 40)

### Smart routing
- New default sort “Recommended”: routes ranked by how long they feel (waiting and walking count more, each change costs extra time), clearly worse routes are hidden. Badges: Recommended, Fastest, Fewest changes, Least walking, with a short reason.
- Each change shows how likely it works (safe / likely / risky, in percent) and a backup connection if you miss it.
- Live trip: warning and notification when a change is at risk, with the backup.
- Settings → Smart routing: on/off, Faster ↔ Fewer changes, extra time per change, own walking speed (step counter, optional), avoid steep walks, learn from chosen trips.

## 0.6.0 (build 39)

### Google Maps in the app
- Settings → Map → Map provider: route, compare and live trip maps can be drawn with Google Maps. Uses the same Google API key as the place search (enable “Maps SDK for iOS” in the Google project). Without a key, Apple Maps stays.

## 0.6.0 (build 38)

### Map and search, your choice
- Settings → Map: map style (standard, muted, satellite) and points of interest for every map in the app, not only the station map.
- "Open in …" goes to the app you choose: Apple Maps, Google Maps or Citymapper.
- Settings → Search: place search with OpenStreetMap (Transitous) or Google. Google brings shops, restaurants and sights as in Google Maps, in the same list; stops still come from Transitous. Needs your own Google API key (Places API).

## 0.6.0 (build 37)

### Live trip
- Pulling up the panel over the map no longer jitters: it follows the finger smoothly, keeps its direction for the whole drag and settles at down, half way or up. Down and half way the whole panel drags; up, the list scrolls and the handle pulls it down again.

## 0.6.0 (build 36)

### Better routes
- Short walks first: Publit searches with a 15 and a 30 min walk to and from the stops at once and keeps a route with the longer walk only when it arrives at least 10 min earlier (Punta Sabbioni → Cannaregio now stays on the 5.1 instead of walking half an hour to save a minute).
- No detours: routes that pass the destination and ride on, or come back to where an earlier leg started, are left out.
- Times in route details stay on one line, also with AM/PM.

### Sharing a place
- A place shared from Google Maps or another app always opens Plan with routes from where you are now, leaving now. Publit waits briefly for a fresh location instead of using an old one, closes settings and other sheets, and says so when it cannot find the place.

## 0.6.0 (build 35)

### Start screens in one style
- Nearby, Plan, Tickets and Saved share one look: settings on every tab, sort and map as round chips like the mode filters, one empty state (no more cut-off titles), "Find a line" as a row in Saved.
- Warnings ("Tight change", "Long walk", service changes) look the same everywhere: the symbol in its colour, the words in ink. Critical ones get a tinted card.
- The trip under way is shown once, in the live bar, not again as "Active trip" and "Next trip".

### Bevelled inputs
- "Where to now?" and From/To are fields pressed into the card; the swap button is a raised key; Depart/Arrive is Publit's own switch instead of the system segmented control.

### Nearby stations
- Tap a station to open it. The new button next to it plans a route there from where you are, leaving now. A long press offers "Route here" and "All departures".

### Route taps and the trip
- Route rows always open. Trip lines look like Apple Maps (the part ahead strong, travelled faded) in each line's colour.
- The live trip is map-first with a panel (peek, half, full), swipeable stage cards with a big countdown, the stage details in the trip list, clear labels ("Take U6 towards …", "Get off at …"), a walk from where you are when you left the planned path, "End trip" at the top and an automatic end at the destination.
- The route preview has a small map at the top and a timeline of the legs; a card says when to leave and when you arrive.

## 0.6.0 (build 34)

### Far stops are shown, not hidden
- Nearby looks for departures within 500 m, 1.5 km and then 5 km. When the nearest stop is far (from 1.5 km), the board lists it anyway and says so above the list: "The nearest departures are 2.9 km away, about 48 min on foot." Station headers show the walking time from 400 m. Example: Cavallino-Treporti, 3 km from the Punta Sabbioni ferry, used to show an empty board.
- Route search tries walks of 30 min, 45 min and then up to 2 h to the first and from the last stop before it says "No connections". Routes found with a longer walk than usual carry a note: "These routes include a long walk: up to 40 min to or from the stop."
- With a shorter walking limit set in Route options, the message names that limit instead of a plain "No connections".
- The empty board says "No departures within 5 km" and offers the station map.

### Map: tap places, drop pins, search sights
- Apple Maps points of interest (museums, shops, beaches …) are on by default on the station map and can be tapped: the place sheet shows distance, walking time, "Route here" and "Route from here".
- A long press anywhere drops a pin with the same sheet; the address follows when Apple knows it.
- The map search combines stops and towns from Transitous with places from Apple Maps ("Museum", a shop name) around the visible map.

### Start screen type
- Weights follow the type tokens: the destination in departure rows is semibold (the station name stays the heaviest text), "now" is as large as the minute figures, "min" and the delay are lighter, "Next trip" has the weight of the other rows in its card, "All departures" is medium.

### Circle lines
- Lines that start and end at the same place (ACTV 5.1/5.2 at Lido, town buses that loop) no longer read as going the wrong way. A leg on such a line says "Towards S. Alvise · Circle line" (where you get off); a departure board says "Circle line" instead of "Towards Lido" at Lido. Piers of the same place ("Lido S.M.E. "A"" and ""B"") count as one.

### Started trip
- The stop list draws one continuous line from the first to the last stop; the rows no longer break it into pieces (it looked like a rope ladder). Travelled parts are faded.
- Walks on all route maps are a row of dots like in Apple Maps instead of thick dashes.
- The map has two new buttons: "Show whole trip" (the whole route on the same map, with start and destination; tap again to go back to the stage) and "Trip overview" (the whole route full screen).

### Places from other map apps
- Publit is in the share sheet: share a place or a dropped pin from Google Maps, Apple Maps or any app that shares a map link, pick Publit, and Publit opens with routes there from where you are ("Where to now?"). Opening the app from the extension uses a way Apple does not allow for extensions: fine for own installs, may be rejected in App Review.
- A map link pasted into the place search (Google Maps incl. short links maps.app.goo.gl, Apple Maps, OpenStreetMap, `geo:`) or plain coordinates ("45.4436, 12.3264") is shown as the place it points to. Short links are followed once; when a link only names a place, Publit searches that name.
- New link `publit://place?name=…&lat=…&lon=…`: routes there from where you are ("Where to now?").

### Apple Watch
- First watch app (watchOS 10): the nearest stops with their next departures (line, direction, minutes, live), the same 500 m / 1.5 km / 5 km steps and far-stop note as on the iPhone. It loads from Transitous on its own and installs with the iPhone app.

### Tests
- ReachTests (radii, walk steps, walking time, long-walk note from a real Cavallino → San Zaccaria answer, picked places, merged search). SharedPlaceTests (circle lines with real ACTV names; Google, Apple Maps, OSM and geo links; Google short link with name; coordinates in a Google page; `publit://place`). UI tests F18 (far stop at Cavallino), F19 (dropped pin) and F20 (pasted map link). UI test failures are repeated as GitHub annotations, so they are visible on pull requests.

## 0.6.0 (build 33)

### Back to the build 31 layout
- Build 32's screen rebuild is reverted: Nearby, Plan, the started trip, trip overview and sections, Tickets, the native tab bar and the map/button arrangement are again exactly as in build 31.
- Ink & Dot stays a styling layer only (ground, ink actions, hairlines, yellow dot), as described in docs/design/README.md. Removed the remaining shadows on ticket cards and the started live bar.

### Kept fixes from build 32
- DurationFigure's ScaledMetric has a declaration-time wrapped value (build 31 did not compile on iOS).
- Ticket field assignment uses embedded PDF text boxes on unrotated pages; raster/rotated pages still use on-device OCR.
- Settings shows version and build number.
- NativeLayoutTests cover both fixes.

## 0.6.0 (build 31)

### Reliability and clearer trips
- Refresh requests percent-encode `+` in itinerary/trip IDs; base64 IDs no longer become spaces and cause HTTP 400. Backup requests preserve the encoding.
- Calendar events carry one short `publit://trip/<id>` link to the saved trip on this device, rather than a long encoded route plus an unavailable web link.
- Walking distance uses GPS progress only for the current walk and only near its path; previewed and distant walks keep their planned length.
- The live trip bar can be pulled up, settles with a spring, and animates countdown changes. Lists and vertical scrollers reserve its measured height, including large text; a failed-refresh banner reserves space instead of covering content.
- Total and leg durations use larger tabular numbers with small localized units. Route comparison timelines show leg minutes where there is room.

### Ticket review before saving
- Shared files now go through the same review as in-app imports. Nothing is saved until Save; repeated document/page imports remain deduplicated.
- The review offers tappable text boxes on the selected PDF page or image. Zoom, tap text, correct it and assign it to a ticket field, including validity dates. Recognition stays on-device; source bytes and codes stay unchanged.

### Matte Ink & Dot
- All content loading indicators use the logo stroke and landing dot, with a static mark for Reduce Motion.
- Floating app controls use opaque matte surfaces and hairlines, not material blur. Native system bars remain platform-managed.
- Switches use a contrasting green on-state. Settings diagnostics load once per opening without animated row changes.
- Added translations in all 15 app languages and regression tests for query encoding, field mapping, date handling and walking previews. Shared-import UI tests now require review.

## 0.6.0 (build 30)

### Ink & Dot everywhere
- The design now covers every screen, not just Nearby: all lists (Plan, routes, trips, lines, stops, map sheets, tickets, settings, search) sit on the flat warm grey ground; the system blue is gone. Main actions ("Search routes", "Add ticket", …) are ink slabs, links, toggles and selected options are ink, swipe actions are ink/dark grey instead of blue, indigo and yellow, the current location is the yellow dot.
- System dialogs and share sheets follow too (accent colour in the asset catalog is ink).
- Delays on the map use the delay colour; ticket screens use the same ground as the rest of the app.

## 0.6.0 (build 29)

### Backup server when Transitous is down
- If Transitous cannot be reached or answers with a server error, Publit asks the MOTIS project's Europe server instead (same engine, same data and IDs, run separately at TU Darmstadt; live times included). Routes, departures, search, maps, sharing vehicles and saved trips keep working.
- After a failure the backup is asked first for three minutes, so not every request waits for a dead server; then Transitous is tried again. When neither server answers (usually the iPhone's own connection), nothing changes: the saved offline copies are shown as before. Settings → Data mentions the backup.

## 0.6.0 (build 28)

### Clear message where there are no timetables
- When no route is found, Publit checks whether open timetables exist around the start and the destination. Instead of a plain "No routes" it then says "No timetable data for this area yet" (or names the start or the destination) and explains that Publit uses open timetables (Transitous) and none are published there. If the check itself fails, the usual message stays.

### Docs
- docs/DATA-SOURCES.md: sharing feeds (bikes, scooters, mopeds) that are public but missing in Transitous, backup timetable APIs per country (with/without key, status), and other useful open data (entrances, elevators, occupancy, national access points).

## 0.6.0 (build 27)

### Faster route search
- Routes come back in about a second everywhere. Cycling and driving the whole way (the comparison under the routes) are now asked separately and show up as soon as they arrive: a car route through a big city took the server up to ten times as long as the transit search (Paris: 12 s instead of 1 s), and the whole list waited for it. Street routes are reused for 15 minutes; the guide's "next connection" search no longer asks for them at all.

### Live Activity ends with the trip
- After the arrival the Live Activity shows "Arrived" for three minutes and then disappears, instead of staying on the lock screen for hours.
- When the app goes to the background, nothing can update the Live Activity any more: it now ends with its last state, keeps counting down and disappears by itself five minutes after the expected arrival. Back in the app it is running again with live times. Activities of removed or finished trips go at once.

### Dark line colours stay visible
- Near-black lines (e.g. Munich S8) on dark backgrounds and white or pale yellow ones on light backgrounds: badges get a visible edge, and route lines, timeline bars, progress, stop lines and map lines are lifted just enough to stand out. All other line colours stay exactly as the operator publishes them.

### Tests and coverage
- UI tests F02, F03 and F07 fixed: rows under the comparison map and under the live trip bar are scrolled into the clear before tapping (drags start above maps, which would pan instead), and F03 picks Home in the destination search (Plan has had no quick destinations since build 14). Failing UI tests now leave a screenshot (`failed-<test>.png`) in the CI results; their failure messages (`ui-failures.txt`) follow once `.github/workflows/ci.yml` is updated from docs/ci-workflow.yml (the agent may not change workflows).
- docs/COVERAGE.md: what Transitous covers in Europe – cities without data (Athens, Malta, Minsk), partial networks (Istanbul, Kyiv, Pristina) and where live data is missing.

## 0.6.0 (build 26)

### Guide: finding your way in the city
- Platform changes: when the live data moves a departure to another platform, the guide shows "Platform changed (was 3)" at the wait stage, the live bar and the Live Activity (lock screen and Dynamic Island) show the new platform marked as changed, and a notification tells you right away ("Platform change: S8 now leaves from platform 2."). Cancellations of a followed trip are announced the same way. Notifications only come if you already allowed them.
- Exits: when a walk starts underground (U-Bahn, S-Bahn), the guide says which way to surface: "Exit towards Ludwigstraße", or the side of the station ("Exit on the north side") when no street is known.
- Compass direction: the walk shows an arrow that turns with your iPhone ("Head north-east") and, at the end, "Destination in 120 m" with the arrow for the last metres. A new button turns the map with you. Without a compass the arrow has north at the top.
- Haptics and optional spoken directions: a new stage, a turn, "get off at the next stop" and platform changes give a short vibration; spoken directions can be switched on in the guide's toolbar (they lower other audio briefly). Only while the guide is open.
- Changes: a card shows where you change: from which line, from platform to platform (or "Same platform 4"), levels, the walk and the time to spare ("Very tight change" when it gets close).
- Tickets: "Show ticket" right in the wait and ride stages when a saved ticket fits the trip.
- Missed a connection or a cancellation: the guide looks for the next connection from where you are and offers it at the top with "Switch to this connection" (one tap, the guide continues with it) or "New route from here".
- The Live Activity shows what comes next ("Then: U3 at 14:32").

## 0.6.0 (build 25)

### Map: layers, satellite and search
- The layers button opens a sheet instead of a menu. Map: Standard, Muted (paler streets, stations and lines stand out), Satellite (with names) and Satellite without names. Switches for 3D terrain and buildings (the map tilts when switched on), places of interest (shops, sights; Apple's transit icons stay off, the stations are Publit's own) and traffic. On the map: stations (new, can be hidden), lines, vehicles, bikes and scooters. Everything is kept for the next time.
- On satellite maps the map, station names and pins switch to dark, like Apple Maps; pin label outlines and vehicle arrows now follow dark mode.
- Search places and towns right on the map (field below the title): results prefer what is around the map's centre. Picking one flies there and marks it: a city or district as a whole (stations and live vehicles stay visible), a village, a stop, an address or a single place up close. Stops open their departures, places and addresses a sheet with distance, "Route here" (closes the map and plans from your position) and "Open in Apple Maps". Towns and districts leave the map free; tap the marker for the sheet.

### Fix
- Build 24 still did not compile. The real cause: the archive's "Delete archived tickets" action returned a number, which left the whole screen ambiguous for the compiler (reported at the toolbar).

## 0.6.0 (build 24)

### Navigation: a started trip guides you step by step
- "Start trip" (on the trip, or ▶ in the live bar) turns the trip screen into a guide in stages, like Moovit: walk → wait → ride → (change) → arrive. A row of stages at the top shows done, now (yellow dot) and ahead; tap one to look ahead, "Back to now" returns. The stage follows the live times and, while the screen is open, your position (at the stop early → waiting; still far from the stop → walking).
- Walk: distance and time left, the departure you are walking to (with platform, and "Walk faster to make it." when it gets tight), the next instruction ("In 35 m · Turn left onto Ludwigstraße") and the full directions with stairs and levels ("Take the stairs down (level −1)"). The router marks stairs and levels but hardly ever turns, so turns are derived from the path's shape; stairs follow the walking direction. "Open in Apple Maps" hands the walk to Apple Maps with spoken guidance. Off the path → "New route from here".
- Wait: line and direction, "Departs in 4 min", delay, platform, tight-change warnings, disruptions, stops and time to the stop to get off.
- Ride: "Get off at …", stops left and arrival with delay, "Now at …" / "Next: …", progress in the line's colour and all stops on a line with the yellow dot where you are. While the screen is open the position comes from GPS (laid onto the line, accepted within three stops of the live times), otherwise from the live stop times. Still at the stop well after departure → "Missed it?" with a new route from here.
- The map shows the stage's leg in full colour (the rest faded), you, the vehicle and the next turn, and moves to the stage.
- GPS is precise (every 5 m) only while the guide is open and the app is in front; nothing runs in the background.
- Starting a trip sets a get-off reminder for each ride when notifications are already allowed, and moves them with live delays; ending the trip removes them. Walking directions are kept with saved trips and refreshes.

### Fix
- Builds 22 and 23 did not compile ("ambiguous use of toolbar" in the ticket archive): the archive's menu is now typed toolbar content.

### Live bar
- A started trip shows like a running workout: a dark bar with a ring for the trip so far, "Live trip", what to do now, the line, and a big countdown to the next moment (leave, departure, get off). Tap opens the guide. Trips not started yet have ▶ to start them.

## 0.6.0 (build 23)

### Resource use
- Answers in memory keep their decoded form: going back to a screen, or a second screen showing the same departures, no longer decodes the same JSON again. The memory limit (24 MB) counts decoded answers at about three times their size.
- Live answers (departures, vehicles, trips; fresh for seconds) are copied to disk for offline use at most every two minutes per board instead of on every refresh. Large disk use within a session is pruned right away instead of at the next launch.
- In the background, kept answers shrink to the newest 4 MB (and map stops are dropped); on a memory warning all of them go. A smaller suspended app is less likely to be ended by iOS.
- The freeze watchdog sleeps while Publit is in the background, and the diagnostic step trail is written at most every two seconds instead of on every step.
- Live refreshes of saved trips are written once after they settle (2 s) instead of the whole trip file on every refresh; edits are still written right away, and pending refreshes when the app goes to the background.
- In Low Power Mode, or when the phone is hot, open screens (Nearby, map vehicles, Plan, trips, lines, shared routes, live bar) refresh half as often. Live Activities and reminders keep their timing.

## 0.6.0 (build 22)

### Tickets: archive
- Expired tickets stay in the list for one more day (delayed trip, late check), then move to the new archive at the end of the ticket list ("Archive", with count). The archive is sorted by month, newest first, and searchable (title, operator, stations, passenger, ticket number, notes) – e.g. for refunds or expense reports. Nothing is deleted automatically; "Delete archived tickets" removes them with their originals after a confirmation.
- Any ticket can be moved to the archive by hand (long press on the card, or the ⋯ menu of the ticket), e.g. a used-up undated ticket; "Move back to tickets" brings it back (an expired one then stays in the list). Archived tickets are no longer offered for trips.

### Tickets: better recognition
- Operators: the issuer in a UIC rail barcode wins, otherwise the operator named nearest the top (partners and sales agents further down no longer win). 30 more operators and transport associations (Italo, Trenord, Eurostar, iryo, PKP Intercity, MÁV, ZSSK, SŽ, DSB, SJ, Leo Express, BLS, ZVV, PostAuto, RATP, VOR, OÖVV, VVT, Salzburg Verkehr, Verbund Linie, Graz Linien, Linz AG, Wiener Lokalbahnen, HVV, VBB, MVG, RMV, VRR, VVS, KVB, VGN, DPP).
- Products: Standard-Ticket, Einfach-Raus-Ticket, Quer-durchs-Land, Bayern-Ticket, Niedersachsenticket, Semesterticket, Jobticket, Kurzstrecke, Streifenkarte, (Super)Sparbillett, GA, Navigo, Travelcard, Frecciarossa; BahnCard/Vorteilscard/Halbtax only when nothing else matches. Ticket types in more languages (Dutch, Spanish, Portuguese, Polish, Czech, Swedish, Hungarian, Turkish).
- Route: timetable rows as on DB/ÖBB tickets ("Berlin Hbf 14.10. ab 08:30 … München Hbf an 12:31": first departure to last arrival), "A → B → C" (B as via), "von … nach … über …", French/Italian/Dutch/Swedish "de … à …", "da … a …", "van … naar …", "från … till …", more labels (Abfahrtsbahnhof, Zielbahnhof, Einstieg, Origen/Destino, Vertrek/Aankomst …). New "Via" field in the editor and on the card.
- Validity: shared-month ranges ("14.–20.10.2025", "28.12.–03.01.2026"), dd/mm/yy, dd-mm-yyyy, "Oct 14, 2025", "14th October 2025", "14 de octubre de 2025", month names in 12 languages, French times ("08h12"); a length printed with the start ("Gültig 90 Minuten", "24-Stunden-Karte", "7 Tage gültig ab …") sets the end; return tickets cover outbound to return day; validated/activated times count as start; more purchase-date words are ignored.
- Passenger: more labels (Passager, Nome, Naam, Pasażer, Cestující …), traveller counts ("2 Erwachsene", "1 Adulto"; not "bis zu 5 Personen") and reductions (BahnCard 25/50/100, BC 50, Vorteilscard, Halbtax, Carte Avantage, Railcards) are added, e.g. "2 Erwachsene · BC 50"; advertising lines are skipped.
- Class: "Klasse: 2", "KL. 1", "2e klas", "1. třída", "Klasa 2", "Second/Standard Class", "première classe" ….
- Price: totals before other prices ("Gesamtbetrag" before "Fahrpreis"), tax lines ("MwSt 19 %") ignored, "2 x 19,90 € = 39,80 €" gives the result, thousands separators ("CHF 1'250.00"), "Fr. 12.80", "19,– €", more currencies.
- Ticket numbers: many more labels (Bestellnummer, Vorgangsnummer, Référence du dossier, Codice prenotazione, Localizador, Boekingsnummer, Numer biletu, Číslo dokladu, Bokningsnummer …).
- Patterns are compiled once, so reading a page is much faster.

## 0.6.0 (build 21)

### Diagnostics
- The diagnostics file now records more than freezes: crashes and hangs reported by iOS (MetricKit, delivered at the next launch, with the stack of the thread that crashed), unexpected exits (the app ended while it was open – crash, watchdog or out of memory), memory warnings, and failed requests (network error code, HTTP status, or the field that could not be read in an answer). The same error within 5 minutes is noted only once.
- Each entry has a header with kind, time, version and build, device and iOS version, then the last 40 steps (screens, taps, requests with status and duration).
- Settings → Diagnostics shows the counts, "Share diagnostics" (one text file with device, language, time zone, memory, power and thermal state, the current steps and all entries), "Report a problem" (opens a new GitHub issue with version, device and counts filled in) and "Delete diagnostics".
- Still no places, stops or tickets in the log; nothing leaves the device unless shared.

## 0.6.0 (build 20)

### Design "Ink & Dot", first screen: Nearby
- New design language (docs/design: principles, tokens, clickable prototype): flat opaque cards on a warm grey ground, glass only for floating controls (tab bar, live trip bar), ink instead of a brand colour, the logo's yellow dot only for "you" and "live".
- Nearby: each station heads its own card (bold name, distance; the nearest stop has the yellow dot) instead of a grey header above it; cards on the new ground; buttons and links in ink.
- Departure boards (Nearby and a station's departures): the minutes are the big figure ("4 min", orange when late), clock time, delay and Live/Timetable small underneath; a cancelled departure shows its time struck through in red. Rows are centred vertically.
- Mode chips everywhere: the selected chip is ink (black, white in dark mode) instead of blue.
- Live trip bar: progress drawn as the logo's stroke with the yellow dot at the current position; hairline border unless late or cancelled; corner radius 22 like the cards.
- Other screens are unchanged for now. Not yet seen on a device.

## 0.6.0 (build 19)

### Sharing
- Shared links now carry the route itself instead of this phone's saved-trip ID, so they work for anyone with Publit and on any device. The route (Transitous itinerary ID, compressed), start, destination and departure are in the link; the receiving iPhone fetches it again from Transitous with live times.
- "Share arrival" (before arrival) sends the arrival text plus that link; after the trip the button is "Share trip". Departure boards have "Share departures".
- Links are https (`https://elija-7580.github.io/Publit-/open/#…`), so messengers make them tappable. The small page (`docs/open/index.html`) shows the route or stop, opens Publit, or offers Apple Maps / Google Maps for people without Publit. The part after `#` stays in the browser. Needs GitHub Pages: Settings → Pages → Deploy from a branch → `main` / `/docs`.
- Opening a route link: if it is one of your saved trips, that trip opens; otherwise the route with live status (refreshed every 90 s) and "Save trip". If Transitous no longer knows the route, "Search this route" fills Plan with the same places and time.
- Calendar entries link to the route too (URL field `publit://route?…`, notes with the web link), so they also open on another device or after the trip was deleted.
- Not yet confirmed on a device. The link includes the exact start point (e.g. home), as any shared route does.

## 0.6.0 (build 18)

- Trip tickets: several tickets for one trip are all shown, oldest first. A ticket added from a trip now always belongs to that trip; before, only tickets recognised as a single ticket were linked, so one recognised (or changed on the check screen) as a day pass, return ticket or other could drop out of the trip. The ticket section on the trip lists all tickets instead of the first three.
- Tickets added in the wallet without a trip now also show on a trip when their dates cover its departure, like passes. Not yet confirmed on a device.

## 0.6.0 (build 17)

- Live trip bar: a trip that is under way now shows as a bar at the bottom of every tab, above the tab bar. It appears for a trip started with "Remind me to leave", otherwise for a saved trip from 30 minutes before departure until 5 minutes after arrival.
- The bar shows the line of the current or next vehicle (or walking/arrival), the status ("Leave in 4 min", "Change at …", "Arrived"), the delay and a progress line. A tap opens the trip; the x hides the bar for that trip. While visible it updates the times every 90 seconds.
- Not yet confirmed on a device.

## 0.6.0 (build 16)

- Freeze log from build 15: no more freezes on the trip screen. Two short ones remain: 3.2 s when opening "Where to now?" (the same SwiftUI loop asking itself for the next update), and text layout while the app went to the background.
- "Recently searched" showed its time with `Text(date, style: .relative)`, which redraws itself every second. It now shows the time to the minute from the shared minute clock ("5 minutes ago", "now"). Likely cause of the 3.2 s, not confirmed on a device.

## 0.6.0 (build 15)

- Station map: the mode filter was sometimes not drawn (an empty strip under the bar) while its chips still took taps (screenshot from build 14). It was a horizontally scrolling row; on the map it is now one fixed row of symbols (All, Train, S-Bahn, Metro, Tram, Bus, Ferry) without scrolling, on a plain background. VoiceOver reads the mode names. Not yet confirmed on a device.
- Plan: the symbol legend (walk, change, …) under the routes is gone; the start and end time of the time axis stay.

## 0.6.0 (build 14)

### Plan
- No "Quick" section. Choosing From or To lists Home and Work first, then the current location, other saved places and recent places.
- The swap button sits between From and To on the right, not in the navigation bar.
- "To the first stop" is no longer on the Plan screen (it is in Route options, also reachable from Settings); Route options is one small line.
- Routes load after "Search routes" once both ends are set. After that, changing places, time or options searches again by itself; clearing the destination starts over. A recent search or "Plan again" searches at once.

### Station map: stations and vehicles missing at times
- An area counted as loaded as soon as its stations were downloaded, before their pins were made. If that was cut off (map closed, app in the background), the area stayed empty until the map was moved far enough. It now counts only once the pins are there.
- A failed station download is tried again after 5 s (before: only when the map moved), with a short note.
- After returning to the map, the live layer (lines and vehicles) loads again at once instead of after up to 30 s.
- The first map position is used even if MapKit reports no region change for it.

## 0.6.0 (build 13)

- Build 12 fixed the freeze on the trip screen (confirmed on the device).
- Station map: the mode chips (Train, Bus, …) have their own space below the navigation bar instead of floating over the map. They could end up hidden, and then an active filter hid stations and vehicles without showing why.
- Stops have "Route here" and "Route from here": both open the Plan tab with the stop as destination or start (the other end stays). From the station map, the map closes so Plan is visible.
- Plan is for planning only: From/To, time, options, Home/Work and recent searches. Saved trips moved to Saved: upcoming trips right below Home and Work, past trips on their own screen ("Plan again" switches to Plan).

## 0.6.0 (build 12)

### Freeze on the trip screen (freeze log from build 11)
- The call stacks show no app code: the main thread is inside SwiftUI's drawing, and while drawing SwiftUI asks itself for the next update (`requestUpdate(after:)`) again and again, until about 15 s have passed: the period of the trip screen's timeline. After the trip has loaded it repeats for another ~15 s.
- All timelines (`TimelineView`) are replaced by plain clocks: departure countdowns read one shared minute clock, the trip and map-vehicle screens keep their own time and update it every 15 s, the route status every 30 s, the line map every 5 s. Nothing else changes on screen. Likely cause, not confirmed on a device.
- The fixed timeline start from build 10 is removed.

## 0.6.0 (build 11)

### Better freeze log
- The log from build 9/10 shows the freeze starting as soon as a trip opens, before its data has loaded (7.5 s), then about 15 s more after loading. It had no build number, so it is unclear which build was running.
- Each freeze entry now has the build number and device model, and the main thread's call stack (function names from the app and iOS) at 2, 5 and 8 s. That shows where the app is stuck. No stack is taken while Xcode's debugger is attached (it would stop on the signal used).

## 0.6.0 (build 10)

### Freeze on the trip screen (freeze log from build 9)
- Still about 15 s without response after tapping a departure in Nearby, even for a short trip (13 stops, 102 map points), and already about 2 s while the trip screen opens, before the map is shown. So neither the map's size nor the list refresh explains it.
- What the trip and route screens share is "Show ticket" in the navigation bar: an item that is empty when there is no ticket, with its ticket sheets inside the bar. It is now added only when there is a ticket, and the sheets hang on the screen. Likely cause, not confirmed on a device.
- Periodic timelines (trip, route, line, map vehicle) use a fixed start instead of restarting their schedule on every redraw.

## 0.6.0 (build 9)

### Freeze when opening a trip from Nearby (freeze log from build 8)
- The log showed a 14.6 s freeze one second after opening a trip from the Nearby board, right after returning to the app. Likely cause: the board first shows the last saved departures, then the fresh ones arrive with new live times. A departure's list ID contained its live time, so a new delay made it a different row, and the trip opened from that row was closed while it was still open. Not yet confirmed on the device.
- Departure IDs now use the timetable time; repeated departures in a response appear once.
- Trips open from the Nearby board by value, so they stay open when the board refreshes or the departure leaves it.
- Trip screen: stops, alerts and the map shape are worked out once per load. The map is redrawn only when the route or the vehicle moves, and has no title labels.
- Alerts with the same priority and title keep a fixed order (before, the order could change on every redraw).
- Freeze log also notes when Nearby and a trip have loaded (counts only).

## 0.6.0 (build 8)

### Freezes (reported as "scene-update" hangs on 0.1.0 and 0.6.0 build 7)
- Both device reports show the main thread busy laying out views for more than 10 s, the second while a back swipe started. The reports contain no app code, so the screen is not known yet; the changes below remove likely causes and are not confirmed on a device.
- Route actions: the button grid is no longer a `LazyVGrid` inside a list row; a fixed two-column layout measures each button once.
- Route summary and connection legend choose their layout by text size instead of `ViewThatFits`, so a row cannot switch height between layout passes. The line chain places badges exactly as it measured them.
- "Add ticket" sheet on the route screen is attached once instead of to every ticket row.
- A trip unsaved on the route screen is deleted shortly after the back animation, not during it. Closing a screen with nothing to delete no longer redraws other screens.
- New: Settings → Diagnostics. If the app does not respond for 2 s or more, the time and the screens opened just before are written to a local file that can be shared. Only fixed screen names and times; nothing leaves the device unless shared.

## 0.6.0 (build 7)

### Fixes from device testing (need confirmation on the device)
- Paper-ticket scan: add the missing camera usage description (`NSCameraUsageDescription`, 15 languages); without it iOS stops an app that opens the camera.
- Ticket import: a ticket appears only after its document and metadata are written; write errors are reported, and a failed save leaves no ticket or orphan document behind. Photo/file pickers no longer cancel the running import. No automatic logo lookup while saving.
- Live data: malformed route shapes, invalid coordinates, non-finite distances and extreme delays are rejected; MapKit only gets valid coordinates. The reported live-data crash had no crash log, so its cause is not confirmed.
- Widget: stop search asks for stops only and skips entries without a valid ID or coordinate.

### Loading
- Nearby: one load per location/mode change instead of competing loads, smaller first request, last board shown first, small GPS movements reuse the board.
- Map: panning and zooming no longer cancel loading; stations, vehicles and rentals load separately and stay visible. Loaded areas are reused; stop grouping runs off the main thread. A failed live layer shows a note and retries after 10 s.
- Lines open with the departures already shown; Plan does not repeat the same search within 45 s.
- No timings measured on a device for this build; server response time is outside the app.

### Navigation and layout
- Lines tab removed: saved lines and stops are in Saved, with "Find a line". Plan shows the next saved trips.
- Route actions: full-width divider under Start trip, even two-column grid with wrapping labels (one column with large text).
- Connections: legend and "How to read connections" explain the timeline bars (later departures start farther right; coloured = ride, grey = walk, dotted = wait). Timelines can be turned off in Settings.
- Saved trips: tapping "Saved" on an open trip unsaves it, but the trip stays on the device until that screen closes, so it can be saved again. Opening a stop or ticket from the trip does not count as closing; after a relaunch the trip is still saved.
- Settings: travel preferences (route options, timelines, map layers).
- Map: larger labels; bike and scooter pins show type, availability and provider and open details. Walking lines use crisp dashes.
- Launch animation: only the moving icon, no spinner underneath.

### Getting to the first stop
- Plan: choose Walk, Bike (Bike & Ride) or Car (Park & Ride) to the first stop. Walking variants stay in the list for comparison. Shared bikes/scooters stay in Route options. Park & Ride is slow on the Transitous server (about 20–25 s in tests) and uses a 45 s timeout.

## 0.5.1 (build 6)

### Shared tickets
- Register PDF/image Open In / Copy to Publit handlers. When iOS hands Publit a local document, recognize it locally and auto-save recognizable tickets with their unchanged source. Uncertain documents go to review; a unique ticket page is selected automatically from PDFs up to 12 pages, otherwise the user chooses. Exact repeated document/page imports do not create duplicates.
- Automatic validity uses labelled date contexts only, not arbitrary dates. Unknown validity stays unknown; recognition does not verify authenticity. Reversed dates require correction rather than silently swapping them.
- Simulator fixtures and F16 cover real PDF/image recognition and multi-page/review paths. Source-app share-sheet visibility still needs device testing; this is a document handler, not a Share Extension.

### Performance review
- Periodic departures, routes and vehicles pause in background/hidden screens; Nearby polls movement every five seconds after its first fix instead of every half-second.
- Fix shared-request cancellation: each cancelled waiter leaves immediately; the last waiter cancels the download. A stale completion cannot remove a newer flight.
- Skip redundant OCR when a PDF already has embedded text; analyze pages sequentially; bound file reads at 25 MiB and PDF auto-analysis at 12 pages. Page-choice thumbnails release when offscreen.
- Do not retain responses larger than the entire 24 MiB memory cache. Updating an existing entry no longer evicts half the cache. Operator-image cache has explicit count/cost limits.
- Fix UI checks affected by the fifth tab (German Saved) and ensure the route sort control is scrolled into view before tapping.


## 0.5.0 (build 5)

### New
- Tickets tab: add a ticket from a PDF, photo, screenshot or paper scan. Publit reads it on the iPhone (nothing is uploaded), you check the details, and the code is ready for the gate at full brightness. Day, weekly and annual passes show up on every trip while they are valid; single tickets can belong to a trip ("Add ticket for this trip", "Show ticket" on route and trip screens, Live Activity button).
- Operator logos on tickets: suggested for known operators from Wikimedia Commons (via Wikidata), or chosen from several suggestions, from Photos, or none. Only the operator name is sent.
- Apple Wallet (optional, needs a Pass Type ID and the signing service): passes carry the operator logo and icon.
- Codes stay as issued: QR, Aztec, PDF417 and Data Matrix are redrawn from the scanned symbol (same version, error correction and binary content), Code 128 from its content; EAN, UPC, ITF, Code 39/93 and Codabar are shown cut out of the original with their quiet zone. Redrawn codes use whole screen pixels per module, without smoothing.
- Bus and tram stops on either side of the road are separate when you zoom in and on Nearby, each with its directions ("→ Moosach · Pasing"); departures and walking directions go to exactly that stop.
- Stop screen: "Walk there" with the walking time to that stop (opens Apple Maps on foot) and a labelled "Route here".
- Short launch animation while location and the first departures load underneath (skipped with Reduce Motion).

### Faster and more reliable
- Station pins stay where they are while zooming and panning: each station sits on its main platform instead of the average of whatever is loaded, and the area loaded is slightly larger than the screen. Station names hide instead of overlapping, and drop the city ("Karolinenplatz", not "München, Karolinenplatz").
- Identical requests that overlap share one download; a dropped connection or a busy server (502–504) is retried once.
- Live Activity: refreshed every 10 min when the trip is more than an hour away, every 3 min within the hour, every minute shortly before leaving and near the end of a ride, every 2 min on long rides. Unchanged content is not sent to the lock screen, the headline no longer holds a minute count that goes stale, and live refreshes skip the fare data.
- GPS stops while Publit is in the background. No second response cache in memory or on disk.

### CI
- `docs/ci-workflow.yml`: faster workflow (cancels outdated runs, skips doc-only commits, boots the simulator during the build, UI tests on demand with `[ui]` or `[ui F14 F15]` in the commit message and nightly). It replaces `.github/workflows/ci.yml` once copied there (see `docs/ci.md`).

## 0.4.0 (build 4)

### New
- Lines tab: the lines around you and your saved lines, searchable by line number ("U6", "bus 52") or destination, and stations by name. Filter by mode.
- Line screen: the route on a map with the vehicles on it now (estimated from live departure times), both directions, alerts, every stop with minutes from the start. Tap a stop for its timetable for any day, by hour.
- Station map: line routes in their colours and live vehicles moving along them, tap a vehicle for its trip and next stops. Shared bikes, e-scooters and their stations as a layer. Layers can be switched on and off; modes filter lines and vehicles too.
- Shared bikes and e-scooters in route planning (Route options, off by default): routes can start or end on a rented vehicle, with a link to the provider's app.
- Notifications for disruptions on saved lines: alerts and cancelled departures, checked on opening and in the background (Settings → Notifications).
- Sort routes by departure, fastest, fewest changes or least walking.
- Share arrival: send your live arrival time from a route or trip; when a followed trip runs late, Publit offers to send the new time.

### Faster
- The station map uses MapKit directly: line routes are grouped by colour into few overlays, vehicles glide between positions instead of being redrawn, and pins cluster.
- Map data loads only for the visible area and only while the map is on screen; lines and vehicles appear from street level, rentals when zoomed in further. Answers are decoded and simplified off the main thread and cached briefly.
- Line search uses one request for all departures nearby (cached 30 min); timetables for a day are cached for hours.

### Not included
- Vehicle occupancy: Transitous publishes no occupancy data, so Publit cannot show how full a vehicle is.

## 0.3.0 (build 3)

### New
- Filter by mode: chips on Nearby (kept between launches) and at every station: All, Train, S-Bahn, Metro, Tram, Bus, Ferry, Other. Only modes that run there are offered.
- Lines at a station: every line with its next departures per direction; tap for the line, swipe to save it.
- Route options for Plan and "Where to now?": transport modes, number of changes, walk to and from stops (5–30 min), step-free (wheelchair). The active filters are shown under the button.
- Walking, cycling and driving the whole way, next to the routes ("Other ways"), with the difference to the fastest public transport route. Driving times are without traffic and parking. Can be switched off in Route options.
- Search scopes: All, Stops, Addresses, Places.

## 0.2.0 (build 2)

First update after 0.1. Install it on top of 0.1: saved places, trips and history stay.

### New
- Nearby: live departures grouped by station, alerts, rail replacement buses, "Where to now?" with Home and Work, the next saved trip.
- Plan: trips for later (depart at / arrive by), more connections, walking when it is about as fast, routes from and to the current location.
- Route screen: summary and actions first (start trip, save, calendar, leave reminder), steps, map, fares.
- Saved trips with live status, a calendar entry that links back, a leave reminder and a Live Activity on the lock screen.
- Trip screen: every stop of a vehicle with live times and an estimated position. Save a line at a stop.
- Station map, departures widget, Siri and Shortcuts ("Take me home"), offline copies of the last answers.
- 15 languages.

### Faster
- Nearby shows the last board at once and no longer restarts while GPS settles.
- Times in server answers are read several times faster; route answers with thousands of times decode sooner.
- Offline copies are written to disk in the background, after the answer is already on screen.
- Screens no longer redraw on every location fix; departure countdowns redraw together once a minute.
- Station search reuses cached answers around the same place (search bias rounded to about 1 km).

### Fixed
- A pending leave reminder now moves when live times shift the departure (delay or earlier train).
- Station map: a station split by name no longer gives two pins with the same ID (wrong sheet on tap).
- Switching a trip's reminder on or off always redraws the saved-trip rows.

### Privacy
- Coordinates in requests are rounded to about 1 m, search bias to about 1 km.

### Not in this version
- Tickets as passes are built (`Sources/Tickets`) but not switched on. See [docs/tickets.md](docs/tickets.md).

# Transit presentation and trip workflows

- Line badges prefer the feed's compact `routeShortName`; an additional service
  identity (`displayName`, e.g. ICE 615) remains visible separately.
- Operator-provided route colours are kept. Missing line colours use a neutral
  badge rather than an invented U-Bahn network mapping. Small badge text now
  requires 4.5:1 contrast and uses a transport symbol and line name as well.
- The advertised vehicle direction is labelled "Towards". It is never silently
  replaced by the passenger's alighting stop; missing headsigns are explicit.
- Route legs separately label "Board here" and "Get off here". Long stop names
  wrap. Departure boards prioritise clock time, then countdown, delay and
  Live/Timetable provenance. Accessibility text sizes use a stacked layout.
- Search history stores the latest 20 origin/destination pairs on the device.
  It starts recording after this update, not retroactively. An old search is
  rerun for fresh departures. History entries can be deleted or cleared.
- "Start trip" saves and activates the connection, opens the live trip view,
  and requests a Live Activity. Only one manually started trip is active.
  "End trip" stops its tracking and Live Activity. Starting another ends the
  previous manually active trip. Completed saved trips remain in Past trips.
- Live status and server predictions refresh while the app is active. There is
  no push server: background Live Activity updates are not guaranteed. If Live
  Activities are unavailable, live tracking remains available in the app.
- Tickets have a metadata card and an embedded stored image. Original image
  files are preserved, not JPEG-recompressed. The display uses a bounded
  thumbnail cache; Original loads the source image. No code is cropped,
  recreated or overlaid. An attachment is not proof of ticket validity.

## Disruptions and replacement services

Alerts retain their effect, severity, communication and impact periods.
Relevant station/platform/leg alerts are deduplicated, sorted by severity and
expandable. No-service, diversions, moved stops and major delays are prominent.
An explicitly advertised SEV bus is labelled Rail replacement bus. Text that
mentions replacement service is presented as information, not as a guarantee
that a particular replacement bus is running.

Coverage depends on the operator's realtime feed and Transitous. Missing alerts
are not a claim that the route is disruption-free. Cached data can be outdated.

## Fares

MOTIS fare data is requested with the experimental `withFares` parameter.
Named products and their rider/media conditions are shown only when supplied.
A total estimate requires complete transit-leg coverage, a known transfer rule,
one unambiguous unrestricted default bundle per required fare component and
one currency. Transfer rules A_AB, A_AB_B and AB are honoured; child-only,
ambiguous, unsupported-media and mixed-currency estimates are rejected.
Otherwise there is no invented price; use the operator's ticket links. These
are experimental data-derived estimates, not binding offers or reservations.

## QA

Regression tests cover identity, contrast, missing headsigns, alert windows,
fare transfer arithmetic, history persistence/replay, active-trip state,
byte-exact ticket import and concurrent refresh coalescing.

UI tests F09–F11 use DEBUG-only, explicitly marked demonstration data to capture
light/dark/accessibility layouts, direction vs alighting, live start/end,
embedded/original tickets, expanded disruptions and conditional fare display.
The demo is compiled out of Release builds. F01–F08 still exercise the original
live-API flows and German navigation.

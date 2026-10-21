# Performance changes and verification

## Changes

- Station grouping indexes candidates by normalised name and memoises repeated
  names. It preserves the previous grouping semantics (name + 300 m radius).
- Concurrent live refreshes from an open trip and the foreground activity loop
  share one request. A 3-second result window absorbs staggered timers. Errors
  are not cached; subsequent requests may retry.
- Persisted trip, ticket and history snapshots are encoded and atomically
  written on an ordered utility queue, not in a SwiftUI event handler. Reloads
  flush earlier snapshots; critical ticket imports await completed storage.
- Ticket file loading, decoding and import writes run off the main actor.
  Display thumbnails are capped at 2048 pixels; a 6-item / 48 MiB cache limits
  retained images. Original source bytes remain unchanged.
- Number formatters are reused by locale. Clock formatters are keyed by locale
  and timezone, with bounded caches. Locale changes do not retain old formatting.
- Loading the previous nearby board uses a non-main-actor async path.
- Existing 300 ms place-search debounce remains. Token checks prevent an old
  cancelled search from replacing newer results or clearing its loading state.
  Cancelled plan searches no longer leave a stuck loading state.
- Points of Interest signposts cover transit requests, local saves, ticket
  import and ticket image loading. They log no location, ticket contents or URLs.

## Measured scope

The regression benchmark compares the previous and indexed grouping algorithms
on the same 1,200-record fixture, five runs each. In the Linux Swift 6.0.3 debug
workspace run: previous 28.860 ms, indexed 6.561 ms (4.40× faster). This measures
that algorithm and workload, not device FPS, app launch time or all API latency.
The test prints new results on each run and checks result parity. It does not
use a brittle fixed performance threshold.

A concurrency test submits 12 identical refreshes and verifies one loader call,
then verifies expiry and retry after errors. UI regression tests cover ordinary,
dark and large accessibility text layouts.

## On-device profiling

Use Instruments Time Profiler, Hangs, Allocations and Points of Interest on a
physical iPhone. Compare cold launch, scrolling Nearby, route comparison, an
80-row departure refresh, repeated trip open/close, a large ticket image and
10-minute active-trip tracking. Record main thread stalls, frame hitches, peak
memory, request count and battery impact.

As of 2026-10-13 (0.5.1 / build 6, `c38c2a5`):

- Debug build installs and launches on Elijá’s iPhone Pro (`io.github.publit.app`)
  via CoreDevice.
- Simulator iPhone 17 Pro (iOS 26.5) Instruments:
  - **Time Profiler** (10.6 s attach): completed; `potential-hangs` export returned
    schema only (no hang rows above 250 ms microhang threshold in that idle window).
  - **Allocations** (10.6 s attach): completed; open the `.trace` in Instruments for
    peak heap — CLI export does not print a single peak-RSS number here.
  - **App Launch** / `--launch` often fails with ambiguous Publit vs
    PublitWidgets process when multiple installs exist; uninstall duplicates first.
- Physical-device `xctrace` attach remains unreliable (device boot timeout /
  ambiguous Publit vs widget). Use Xcode → Product → Profile over USB for Network
  and Energy Log while scrolling Nearby and comparing routes.
- Do not treat CI Linux grouping benchmarks as device FPS or battery results.

## Outdoor checklist (physical iPhone)

- [ ] Location permission and Nearby departures around you
- [ ] Plan a route (now and later)
- [ ] Start a trip: Live Activity / Dynamic Island updates
- [ ] Home-screen widget (small / medium)
- [ ] Siri / App Shortcut for a saved stop
- [ ] Ticket full screen at full brightness
- [ ] Airplane / offline: last departures and saved trips still readable
- [ ] Dark Mode
- [ ] Larger Dynamic Type
- [ ] VoiceOver on Nearby and ticket screens

## 0.5.1 code review and regression checks

This is a code/lifecycle review, not a measured device performance profile. Existing native SwiftUI,
transit data, privacy rules, persistence and visual foundations remain in place.

| Finding | Targeted change | Verification |
| --- | --- | --- |
| Shared downloads counted cancelled callers until completion, so all-cancelled flights could keep running | Per-waiter continuations, last-waiter cancellation, generation IDs | Cancellation/reuse tests + existing overlap/retry tests |
| Periodic view tasks could survive background/hidden screens | Scene/visibility/tab task keys on Nearby, Plan, Go Now, trip/line/stop views | Source inspection; UI regression; device energy check still pending |
| Nearby woke twice a second even after GPS settled | Five-second movement checks after first fix; keep 30-second server cadence | Source inspection; physical walking/re-entry check pending |
| PDF text was recognized by OCR and then discarded for embedded text | Embedded text suppresses OCR, barcode detection remains | iOS synthetic PDF/image recognition tests |
| Long PDFs could accumulate every visited thumbnail | Cell-local thumbnails cleared offscreen; sequential bounded auto-analysis | Code inspection; device Allocations check pending |
| A single oversized response bypassed the memory budget | Skip its memory retention; replacing keys does not trigger count pruning | Cache regression tests |
| Operator logos had no explicit retained-image budget | 80 entries / 8 MiB decoded-cost NSCache limits | Source inspection; device scrolling check pending |

### Test handoff

1. Cold launch, switch all five tabs, open/close route/line/stop screens, then background/return.
2. Share a PDF from Files/Mail and an image from WhatsApp where Open In is offered. If Publit is
   absent from that host's share sheet, use the in-app file picker; no Share Extension is shipped.
3. Single ticket, ticket + terms PDF, two tickets in one PDF, an expired ticket, no labelled dates,
   an ordinary non-ticket image, malformed data and a file above 25 MiB.
4. Repeat the same shared file: only one pass. Delete and re-import: source/code still accessible.
5. Check every extracted validity against the original, including timezone-sensitive tickets.
6. Instruments on the physical iPhone: Time Profiler/Hangs, Allocations, Network and Energy Log
   during 10 minutes foreground, 10 minutes background, map panning and repeated 25 MiB imports.
   Record actual traces before making FPS, memory-peak or battery improvement claims.

Residuals: a started Vision operation may finish after dismissal (saving is cancellation-guarded);
small operator-logo cache misses still load synchronously; TripRefreshCoordinator deliberately
finishes a short shared in-flight refresh. Device traces are needed to decide whether these matter.
No new background privilege, third-party import service or UI/framework rewrite was introduced.

# Publit native Android — porting plan and status

Goal: Publit for Android as a native Kotlin + Jetpack Compose app, as light and fast as possible.
The iOS app (Swift, `Sources/Publit/`) is the reference; Android is ported feature by feature, not shared.
Old Skip port (`Android/`, `Sources/`) stays until the native app replaces it.

## Rules (every session)
- Read only this file, `CLAUDE.md` and the iOS files of the current milestone. No repo-wide exploration.
- Port behaviour 1:1 from Swift; keep English string keys exactly as in Swift (`L10n.t("Nearby")`, `L10n.f("%lld min", n)`).
- Architecture: `data/` (models, API, pure logic, unit-tested) → `ui/<screen>/` ViewModel (StateFlow) + Composables.
  Location/GPS only while a screen is resumed. No new libraries without need.
- Build on GitHub Actions (local Maven downloads get rate-limited). Read only the error lines of the log.
- Commit + push after every finished step (session limits end work without warning); WIP is pushed and marked in the status.
- Each milestone ends with: tests green, APK on release `native-latest`, status below updated, commit + push.
- Device: Huawei Mate 20 Lite (Android ≤10, slow). Test there; crash report is shown in-app at next start.

## Layout
`app/src/main/java/io/github/elija7580/publit/`: `core/` (L10n, Fmt, Geo), `data/` (TransitModels, ModeStyle,
LineColors, Alerts, StopGrouping, TransitousClient), `location/LocationRepository`, `ui/` (Theme, Components,
NearbyViewModel, PublitApp, StopScreen, PlanScreen, TripScreen, PublitMap, StationMapScreen, SavedScreen, SettingsScreen), `CrashLog`, `PublitApplication`, `MainActivity`.
Strings: `tools/strings.py` turns the iOS catalog into `assets/strings/<lang>.json` (CI step, not committed).
Two test APKs per release: arm64 (`Publit-native.apk`) and 32-bit ARM (`Publit-native-32bit.apk`). App ID `io.github.elija7580.publit` (replaces the old Skip build; uninstall that once, its signing key differs), fixed test key `app/publit-test.jks` until the Play upload key is a CI secret.

## Milestones
| # | Milestone | iOS sources | Status |
|---|-----------|-------------|--------|
| 1 | Scaffold, core logic, location, Nearby | NearbyView, TransitModels, StopGrouping, TransitFilter, LineColors, ModeStyle | done (Google + GPS + network location, crash report in-app) |
| 2 | CI build + release `native-latest`, compile fixes, unit tests | – | done (`.github/workflows/android-native.yml`, 1.8 MB APK) |
| 3 | Stop departures + lines view, bottom tabs, navigation | NearbyView (StopDeparturesView), LineGrouping | done |
| 4 | Place search + Plan (route search, results) | PlanView, PlaceSearchView, PlanModel, TransitousClient.plan, Reach | done (basic: no route options, fares, smart routing yet) |
| 5 | Itinerary detail + trip view | ItineraryDetailView, TripView, TripStatus | done (trip view: stops, live progress, route here/from here; map follows in 6, save line in 7) |
| 6 | Map (MapLibre) + station map | Views/Map, StationMapView, MapLayers | done (MapLibre/OpenFreeMap: trip + route map, station map with search and mode filter; open: live vehicles, line shapes, sharing, layers sheet, category search) |
| 7 | Saved places/lines/trips, settings, onboarding | FavoritesView, FavoritesStore, SettingsView, OnboardingView | done (Home/Work, saved stops/places/lines, save stop + save line, line departures, settings: start screen/data/about, onboarding; open: saved trips (TripStore, SavedTripView), settings for map/route options/notifications/diagnostics as their features arrive) |
| 8 | Live trip, notifications, reminders | LiveTripView, Notifications/* | done (saved + past trips, save/calendar/share/leave reminder, start trip → live view, get-off reminders, ongoing live notification refreshed by alarms; open: line alerts (LineAlertMonitor), voice guide, off-route/missed detection, alternatives) |
| 9 | Live sources overlay, offline cache | LiveSources*, ResponseCache | done (offline copies + note; live sources ÖBB, Wiener Linien, VBB, RMV, VBN, NAH.SH; open: search.ch, VRR/VRN (EFA), ACTV, SL, TfL, LiveSourcesView, settings toggle) |
| 10 | Tickets, widget, Wear OS | Tickets/*, DroidWidget.kt, Android/wear | widget done (Glance, stop picker); Wear OS done (standalone module `wear/`, from the Skip port); tickets in progress |
| 11 | Switch app ID to `io.github.elija7580.publit`, release signing, remove Skip port | – | open (needs owner: Play upload key as CI secret, OK to replace the installed Skip app) |
